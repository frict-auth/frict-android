package app.frict.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val HOST = "api.frict.test"
private const val USER_ID = "DUABCDEFGHIJKLMNOPQRSTUVWXYZ"

/** An enrollment saved by an app version with one account, as that version saved it. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val legacyPrefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private lateinit var deviceKey: ByteArray

    @Before
    fun enrolledByAnOlderVersion() {
        cleared()
        deviceKey = KeystoreKeyMaker(hasStrongBox = false).create(KeyAliases.LEGACY, ByteArray(16), UnlockMode.FINGERPRINT_OR_SCREEN_LOCK).deviceKey
        assertTrue(
            legacyPrefs.edit()
                .putString("host", HOST)
                .putString("user_id", USER_ID)
                .putString("unlock_mode", UnlockMode.FINGERPRINT_OR_SCREEN_LOCK.name)
                .commit(),
        )
    }

    @After
    fun cleared() {
        legacyPrefs.edit().clear().commit()
        context.getSharedPreferences("accounts", Context.MODE_PRIVATE).edit().clear().commit()
        deleteKeys(KeyAliases.LEGACY)
    }

    @Test
    fun theOldEnrollmentIsTheFirstAccountWithItsKeysUntouched() {
        val migrated = Account(HOST, USER_ID, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK, KeyAliases.LEGACY)
        assertEquals(listOf(migrated), PrefsAccountStore(context).load())
        assertEquals(emptyMap<String, Any>(), legacyPrefs.all)
        assertEquals(listOf(migrated), PrefsAccountStore(context).load())

        val data = signedBytes(USER_ID, 1762345678, PENDING_PATH, "{}".toByteArray())
        val signature = keystoreSigner(migrated.keys.device).sign(data)
        assertTrue(
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(deviceKey)))
                update(data)
                verify(signature)
            },
        )
        assertNull(lostApprovalKey(migrated.keys.approval))
    }

    @Test
    fun accountsAddedLaterGoAfterIt() {
        val store = PrefsAccountStore(context)
        val admin = Account("admin.frict.test", "DU234567234567234567234567AB", UnlockMode.FINGERPRINT_ONLY, KeyAliases("device_key.a", "approval_key.a"))
        assertTrue(store.add(admin))
        assertEquals(listOf(USER_ID, admin.userId), store.load().map { it.userId })
        assertTrue(store.remove(admin))
        assertEquals(listOf(USER_ID), store.load().map { it.userId })
    }
}
