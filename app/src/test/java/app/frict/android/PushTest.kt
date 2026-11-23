package app.frict.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val ID = "AAAAAAAAAAAAAAAAAAAAAA"

class PushTest {
    @Test
    fun loginPushNamesItsChallenge() {
        assertEquals(ID, parsePush(mapOf("challenge_id" to ID)))
    }

    @Test
    fun detailsInAPushAreIgnored() {
        assertEquals(ID, parsePush(mapOf("challenge_id" to ID, "username" to "mallory", "ipaddr" to "198.51.100.1")))
    }

    @Test
    fun anythingButA22CharacterBase64urlChallengeIdIsIgnored() {
        assertNull(parsePush(emptyMap()))
        assertNull(parsePush(mapOf("challenge_id" to "")))
        assertNull(parsePush(mapOf("challenge_id" to "A".repeat(21))))
        assertNull(parsePush(mapOf("challenge_id" to "A".repeat(21) + "/")))
        assertNull(parsePush(mapOf("alert_id" to ID)))
        assertNull(parsePush(mapOf("username" to "mallory")))
    }
}
