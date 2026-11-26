package app.frict.android

import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
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

/** Where one account's two keys live in Keystore. */
data class KeyAliases(val device: String, val approval: String) {
    companion object {
        /** The aliases of the one account app versions before several accounts had. */
        val LEGACY = KeyAliases("device_key", "approval_key")

        fun random(): KeyAliases = UUID.randomUUID().toString().let { KeyAliases("device_key.$it", "approval_key.$it") }
    }
}

/**
 * One enrolled user: the API host that enrolled it, what unlocks its approval_key, where its
 * keys are, and the label it is shown by, which stays on this phone. Accounts enrolled before
 * labels have none.
 */
data class Account(val host: String, val userId: String, val unlockMode: UnlockMode, val keys: KeyAliases, val label: String? = null) {
    fun isFor(host: String, userId: String) = this.host.equals(host, ignoreCase = true) && this.userId == userId
}

/** The accounts on this phone, in the order they were enrolled. */
interface AccountStore {
    fun load(): List<Account>

    /** Returns false if the account could not be saved. */
    fun add(account: Account): Boolean

    /** Returns false if the account could not be removed. */
    fun remove(account: Account): Boolean

    /** Saves [new] in place of [old]; returns false if it could not be saved. */
    fun replace(old: Account, new: Account): Boolean
}

/**
 * Removes [account] from this phone only: the saved account, then its keys, then, once no
 * account is left, this app's Firebase installation. Returns false, keeping the keys, if
 * the account could not be removed.
 */
fun unenroll(account: Account, store: AccountStore, deleteKeys: (KeyAliases) -> Unit, deleteInstallation: () -> Unit): Boolean {
    if (!store.remove(account)) return false
    deleteKeys(account.keys)
    if (store.load().isEmpty()) deleteInstallation()
    return true
}

sealed interface RemoveResult {
    data object Removed : RemoveResult

    /** Gone from the server, but the account could not be removed here; the keys are kept. */
    data object NotRemovedHere : RemoveResult

    /** Nothing was removed: the user decides whether to [unenroll] anyway. */
    data class ServerNotTold(val reason: String) : RemoveResult
}

/** Removes [account] from its server, then, once the server no longer holds it, from this phone. */
fun removeAccount(
    account: Account,
    tellServer: () -> ServerRemoval,
    store: AccountStore,
    deleteKeys: (KeyAliases) -> Unit,
    deleteInstallation: () -> Unit,
): RemoveResult = when (val told = tellServer()) {
    is ServerRemoval.NotTold -> RemoveResult.ServerNotTold(told.reason)
    UpdateRequired -> UpdateRequired
    ServerRemoval.Removed, ServerRemoval.AlreadyGone ->
        if (unenroll(account, store, deleteKeys, deleteInstallation)) RemoveResult.Removed else RemoveResult.NotRemovedHere
}

sealed interface EnrollResult {
    data class Enrolled(val account: Account) : EnrollResult

    /** This phone already holds an account for the link's user on the link's host. */
    data object AlreadyEnrolled : EnrollResult
    data object NotAnActivationLink : EnrollResult
    data class KeysUnavailable(val message: String) : EnrollResult
    data class Rejected(val status: Int) : EnrollResult
    data class NetworkError(val message: String) : EnrollResult
    data object NotSaved : EnrollResult
}

/**
 * Claims an activation for this device as a new account: new keys under new aliases, a
 * signed enroll request, and the account saved once the server accepts, with its
 * [defaultLabel]. The keys are
 * deleted again if it is not saved. Blocks; not thread-safe, so run it on one background
 * thread.
 */
class Enroller(
    private val keys: KeyMaker,
    private val deleteKeys: (KeyAliases) -> Unit,
    private val store: AccountStore,
    private val api: (host: String) -> DeviceApi,
    private val fid: () -> String,
    private val unixSeconds: () -> Long,
    private val newAliases: () -> KeyAliases = KeyAliases::random,
) {
    fun enroll(link: String, mode: UnlockMode): EnrollResult {
        val activation = parseActivation(link) ?: return EnrollResult.NotAnActivationLink
        if (store.load().any { it.isFor(activation.host, activation.userId) }) return EnrollResult.AlreadyEnrolled
        val aliases = newAliases()
        val result = enroll(activation, aliases, mode)
        if (result !is EnrollResult.Enrolled) deleteKeys(aliases)
        return result
    }

    private fun enroll(activation: Activation, aliases: KeyAliases, mode: UnlockMode): EnrollResult {
        val newKeys = try {
            keys.create(aliases, activation.code.toByteArray(Charsets.UTF_8), mode)
        } catch (e: Exception) {
            return EnrollResult.KeysUnavailable(e.message ?: e.javaClass.simpleName)
        }
        val body = enrollBody(activation.code, newKeys, fid())
        val auth = authorization(newKeys.deviceSigner, activation.userId, unixSeconds(), ENROLL_PATH, body)
        return when (val response = api(activation.host).post(ENROLL_PATH, body, auth)) {
            is DeviceResponse.NetworkError -> EnrollResult.NetworkError(response.message)
            UpdateRequired -> UpdateRequired
            is DeviceResponse.Ok -> when (response.status) {
                HttpURLConnection.HTTP_OK -> Account(activation.host, activation.userId, mode, aliases, defaultLabel(username(response.body), activation.host, activation.userId)).let {
                    if (store.add(it)) EnrollResult.Enrolled(it) else EnrollResult.NotSaved
                }
                else -> EnrollResult.Rejected(response.status)
            }
        }
    }
}

/** The username in an enroll reply, or empty if it has none. */
private fun username(reply: String): String = try {
    JSONObject(reply).optString("username")
} catch (_: JSONException) {
    ""
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
