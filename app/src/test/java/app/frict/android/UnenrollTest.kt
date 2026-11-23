package app.frict.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnenrollTest {
    private val enrolled = Account("api.frict.test", "DUABCDEFGHIJKLMNOPQRSTUVWXYZ", UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
    private val store = MemoryAccountStore(enrolled)
    private val events = mutableListOf<String>()
    private val deleteKeys = { events += "keys deleted" }

    private fun server(answer: ServerRemoval): () -> ServerRemoval = {
        events += "server told, account ${if (store.account == null) "gone" else "kept"}"
        answer
    }

    @Test
    fun removesTheAccountAndTheKeys() {
        assertTrue(unenroll(store, deleteKeys))
        assertNull(store.account)
        assertEquals(listOf("keys deleted"), events)
    }

    @Test
    fun keepsTheKeysWhenTheAccountCannotBeRemoved() {
        store.clearSucceeds = false
        assertFalse(unenroll(store, deleteKeys))
        assertEquals(enrolled, store.account)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun removalTellsTheServerBeforeRemovingAnythingHere() {
        assertEquals(RemoveResult.Removed, removePhone(server(ServerRemoval.Removed), store, deleteKeys))
        assertNull(store.account)
        assertEquals(listOf("server told, account kept", "keys deleted"), events)
    }

    @Test
    fun anEnrollmentAlreadyGoneFromTheServerIsRemovedHere() {
        assertEquals(RemoveResult.Removed, removePhone(server(ServerRemoval.AlreadyGone), store, deleteKeys))
        assertNull(store.account)
        assertEquals(listOf("server told, account kept", "keys deleted"), events)
    }

    @Test
    fun nothingIsRemovedHereWhenTheServerWasNotTold() {
        assertEquals(RemoveResult.ServerNotTold("timeout"), removePhone(server(ServerRemoval.NotTold("timeout")), store, deleteKeys))
        assertEquals(enrolled, store.account)
        assertEquals(listOf("server told, account kept"), events)
    }

    @Test
    fun keysAreKeptWhenTheAccountCannotBeRemovedAfterTheServerRemovedIt() {
        store.clearSucceeds = false
        assertEquals(RemoveResult.NotRemovedHere, removePhone(server(ServerRemoval.Removed), store, deleteKeys))
        assertEquals(enrolled, store.account)
        assertEquals(listOf("server told, account kept"), events)
    }

    @Test
    fun removalAgainstTheServerEndToEnd() {
        FakeFrictServer(enrolled.userId, "unused").use { server ->
            val keys = SoftwareKeyMaker()
            val created = keys.create(ByteArray(0), UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
            server.enrolled(keys.device.public, keys.approval.public)
            val approvals = Approvals(enrolled, DeviceApi(server.url), created.deviceSigner, { 1762345678L })
            assertEquals(RemoveResult.Removed, removePhone(approvals::remove, store, deleteKeys))
            assertNull(store.account)
            assertEquals(listOf(REMOVE_PATH), server.requests.map { it.path })
        }
    }
}
