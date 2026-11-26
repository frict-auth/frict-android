package app.frict.android

import org.junit.Assert.assertEquals
import org.junit.Test

private const val OWN = "DUABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val ADMIN = "DU234567234567234567234567AB"

class AccountsJsonTest {
    private val admin = testAccount(ADMIN, host = "admin.frict.test", mode = UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)

    @Test
    fun accountsRoundTripInOrder() {
        val accounts = listOf(testAccount(OWN), admin, Account("h:8443", OWN, UnlockMode.FINGERPRINT_ONLY, KeyAliases.LEGACY))
        assertEquals(accounts, accountsFromJson(accountsToJson(accounts)))
        assertEquals(emptyList<Account>(), accountsFromJson(accountsToJson(emptyList())))
    }

    @Test
    fun theOldSingleAccountBecomesTheFirstWithItsKeysWhereTheyAre() {
        val migrated = withLegacyAccount(emptyList(), "api.frict.test", OWN, "FINGERPRINT_OR_SCREEN_LOCK")
        assertEquals(listOf(Account("api.frict.test", OWN, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK, KeyAliases("device_key", "approval_key"))), migrated)
    }

    @Test
    fun anOldAccountSavedWithoutAnUnlockModeIsFingerprintOnly() {
        assertEquals(UnlockMode.FINGERPRINT_ONLY, withLegacyAccount(emptyList(), "api.frict.test", OWN, null).single().unlockMode)
    }

    @Test
    fun migrationGoesBeforeAccountsAlreadySavedAndOnlyOnce() {
        val once = withLegacyAccount(listOf(admin), "api.frict.test", OWN, "FINGERPRINT_ONLY")
        assertEquals(listOf(Account("api.frict.test", OWN, UnlockMode.FINGERPRINT_ONLY, KeyAliases.LEGACY), admin), once)
        assertEquals(once, withLegacyAccount(once, "api.frict.test", OWN, "FINGERPRINT_ONLY"))
    }

    @Test
    fun withoutAnOldAccountNothingChanges() {
        assertEquals(listOf(admin), withLegacyAccount(listOf(admin), null, null, null))
        assertEquals(listOf(admin), withLegacyAccount(listOf(admin), "api.frict.test", null, null))
    }
}
