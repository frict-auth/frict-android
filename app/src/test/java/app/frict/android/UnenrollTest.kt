package app.frict.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnenrollTest {
    private val enrolled = Account("api.frict.test", "DUABCDEFGHIJKLMNOPQRSTUVWXYZ", UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)

    @Test
    fun removesTheAccountAndTheKeys() {
        val store = MemoryAccountStore(enrolled)
        var keysDeleted = 0
        assertTrue(unenroll(store) { keysDeleted++ })
        assertNull(store.account)
        assertEquals(1, keysDeleted)
    }

    @Test
    fun keepsTheKeysWhenTheAccountCannotBeRemoved() {
        val store = MemoryAccountStore(enrolled).apply { clearSucceeds = false }
        var keysDeleted = 0
        assertFalse(unenroll(store) { keysDeleted++ })
        assertEquals(enrolled, store.account)
        assertEquals(0, keysDeleted)
    }
}
