package app.frict.android

import android.hardware.biometrics.BiometricManager.Authenticators
import android.security.keystore.KeyProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockModeTest {
    @Test
    fun fingerprintOrScreenLockKeyAcceptsAStrongBiometricOrTheScreenLock() {
        assertEquals(
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            UnlockMode.FINGERPRINT_OR_SCREEN_LOCK.keyAuthTypes,
        )
    }

    @Test
    fun fingerprintOnlyKeyAcceptsOnlyAStrongBiometric() {
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, UnlockMode.FINGERPRINT_ONLY.keyAuthTypes)
    }

    @Test
    fun fingerprintOrScreenLockPromptOffersTheScreenLockWithoutABackButton() {
        val mode = UnlockMode.FINGERPRINT_OR_SCREEN_LOCK
        assertEquals(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL, mode.promptAuthenticators)
        assertFalse(mode.promptHasBackButton)
    }

    @Test
    fun fingerprintOnlyPromptOffersOnlyAStrongBiometricAndABackButton() {
        val mode = UnlockMode.FINGERPRINT_ONLY
        assertEquals(Authenticators.BIOMETRIC_STRONG, mode.promptAuthenticators)
        assertTrue(mode.promptHasBackButton)
    }

    @Test
    fun promptAcceptsExactlyWhatTheKeyAccepts() {
        for (mode in UnlockMode.entries) {
            val biometric = mode.promptAuthenticators and Authenticators.DEVICE_CREDENTIAL.inv()
            assertEquals(mode.name, mode.keyAuthTypes and KeyProperties.AUTH_BIOMETRIC_STRONG != 0, biometric == Authenticators.BIOMETRIC_STRONG)
            assertTrue(mode.name, biometric == 0 || biometric == Authenticators.BIOMETRIC_STRONG)
            assertEquals(
                mode.name,
                mode.keyAuthTypes and KeyProperties.AUTH_DEVICE_CREDENTIAL != 0,
                mode.promptAuthenticators and Authenticators.DEVICE_CREDENTIAL != 0,
            )
        }
    }

    @Test
    fun noScreenLockBlocksEveryMode() {
        for (mode in UnlockMode.entries) {
            assertEquals(R.string.needs_screen_lock, enrollBlocker(mode, screenLock = false, strongBiometric = true))
        }
    }

    @Test
    fun fingerprintOrScreenLockEnrollsWithAScreenLockAlone() {
        assertNull(enrollBlocker(UnlockMode.FINGERPRINT_OR_SCREEN_LOCK, screenLock = true, strongBiometric = false))
    }

    @Test
    fun fingerprintOnlyNeedsAStrongBiometric() {
        assertEquals(R.string.needs_biometric, enrollBlocker(UnlockMode.FINGERPRINT_ONLY, screenLock = true, strongBiometric = false))
        assertNull(enrollBlocker(UnlockMode.FINGERPRINT_ONLY, screenLock = true, strongBiometric = true))
    }
}
