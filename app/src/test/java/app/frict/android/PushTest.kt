package app.frict.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val ID = "AAAAAAAAAAAAAAAAAAAAAA"
private const val USER = "DUABCDEFGHIJKLMNOPQRSTUVWXYZ"

class PushTest {
    @Test
    fun loginPushNamesItsChallengeAndUser() {
        assertEquals(LoginPush(ID, USER), parsePush(mapOf("challenge_id" to ID, "user_id" to USER)))
    }

    @Test
    fun detailsInAPushAreIgnored() {
        assertEquals(LoginPush(ID, USER), parsePush(mapOf("challenge_id" to ID, "user_id" to USER, "username" to "mallory", "ipaddr" to "198.51.100.1")))
    }

    @Test
    fun aPushWithoutAValidChallengeIdIsIgnored() {
        assertNull(parsePush(mapOf("user_id" to USER)))
        assertNull(parsePush(mapOf("challenge_id" to "", "user_id" to USER)))
        assertNull(parsePush(mapOf("challenge_id" to "A".repeat(21), "user_id" to USER)))
        assertNull(parsePush(mapOf("challenge_id" to "A".repeat(21) + "/", "user_id" to USER)))
        assertNull(parsePush(mapOf("alert_id" to ID, "user_id" to USER)))
    }

    @Test
    fun aPushWithoutAValidUserIdIsIgnored() {
        assertNull(parsePush(mapOf("challenge_id" to ID)))
        assertNull(parsePush(mapOf("challenge_id" to ID, "user_id" to "")))
        assertNull(parsePush(mapOf("challenge_id" to ID, "user_id" to "DU" + "A".repeat(25))))
        assertNull(parsePush(mapOf("challenge_id" to ID, "user_id" to USER.lowercase())))
        assertNull(parsePush(mapOf("challenge_id" to ID, "username" to "mallory")))
    }
}
