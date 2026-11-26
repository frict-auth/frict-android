package app.frict.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnenrollTest {
    private val own = testAccount("DUABCDEFGHIJKLMNOPQRSTUVWXYZ")
    private val admin = testAccount("DU234567234567234567234567AB", host = "admin.frict.test", mode = UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
    private val store = MemoryAccountStore(own, admin)
    private val events = mutableListOf<String>()
    private val deleteKeys = { keys: KeyAliases -> events += "deleted ${keys.device} ${keys.approval}" }
    private val deleteInstallation = { events += "installation deleted" }

    private fun deleted(a: Account) = "deleted ${a.keys.device} ${a.keys.approval}"

    private fun server(answer: ServerRemoval): () -> ServerRemoval = {
        events += "server told, ${store.accounts.size} accounts here"
        answer
    }

    private fun remove(account: Account, answer: ServerRemoval) =
        removeAccount(account, server(answer), store, deleteKeys, deleteInstallation)

    @Test
    fun removesOnlyThatAccountAndItsKeys() {
        assertTrue(unenroll(own, store, deleteKeys, deleteInstallation))
        assertEquals(listOf(admin), store.accounts)
        assertEquals(listOf(deleted(own)), events)
    }

    @Test
    fun removingTheLastAccountDeletesTheFirebaseInstallationAfterItsKeys() {
        assertTrue(unenroll(own, store, deleteKeys, deleteInstallation))
        assertTrue(unenroll(admin, store, deleteKeys, deleteInstallation))
        assertEquals(emptyList<Account>(), store.accounts)
        assertEquals(listOf(deleted(own), deleted(admin), "installation deleted"), events)
    }

    @Test
    fun keepsTheKeysWhenTheAccountCannotBeRemoved() {
        store.writesSucceed = false
        assertFalse(unenroll(own, store, deleteKeys, deleteInstallation))
        assertEquals(listOf(own, admin), store.accounts)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun removalTellsTheServerBeforeRemovingAnythingHere() {
        assertEquals(RemoveResult.Removed, remove(admin, ServerRemoval.Removed))
        assertEquals(listOf(own), store.accounts)
        assertEquals(listOf("server told, 2 accounts here", deleted(admin)), events)
    }

    @Test
    fun theLastAccountRemovedFromItsServerDeletesTheFirebaseInstallation() {
        store.accounts -= own
        assertEquals(RemoveResult.Removed, remove(admin, ServerRemoval.Removed))
        assertEquals(listOf("server told, 1 accounts here", deleted(admin), "installation deleted"), events)
    }

    @Test
    fun anEnrollmentAlreadyGoneFromTheServerIsRemovedHere() {
        assertEquals(RemoveResult.Removed, remove(own, ServerRemoval.AlreadyGone))
        assertEquals(listOf(admin), store.accounts)
        assertEquals(listOf("server told, 2 accounts here", deleted(own)), events)
    }

    @Test
    fun nothingIsRemovedHereWhenTheServerWasNotTold() {
        store.accounts -= own
        assertEquals(RemoveResult.ServerNotTold("timeout"), remove(admin, ServerRemoval.NotTold("timeout")))
        assertEquals(listOf(admin), store.accounts)
        assertEquals(listOf("server told, 1 accounts here"), events)
    }

    @Test
    fun keysAreKeptWhenTheAccountCannotBeRemovedAfterTheServerRemovedIt() {
        store.writesSucceed = false
        assertEquals(RemoveResult.NotRemovedHere, remove(own, ServerRemoval.Removed))
        assertEquals(listOf(own, admin), store.accounts)
        assertEquals(listOf("server told, 2 accounts here"), events)
    }

    @Test
    fun removalAgainstTheServerEndToEnd() {
        FakeFrictServer(own.userId, "unused").use { server ->
            val keys = SoftwareKeyMaker()
            val created = keys.create(own.keys, ByteArray(0), UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
            server.enrolled(keys.device.public, keys.approval.public)
            val approvals = Approvals(own, DeviceApi(server.url), created.deviceSigner, { 1762345678L })
            assertEquals(RemoveResult.Removed, removeAccount(own, approvals::remove, store, deleteKeys, deleteInstallation))
            assertEquals(listOf(admin), store.accounts)
            assertEquals(listOf(REMOVE_PATH), server.requests.map { it.path })
        }
    }
}
