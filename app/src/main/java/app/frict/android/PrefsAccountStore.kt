package app.frict.android

import android.annotation.SuppressLint
import android.content.Context

private const val PREFS = "accounts"
private const val KEY_ACCOUNTS = "accounts"

private const val LEGACY_PREFS = "account"
private const val LEGACY_HOST = "host"
private const val LEGACY_USER_ID = "user_id"
private const val LEGACY_UNLOCK_MODE = "unlock_mode"

/** The accounts as JSON in shared preferences; a single account saved by an older version is moved in on first use. */
// The KTX edit {} discards commit()'s result, which add and remove report.
@SuppressLint("UseKtx")
class PrefsAccountStore(context: Context) : AccountStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    override fun load(): List<Account> {
        val saved = prefs.getString(KEY_ACCOUNTS, null)?.let(::accountsFromJson).orEmpty()
        if (!legacy.contains(LEGACY_HOST)) return saved
        val migrated = withLegacyAccount(
            saved,
            legacy.getString(LEGACY_HOST, null),
            legacy.getString(LEGACY_USER_ID, null),
            legacy.getString(LEGACY_UNLOCK_MODE, null),
        )
        if (save(migrated)) legacy.edit().clear().apply()
        return migrated
    }

    override fun add(account: Account): Boolean = save(load() + account)

    override fun remove(account: Account): Boolean = save(load() - account)

    private fun save(accounts: List<Account>) = prefs.edit().putString(KEY_ACCOUNTS, accountsToJson(accounts)).commit()
}
