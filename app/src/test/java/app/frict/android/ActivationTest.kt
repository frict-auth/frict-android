package app.frict.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val USER = "DUABCDEFGHIJKLMNOPQRSTUVWXYZ"

class ActivationTest {
    @Test
    fun parsesTheServersLink() {
        // As frict-server's ActivationLink encodes it: url.Values sorts the keys.
        assertEquals(
            Activation("api.frict.app", USER, "ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
            parseActivation("frict://enroll?code=ABCDEFGHIJKLMNOPQRSTUVWXYZ&host=api.frict.app&user_id=$USER"),
        )
    }

    @Test
    fun acceptsAPortAndSurroundingWhitespace() {
        assertEquals(
            Activation("10.0.2.2:8443", USER, "C"),
            parseActivation("  frict://enroll?host=10.0.2.2%3A8443&user_id=$USER&code=C\n"),
        )
    }

    @Test
    fun rejectsOtherLinks() {
        for (link in listOf(
            "https://enroll?code=C&host=h&user_id=$USER",
            "frict://other?code=C&host=h&user_id=$USER",
            "frict://enroll?host=h&user_id=$USER",
            "frict://enroll?code=C&user_id=$USER",
            "frict://enroll?code=C&host=h",
            "frict://enroll?code=C&host=h&user_id=DUshort",
            "frict://enroll?code=C&host=evil.test/x%3F&user_id=$USER",
            "frict://enroll?code=C&host=me%40evil.test&user_id=$USER",
            "frict://enroll?code=%ZZ&host=h&user_id=$USER",
            "not a link",
        )) {
            assertNull(link, parseActivation(link))
        }
    }
}
