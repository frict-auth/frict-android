package app.frict.android

import org.json.JSONArray
import org.json.JSONObject

/** [accounts] as PrefsAccountStore saves them. */
fun accountsToJson(accounts: List<Account>): String = JSONArray(
    accounts.map {
        JSONObject()
            .put("host", it.host)
            .put("user_id", it.userId)
            .put("unlock_mode", it.unlockMode.name)
            .put("device_key_alias", it.keys.device)
            .put("approval_key_alias", it.keys.approval)
    },
).toString()

fun accountsFromJson(json: String): List<Account> = JSONArray(json).let { a ->
    List(a.length()) { i ->
        val o = a.getJSONObject(i)
        Account(
            host = o.getString("host"),
            userId = o.getString("user_id"),
            unlockMode = UnlockMode.valueOf(o.getString("unlock_mode")),
            keys = KeyAliases(o.getString("device_key_alias"), o.getString("approval_key_alias")),
        )
    }
}

/**
 * [accounts] with the one account of app versions before several accounts, saved as
 * [host], [userId] and [unlockMode], first. Its keys stay at [KeyAliases.LEGACY]; one
 * saved without an unlock mode is fingerprint-only, as keys made before the choice were.
 */
fun withLegacyAccount(accounts: List<Account>, host: String?, userId: String?, unlockMode: String?): List<Account> {
    if (host == null || userId == null || accounts.any { it.isFor(host, userId) }) return accounts
    val mode = unlockMode?.let(UnlockMode::valueOf) ?: UnlockMode.FINGERPRINT_ONLY
    return listOf(Account(host, userId, mode, KeyAliases.LEGACY)) + accounts
}
