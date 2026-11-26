package app.frict.android

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivacyPolicyUrlTest {
    private val url = URI(BuildConfig.PRIVACY_POLICY_URL)

    @Test
    fun isHttps() = assertEquals("https", url.scheme)

    @Test
    fun isFrictsPolicySite() = assertEquals("privacy.frict.app", url.host)
}
