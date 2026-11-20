package app.frict.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertsTest {
    @Test
    fun absoluteHttpAndHttpsLinksWithAHostOpen() {
        for (url in listOf(
            "https://auth.example/if/admin/#/identity/users;%7B%22ak-user-list-search%22:%22geoffrey%22%7D",
            "http://127.0.0.1:9000/if/admin/",
            "HTTPS://Auth.Example/",
            "https://[2001:db8::1]:8443/",
        )) {
            assertEquals(url, url, openableLink(url))
        }
    }

    @Test
    fun otherSchemesAreNeverOpened() {
        for (url in listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.example;end",
            "javascript:alert(1)",
            "javascript://auth.example/%0Aalert(1)",
            "file:///data/data/app.frict.android/shared_prefs/account.xml",
            "frict://enroll?code=x",
            "content://app.frict.android/x",
            "data:text/html,<script>alert(1)</script>",
            "ftp://auth.example/",
        )) {
            assertNull(url, openableLink(url))
        }
    }

    @Test
    fun hostlessRelativeOrMalformedLinksAreNeverOpened() {
        for (url in listOf(
            "",
            "https://",
            "https:///if/admin/",
            "https:/auth.example/",
            "https:auth.example",
            "//auth.example/if/admin/",
            "/if/admin/",
            "auth.example",
            "https://auth example/",
            " https://auth.example/",
            "https://auth.example\\@evil.example/",
            "https://auth_example/",
        )) {
            assertNull(url, openableLink(url))
        }
    }

    @Test
    fun linksWithUserinfoAreNeverOpened() {
        for (url in listOf(
            "https://user@auth.example/",
            "https://user:password@auth.example/",
            "https://auth.example@evil.example/",
            "https://@evil.example/",
        )) {
            assertNull(url, openableLink(url))
        }
    }
}
