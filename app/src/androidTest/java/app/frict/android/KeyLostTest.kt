package app.frict.android

import android.Manifest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyPairGenerator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Nothing listens on this port, so the server can't be told of a removal. */
private const val UNREACHABLE = "127.0.0.1:9"
private const val WAIT_MS = 15_000L

/** Two accounts, the first of which Keystore lost keys of, as after the screen lock is removed. */
@RunWith(AndroidJUnit4::class)
class KeyLostTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = PrefsAccountStore(context)
    private val lost = Account(UNREACHABLE, "DUABCDEFGHIJKLMNOPQRSTUVWXYZ", UnlockMode.FINGERPRINT_OR_SCREEN_LOCK, KeyAliases("device_key.lost", "approval_key.lost"))
    private val healthy = Account(UNREACHABLE, "DU234567234567234567234567AB", UnlockMode.FINGERPRINT_OR_SCREEN_LOCK, KeyAliases("device_key.ok", "approval_key.ok"))

    @Before
    fun enrolled() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        unenrolled()
        KeystoreKeyMaker(hasStrongBox = false).create(healthy.keys, ByteArray(16), healthy.unlockMode)
        store.add(lost)
        store.add(healthy)
    }

    @After
    fun unenrolled() {
        store.load().forEach { store.remove(it) }
        deleteKeys(lost.keys)
        deleteKeys(healthy.keys)
    }

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun waitFor(s: String) = compose.waitUntil(WAIT_MS) { compose.onAllNodes(hasText(s)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun lostApprovalKeyBlocksOnlyItsAccountWhoseOnlyWayOnIsRemovalServerFirst() {
        KeyPairGenerator.getInstance("EC", "AndroidKeyStore").run {
            initialize(deviceKeySpec(lost.keys.device, ByteArray(16), strongBox = false))
            generateKeyPair()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(text(R.string.key_lost_title))
            compose.onAllNodesWithText(text(R.string.key_lost_title)).assertCountEquals(1)
            compose.onNodeWithText(text(R.string.approval_key_lost_fingerprint_or_screen_lock)).assertExists()
            compose.onNodeWithText(text(R.string.no_requests)).assertExists()
            compose.onAllNodesWithText(text(R.string.remove_account))[0].performClick()
            compose.onNodeWithText(text(R.string.remove_account_title, lost.userId, lost.host)).assertExists()
            compose.onNodeWithText(text(R.string.remove)).performClick()
            waitFor(text(R.string.server_not_told_title))
            compose.onNodeWithText(text(R.string.remove_anyway)).performClick()
            waitFor(text(R.string.removed, lost.userId, lost.host))
            compose.onNodeWithText(text(R.string.enrolled, healthy.userId, healthy.host)).assertExists()
            compose.onNodeWithText(text(R.string.key_lost_title)).assertDoesNotExist()
        }
        assertEquals(listOf(healthy), store.load())
        assertNull(lostApprovalKey(healthy.keys.approval))
    }

    @Test
    fun lostDeviceKeyIsExplained() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(text(R.string.device_key_lost))
            compose.onAllNodesWithText(text(R.string.device_key_lost)).assertCountEquals(1)
        }
    }
}
