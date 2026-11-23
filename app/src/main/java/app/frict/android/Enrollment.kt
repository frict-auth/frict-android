package app.frict.android

import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

const val ENROLL_PATH = "/device/v1/enroll"

/**
 * Enrolls a device that has no Firebase Installation ID, such as a build without a
 * Firebase project. The server requires a non-empty fid and clears one that FCM rejects,
 * so such a device polls until it sends a real one.
 */
const val NO_FID = "no-fid"

private val USER_ID = Regex("DU[A-Z2-7]{26}")
private val HOST = Regex("[A-Za-z0-9.-]+(:[0-9]{1,5})?")

/** An activation link's fields: `frict://enroll?code=…&host=…&user_id=…`, as the QR code encodes it. */
data class Activation(val host: String, val userId: String, val code: String)

fun parseActivation(link: String): Activation? {
    val uri = try {
        URI(link.trim())
    } catch (_: URISyntaxException) {
        return null
    }
    if (uri.scheme != "frict" || uri.host != "enroll") return null
    val query = try {
        uri.rawQuery.orEmpty().split('&').associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
    } catch (_: IllegalArgumentException) {
        return null
    }
    val host = query["host"]?.takeIf { HOST.matches(it) } ?: return null
    val userId = query["user_id"]?.takeIf { USER_ID.matches(it) } ?: return null
    val code = query["code"]?.takeIf { it.isNotEmpty() } ?: return null
    return Activation(host, userId, code)
}

private fun decode(s: String) = URLDecoder.decode(s, Charsets.UTF_8)

fun enrollBody(code: String, keys: EnrollmentKeys, fid: String): ByteArray = JSONObject()
    .put("activation_code", code)
    .put("device_key", b64url(keys.deviceKey))
    .put("approval_key", b64url(keys.approvalKey))
    .put(
        "attestation",
        JSONObject()
            .put("device_key", JSONArray(keys.deviceKeyAttestation.map(::b64url)))
            .put("approval_key", JSONArray(keys.approvalKeyAttestation.map(::b64url))),
    )
    .put("fid", fid)
    .toString()
    .toByteArray(Charsets.UTF_8)

private fun b64url(der: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(der)

/** The enrolled user, the API host that enrolled it, and what unlocks its approval_key. */
data class Account(val host: String, val userId: String, val unlockMode: UnlockMode)

interface AccountStore {
    fun load(): Account?

    /** Returns false if the account could not be saved. */
    fun save(account: Account): Boolean

    /** Returns false if the account could not be removed. */
    fun clear(): Boolean
}

/**
 * Removes this phone's enrollment from this phone only: the saved account, then both keys.
 * Returns false, keeping the keys, if the account could not be removed.
 */
fun unenroll(store: AccountStore, deleteKeys: () -> Unit): Boolean {
    if (!store.clear()) return false
    deleteKeys()
    return true
}

sealed interface RemoveResult {
    data object Removed : RemoveResult

    /** Gone from the server, but the account could not be removed here; the keys are kept. */
    data object NotRemovedHere : RemoveResult

    /** Nothing was removed: the user decides whether to [unenroll] anyway. */
    data class ServerNotTold(val reason: String) : RemoveResult
}

/** Removes the enrollment from the server, then, once the server no longer holds it, from this phone. */
fun removePhone(tellServer: () -> ServerRemoval, store: AccountStore, deleteKeys: () -> Unit): RemoveResult =
    when (val told = tellServer()) {
        is ServerRemoval.NotTold -> RemoveResult.ServerNotTold(told.reason)
        ServerRemoval.Removed, ServerRemoval.AlreadyGone ->
            if (unenroll(store, deleteKeys)) RemoveResult.Removed else RemoveResult.NotRemovedHere
    }

sealed interface EnrollResult {
    data class Enrolled(val account: Account) : EnrollResult
    data object AlreadyEnrolled : EnrollResult
    data object NotAnActivationLink : EnrollResult
    data class KeysUnavailable(val message: String) : EnrollResult
    data class Rejected(val status: Int) : EnrollResult
    data class NetworkError(val message: String) : EnrollResult
    data object NotSaved : EnrollResult
}

/**
 * Claims an activation for this device: new keys, a signed enroll request, and the
 * activation's user saved once the server accepts. Blocks; not thread-safe, so run it on
 * one background thread.
 */
class Enroller(
    private val keys: KeyMaker,
    private val store: AccountStore,
    private val api: (host: String) -> DeviceApi,
    private val fid: () -> String,
    private val unixSeconds: () -> Long,
) {
    fun enroll(link: String, mode: UnlockMode): EnrollResult {
        if (store.load() != null) return EnrollResult.AlreadyEnrolled
        val activation = parseActivation(link) ?: return EnrollResult.NotAnActivationLink
        val newKeys = try {
            keys.create(activation.code.toByteArray(Charsets.UTF_8), mode)
        } catch (e: Exception) {
            return EnrollResult.KeysUnavailable(e.message ?: e.javaClass.simpleName)
        }
        val body = enrollBody(activation.code, newKeys, fid())
        val auth = authorization(newKeys.deviceSigner, activation.userId, unixSeconds(), ENROLL_PATH, body)
        return when (val response = api(activation.host).post(ENROLL_PATH, body, auth)) {
            is DeviceResponse.NetworkError -> EnrollResult.NetworkError(response.message)
            is DeviceResponse.Ok -> when (response.status) {
                HttpURLConnection.HTTP_OK -> Account(activation.host, activation.userId, mode).let {
                    if (store.save(it)) EnrollResult.Enrolled(it) else EnrollResult.NotSaved
                }
                else -> EnrollResult.Rejected(response.status)
            }
        }
    }
}

/**
 * Why this device can't enroll with [mode], as a string resource, or null if it can.
 * Every approval_key needs a secure screen lock.
 */
fun enrollBlocker(mode: UnlockMode, screenLock: Boolean, strongBiometric: Boolean): Int? = when {
    !screenLock -> R.string.needs_screen_lock
    mode == UnlockMode.FINGERPRINT_ONLY && !strongBiometric -> R.string.needs_biometric
    else -> null
}
