package app.frict.android

import android.content.Context

private const val PREFS = "account"
private const val KEY_HOST = "host"
private const val KEY_USER_ID = "user_id"

class PrefsAccountStore(context: Context) : AccountStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): Account? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        return Account(host, userId)
    }

    override fun save(account: Account): Boolean =
        prefs.edit().putString(KEY_HOST, account.host).putString(KEY_USER_ID, account.userId).commit()
}
