package app.frict.android

/** An account of [userId] on [host] whose keys are named after it. */
fun testAccount(userId: String, host: String = "api.frict.test", mode: UnlockMode = UnlockMode.FINGERPRINT_ONLY) =
    Account(host, userId, mode, KeyAliases("device_key.$userId", "approval_key.$userId"))

class MemoryAccountStore(vararg initial: Account) : AccountStore {
    val accounts = initial.toMutableList()
    var writesSucceed = true

    override fun load() = accounts.toList()

    override fun add(account: Account) = writesSucceed.also { if (it) accounts += account }

    override fun remove(account: Account) = writesSucceed.also { if (it) accounts -= account }

    override fun replace(old: Account, new: Account) = writesSucceed.also { if (it) accounts.replaceAll { a -> if (a == old) new else a } }
}
