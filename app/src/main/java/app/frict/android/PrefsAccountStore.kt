package app.frict.android

import android.annotation.SuppressLint
import android.content.Context

private const val PREFS = "account"
private const val KEY_HOST = "host"
private const val KEY_USER_ID = "user_id"
private const val KEY_UNLOCK_MODE = "unlock_mode"

class PrefsAccountStore(context: Context) : AccountStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): Account? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        // Keys made before the choice existed are fingerprint-only.
        val mode = prefs.getString(KEY_UNLOCK_MODE, null)?.let(UnlockMode::valueOf) ?: UnlockMode.FINGERPRINT_ONLY
        return Account(host, userId, mode)
    }

    // The KTX edit {} discards commit()'s result, which save reports.
    @SuppressLint("UseKtx")
    override fun save(account: Account): Boolean =
        prefs.edit()
            .putString(KEY_HOST, account.host)
            .putString(KEY_USER_ID, account.userId)
            .putString(KEY_UNLOCK_MODE, account.unlockMode.name)
            .commit()
}
