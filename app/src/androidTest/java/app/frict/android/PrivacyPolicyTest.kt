package app.frict.android

import android.Manifest
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records ACTION_VIEW starts instead of starting them; lets every other start through. */
private class ViewRecorder : Instrumentation.ActivityMonitor() {
    val started = mutableListOf<Intent>()

    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
        if (intent.action != Intent.ACTION_VIEW) return null
        started += intent
        return Instrumentation.ActivityResult(0, null)
    }
}

@RunWith(AndroidJUnit4::class)
class PrivacyPolicyTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store = PrefsAccountStore(context)
    private val recorder = ViewRecorder()

    @Before
    fun noNotificationPrompt() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        unenrolled()
        instrumentation.addMonitor(recorder)
    }

    @After
    fun stopRecording() = instrumentation.removeMonitor(recorder)

    @After
    fun unenrolled() = store.load().forEach { store.remove(it) }

    private fun opensThePolicyInTheBrowser(screen: String) {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(screen)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(R.string.privacy_policy)).performClick()
            compose.waitForIdle()
            assertEquals(listOf(BuildConfig.PRIVACY_POLICY_URL), recorder.started.map { it.dataString })
        }
    }

    @Test
    fun fromTheEnrollmentScreen() = opensThePolicyInTheBrowser(context.getString(R.string.not_enrolled))

    @Test
    fun fromTheAccountList() {
        store.add(Account("127.0.0.1:9", "DUABCDEFGHIJKLMNOPQRSTUVWXYZ", UnlockMode.FINGERPRINT_OR_SCREEN_LOCK, KeyAliases("device_key.p", "approval_key.p"), "Policy reader"))
        opensThePolicyInTheBrowser("Policy reader")
    }
}
