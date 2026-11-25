package app.frict.android

import android.Manifest
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyPairGenerator
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val USER_ID = "DUABCDEFGHIJKLMNOPQRSTUVWXYZ"

/** Nothing listens on this port, so the server can't be told of a removal. */
private const val UNREACHABLE = "127.0.0.1:9"
private const val WAIT_MS = 15_000L

/** An enrolled account whose keys Keystore lost, as after the screen lock is removed. */
@RunWith(AndroidJUnit4::class)
class KeyLostTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PrefsAccountStore(context)

    @Before
    fun enrolledWithoutKeys() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        deleteKeys()
        store.save(Account(UNREACHABLE, USER_ID, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK))
    }

    @After
    fun unenrolled() {
        store.clear()
        deleteKeys()
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun waitFor(s: String) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(s)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun lostApprovalKeyIsExplainedAndTheOnlyWayOnIsRemovingThePhoneServerFirst() {
        KeyPairGenerator.getInstance("EC", "AndroidKeyStore").run {
            initialize(deviceKeySpec(ByteArray(16), strongBox = false))
            generateKeyPair()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(text(R.string.key_lost_title))
            compose.onNodeWithText(text(R.string.approval_key_lost_fingerprint_or_screen_lock)).assertExists()
            compose.onNodeWithText(text(R.string.no_requests)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.remove_phone)).performClick()
            compose.onNodeWithText(text(R.string.remove)).performClick()
            waitFor(text(R.string.server_not_told_title))
            compose.onNodeWithText(text(R.string.remove_anyway)).performClick()
            waitFor(text(R.string.not_enrolled))
        }
        assertNull(store.load())
    }

    @Test
    fun lostDeviceKeyIsExplained() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(text(R.string.device_key_lost))
            compose.onNodeWithText(text(R.string.remove_phone)).assertExists()
        }
    }
}
