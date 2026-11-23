package app.frict.android

import java.net.HttpURLConnection
import java.net.URLDecoder
import org.json.JSONException
import org.json.JSONObject

const val PENDING_PATH = "/device/v1/pending"
const val CHALLENGE_PATH = "/device/v1/challenge"
const val ANSWER_PATH = "/device/v1/answer"
const val FID_PATH = "/device/v1/fid"
const val REMOVE_PATH = "/device/v1/remove"

/** Until the server sets one; also the wait after a failed poll. */
const val DEFAULT_POLL_SECONDS = 5L

/** A pending sign-in, as `/device/v1/challenge` describes it. Any field but [id] may be empty. */
data class Challenge(
    val id: String,
    val username: String,
    val ipaddr: String,
    /** Authentik's `pushinfo` form fields, e.g. Domain and Application, in order. */
    val pushinfo: List<Pair<String, String>>,
    val type: String,
    val created: Long,
    val expires: Long,
)

/** The three answers `/device/v1/answer` accepts. */
enum class Answer(val decision: String, val reason: String?) {
    APPROVE("approve", null),
    DENY("deny", "cancel"),
    NOT_ME("deny", "not_me"),
}

sealed interface PollUpdate {
    /** A challenge to show; nothing was shown. */
    data class Show(val challenge: Challenge) : PollUpdate

    /** The shown challenge is no longer pending: answered, expired, or its sign-in gave up. */
    data class Withdrawn(val id: String) : PollUpdate

    data object NoChange : PollUpdate
    data class Failed(val message: String) : PollUpdate
}

data class Poll(val update: PollUpdate, val nextSeconds: Long)

sealed interface AnswerResult {
    data class Answered(val answer: Answer) : AnswerResult

    /** 409: already answered or expired. */
    data object NotPending : AnswerResult

    /** 404: unknown to the server, or another user's. */
    data object NotFound : AnswerResult
    data class Rejected(val status: Int, val error: String) : AnswerResult
    data class NetworkError(val message: String) : AnswerResult
}

sealed interface ServerRemoval {
    data object Removed : ServerRemoval

    /** 401: the server holds no enrollment this key signs for, or the clock is off. */
    data object AlreadyGone : ServerRemoval

    /** The server could not be reached, failed, or refused the request; it may still hold the enrollment. */
    data class NotTold(val reason: String) : ServerRemoval
}

sealed interface FidResult {
    data object Sent : FidResult
    data class Rejected(val status: Int, val error: String) : FidResult
    data class NetworkError(val message: String) : FidResult
}

/**
 * The enrolled device's requests: polls, fetches, FID updates and removal with the device_key;
 * answers only with a signer the caller got from a biometric prompt over the
 * approval_key. Calls block.
 */
class Approvals(
    private val account: Account,
    private val api: DeviceApi,
    private val deviceSigner: Signer,
    private val unixSeconds: () -> Long,
) {
    /** One poll, given the ID of the challenge on screen, if any. */
    fun poll(shownId: String?): Poll {
        val response = post(PENDING_PATH, JSONObject(), deviceSigner)
        val pending = (response as? DeviceResponse.Ok)?.takeIf { it.status == HttpURLConnection.HTTP_OK }?.let(::parsePending)
            ?: return Poll(PollUpdate.Failed(describe(response)), DEFAULT_POLL_SECONDS)
        val (ids, next) = pending
        val update = when {
            shownId != null -> if (shownId in ids) PollUpdate.NoChange else PollUpdate.Withdrawn(shownId)
            ids.isEmpty() -> PollUpdate.NoChange
            else -> fetch(ids.first())
        }
        return Poll(update, next)
    }

    /** Challenge [id], as a push names it: its details come from the server, never the push. */
    fun open(id: String): PollUpdate = fetch(id)

    /** Tells the server to push to [fid] from now on. */
    fun sendFid(fid: String): FidResult = when (val response = post(FID_PATH, JSONObject().put("fid", fid), deviceSigner)) {
        is DeviceResponse.NetworkError -> FidResult.NetworkError(response.message)
        is DeviceResponse.Ok ->
            if (response.status == HttpURLConnection.HTTP_OK) FidResult.Sent else FidResult.Rejected(response.status, errorCode(response.body))
    }

    /** Asks the server to delete this enrollment. */
    fun remove(): ServerRemoval {
        val response = try {
            post(REMOVE_PATH, JSONObject(), deviceSigner)
        } catch (e: Exception) {
            return ServerRemoval.NotTold(e.message ?: e.javaClass.simpleName)
        }
        return when {
            response is DeviceResponse.Ok && response.status == HttpURLConnection.HTTP_OK -> ServerRemoval.Removed
            response is DeviceResponse.Ok && response.status == HttpURLConnection.HTTP_UNAUTHORIZED -> ServerRemoval.AlreadyGone
            else -> ServerRemoval.NotTold(describe(response))
        }
    }

    private fun fetch(id: String): PollUpdate {
        val response = post(CHALLENGE_PATH, JSONObject().put("challenge_id", id), deviceSigner)
        if (response is DeviceResponse.Ok && response.status == HttpURLConnection.HTTP_NOT_FOUND) return PollUpdate.NoChange
        val challenge = (response as? DeviceResponse.Ok)?.takeIf { it.status == HttpURLConnection.HTTP_OK }?.let(::parseChallenge)
        return challenge?.let { PollUpdate.Show(it) } ?: PollUpdate.Failed(describe(response))
    }

    /** Answers challenge [id]; [approvalSigner] must sign with the approval_key. */
    fun answer(id: String, answer: Answer, approvalSigner: Signer): AnswerResult {
        val body = JSONObject().put("challenge_id", id).put("decision", answer.decision)
        answer.reason?.let { body.put("reason", it) }
        return when (val response = post(ANSWER_PATH, body, approvalSigner)) {
            is DeviceResponse.NetworkError -> AnswerResult.NetworkError(response.message)
            is DeviceResponse.Ok -> when (response.status) {
                HttpURLConnection.HTTP_OK -> AnswerResult.Answered(answer)
                HttpURLConnection.HTTP_CONFLICT -> AnswerResult.NotPending
                HttpURLConnection.HTTP_NOT_FOUND -> AnswerResult.NotFound
                else -> AnswerResult.Rejected(response.status, errorCode(response.body))
            }
        }
    }

    private fun post(path: String, json: JSONObject, signer: Signer): DeviceResponse {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        return api.post(path, body, authorization(signer, account.userId, unixSeconds(), path, body))
    }
}

private fun parsePending(r: DeviceResponse.Ok): Pair<List<String>, Long>? = try {
    val json = JSONObject(r.body)
    val ids = json.getJSONArray("challenge_ids").let { a -> List(a.length()) { a.getString(it) } }
    ids to json.optLong("poll_interval", DEFAULT_POLL_SECONDS).coerceIn(1, 60)
} catch (_: JSONException) {
    null
}

private fun parseChallenge(r: DeviceResponse.Ok): Challenge? = try {
    val json = JSONObject(r.body)
    Challenge(
        id = json.getString("challenge_id"),
        username = json.optString("username"),
        ipaddr = json.optString("ipaddr"),
        pushinfo = parsePushinfo(json.optString("pushinfo")),
        type = json.optString("type"),
        created = json.optLong("created"),
        expires = json.optLong("expires"),
    )
} catch (_: JSONException) {
    null
}

/** Authentik sends pushinfo URL-encoded: `Domain=…&Application=…`. */
fun parsePushinfo(pushinfo: String): List<Pair<String, String>> = try {
    pushinfo.split('&').filter { it.isNotEmpty() }.map {
        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
    }
} catch (_: IllegalArgumentException) {
    listOf("" to pushinfo)
}

private fun errorCode(body: String): String = try {
    JSONObject(body).optString("error")
} catch (_: JSONException) {
    ""
}

private fun describe(response: DeviceResponse): String = when (response) {
    is DeviceResponse.NetworkError -> response.message
    is DeviceResponse.Ok -> "HTTP ${response.status} ${errorCode(response.body)}".trim()
}
