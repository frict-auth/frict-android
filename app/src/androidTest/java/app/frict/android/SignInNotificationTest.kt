package app.frict.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignInNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val account = Account("api.notify-host.test:8443", "DUNOTIFYTEST000000000000000000", UnlockMode.FINGERPRINT_ONLY, KeyAliases("device_key.n", "approval_key.n"), "Work admin label")
    private val challenge = Challenge("notify-test-challenge", "alice-notify", "203.0.113.77", listOf("Domain" to "corp-notify.example", "Application" to "SSH bastion notify"), "push", 0, 0)
    private val details = listOf(account.name, "notify-host", account.userId, challenge.username, challenge.ipaddr, "corp-notify.example", "SSH bastion notify")

    @Before
    fun allowed() = InstrumentationRegistry.getInstrumentation().uiAutomation
        .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)

    @After
    fun cancelled() = notifications.cancel(challenge.id, 0)

    @Test
    fun sayingOnlySignInRequestLockedOrNot() {
        postSignInNotification(context, OwnedChallenge(account, challenge))
        val posted = posted()
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        for (n in listOf(posted, checkNotNull(posted.publicVersion) { "no public version" })) {
            assertEquals(context.getString(R.string.notify_login), n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(context.getString(R.string.notify_login_text), n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            val shown = shownText(n)
            for (detail in details) assertTrue("\"$detail\" in $shown", !shown.contains(detail, ignoreCase = true))
        }
    }

    /** Posting is asynchronous. */
    private fun posted(): Notification {
        repeat(50) {
            notifications.activeNotifications.singleOrNull { it.tag == challenge.id }?.let { return it.notification }
            Thread.sleep(100)
        }
        throw AssertionError("no notification ${challenge.id} within 5s")
    }

    @Suppress("DEPRECATION")
    private fun shownText(n: Notification) =
        (n.extras.keySet().map { "${n.extras.get(it)}" } + "${n.tickerText}").joinToString(" | ")
}
