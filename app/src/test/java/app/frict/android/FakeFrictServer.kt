package app.frict.android

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

class RecordedRequest(val path: String, val authorization: String, val body: ByteArray) {
    val json get() = JSONObject(String(body, Charsets.UTF_8))
}

enum class ChallengeState { PENDING, APPROVED, DENIED_CANCEL, DENIED_NOT_ME, EXPIRED }

/**
 * Stands in for frict-server's device API (docs/device-protocol.md): enroll accepts one
 * activation [code] for [userId], signed by the body's device_key; pending and challenge
 * want the enrolled device_key, answer the enrolled approval_key. Challenges are added
 * with [addChallenge] and their state is in [challenges]. fid, signed with the device_key,
 * sets [fid]; remove, signed with the device_key, forgets both keys. [failWith] answers
 * every request with that status instead.
 */
class FakeFrictServer(private val userId: String, private val code: String) : AutoCloseable {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    val challenges = linkedMapOf<String, ChallengeState>()
    var pollInterval = 5

    @Volatile
    var failWith: Int? = null

    @Volatile
    var fid: String? = null
    private val details = mutableMapOf<String, JSONObject>()
    private var deviceKey: PublicKey? = null
    private var approvalKey: PublicKey? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.use {
                val request = RecordedRequest(
                    it.requestURI.path,
                    it.requestHeaders.getFirst("Authorization").orEmpty(),
                    it.requestBody.readBytes(),
                )
                requests += request
                val (status, reply) = handle(request)
                val bytes = reply.toByteArray()
                it.sendResponseHeaders(status, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        start()
    }

    val url = "http://127.0.0.1:${server.address.port}"

    /** Enrolled as if by a successful enroll request. */
    @Synchronized
    fun enrolled(device: PublicKey, approval: PublicKey) {
        deviceKey = device
        approvalKey = approval
    }

    @Synchronized
    fun addChallenge(id: String, username: String = "geoffrey", state: ChallengeState = ChallengeState.PENDING) {
        challenges[id] = state
        details[id] = JSONObject()
            .put("challenge_id", id)
            .put("username", username)
            .put("ipaddr", "203.0.113.7")
            .put("pushinfo", "Domain=auth.example&Application=Frict+test")
            .put("type", "authentik Login request")
            .put("created", 1762345678)
            .put("expires", 1762345738)
    }

    @Synchronized
    private fun handle(r: RecordedRequest): Pair<Int, String> = failWith?.let { it to """{"error":"internal"}""" } ?: when (r.path) {
        ENROLL_PATH -> if (acceptsEnroll(r)) 200 to "{}" else unauthorized
        PENDING_PATH -> when {
            !verifies(r, deviceKey) -> unauthorized
            r.json.length() != 0 -> badRequest
            else -> {
                val ids = challenges.filterValues { it == ChallengeState.PENDING }.keys
                200 to JSONObject().put("challenge_ids", JSONArray(ids)).put("poll_interval", pollInterval).toString()
            }
        }
        CHALLENGE_PATH -> when {
            !verifies(r, deviceKey) -> unauthorized
            r.json.fields() != setOf("challenge_id") -> badRequest
            challenges[r.json.getString("challenge_id")] != ChallengeState.PENDING -> 404 to """{"error":"not_found"}"""
            else -> 200 to details.getValue(r.json.getString("challenge_id")).toString()
        }
        ANSWER_PATH -> if (!verifies(r, approvalKey)) unauthorized else answer(r.json)
        FID_PATH -> when {
            !verifies(r, deviceKey) -> unauthorized
            r.json.fields() != setOf("fid") || r.json.optString("fid").isEmpty() -> badRequest
            else -> {
                fid = r.json.getString("fid")
                200 to "{}"
            }
        }
        REMOVE_PATH -> when {
            !verifies(r, deviceKey) -> unauthorized
            r.json.length() != 0 -> badRequest
            else -> {
                deviceKey = null
                approvalKey = null
                200 to "{}"
            }
        }
        else -> 404 to """{"error":"not_found"}"""
    }

    private fun answer(body: JSONObject): Pair<Int, String> {
        val outcome = when {
            body.fields() == setOf("challenge_id", "decision") && body.getString("decision") == "approve" -> ChallengeState.APPROVED
            body.fields() != setOf("challenge_id", "decision", "reason") || body.getString("decision") != "deny" -> null
            body.getString("reason") == "cancel" -> ChallengeState.DENIED_CANCEL
            body.getString("reason") == "not_me" -> ChallengeState.DENIED_NOT_ME
            else -> null
        } ?: return badRequest
        val id = body.getString("challenge_id")
        return when (challenges[id]) {
            null -> 404 to """{"error":"not_found"}"""
            ChallengeState.PENDING -> {
                challenges[id] = outcome
                200 to "{}"
            }
            else -> 409 to """{"error":"not_pending"}"""
        }
    }

    private fun acceptsEnroll(r: RecordedRequest): Boolean {
        if (deviceKey != null) return false
        val body = r.json
        if (body.fields() != setOf("activation_code", "device_key", "approval_key", "attestation", "fid")) return false
        if (body.getString("activation_code") != code || body.getString("fid").isEmpty()) return false
        val device = p256(body.getString("device_key"))
        if (!verifies(r, device)) return false
        enrolled(device, p256(body.getString("approval_key")))
        fid = body.getString("fid")
        return true
    }

    private fun verifies(r: RecordedRequest, key: PublicKey?): Boolean {
        if (key == null || !r.authorization.startsWith("Frict-P256 ")) return false
        val (claimedUser, unix, signature) = r.authorization.removePrefix("Frict-P256 ").split(':').takeIf { it.size == 3 } ?: return false
        if (claimedUser != userId) return false
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(signedBytes(claimedUser, unix.toLong(), r.path, r.body))
            verify(b64url(signature))
        }
    }

    override fun close() = server.stop(0)
}

private val unauthorized = 401 to """{"error":"unauthorized"}"""
private val badRequest = 400 to """{"error":"bad_request"}"""

private fun p256(spki: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64url(spki)))

fun b64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

fun JSONObject.fields(): Set<String> = keys().asSequence().toSet()
