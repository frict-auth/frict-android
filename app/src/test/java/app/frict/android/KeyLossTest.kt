package app.frict.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private const val USER_ID = "DUABCDEFGHIJKLMNOPQRSTUVWXYZ"

class KeyLossTest {
    private val server = FakeFrictServer(USER_ID, "unused")
    private val account = testAccount(USER_ID)
    private val noDeviceKey = Approvals(account, DeviceApi(server.url), { throw KeyLostException(LostKey.DEVICE_KEY) }, { 1762345678L })

    @After
    fun close() = server.close()

    @Test
    fun pollWithoutTheDeviceKeySaysItIsLost() {
        assertEquals(Poll(PollUpdate.KeyLost(LostKey.DEVICE_KEY), DEFAULT_POLL_SECONDS), noDeviceKey.poll(shownId = null))
        assertEquals(emptyList<RecordedRequest>(), server.requests.toList())
    }

    @Test
    fun openingAPushWithoutTheDeviceKeySaysItIsLost() {
        assertEquals(PollUpdate.KeyLost(LostKey.DEVICE_KEY), noDeviceKey.open("AAAAAAAAAAAAAAAAAAAAAA"))
    }

    @Test
    fun removalWithoutTheDeviceKeyKeepsEverythingUntilTheUserRemovesAnyway() {
        val store = MemoryAccountStore(account)
        val deleted = mutableListOf<String>()
        assertEquals(
            RemoveResult.ServerNotTold("the device_key is gone"),
            removeAccount(account, noDeviceKey::remove, store, { deleted += "keys" }, { deleted += "installation" }),
        )
        assertEquals(listOf(account), store.accounts)
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun theExplanationNamesWhatInvalidatesTheKey() {
        val fingerprintOnly = LostKey.APPROVAL_KEY.explanation(UnlockMode.FINGERPRINT_ONLY)
        val screenLock = LostKey.APPROVAL_KEY.explanation(UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
        assertEquals(R.string.approval_key_lost_fingerprint_only, fingerprintOnly)
        assertEquals(R.string.approval_key_lost_fingerprint_or_screen_lock, screenLock)
        for (mode in UnlockMode.entries) assertEquals(R.string.device_key_lost, LostKey.DEVICE_KEY.explanation(mode))
        assertNotEquals(fingerprintOnly, screenLock)
    }
}
