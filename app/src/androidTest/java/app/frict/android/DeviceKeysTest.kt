package app.frict.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.SignatureException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private val CHALLENGE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray()
private val ALIASES = KeyAliases("device_key.test", "approval_key.test")
private val OTHER = KeyAliases("device_key.other", "approval_key.other")

/** Needs a secure lock screen and an enrolled strong biometric: scripts/emulator.sh sets both up. */
@RunWith(AndroidJUnit4::class)
class DeviceKeysTest {
    @After
    fun deleted() {
        deleteKeys(ALIASES)
        deleteKeys(OTHER)
    }

    private fun assertP256Signing(spec: KeyGenParameterSpec, strongBox: Boolean) {
        assertEquals(KeyProperties.PURPOSE_SIGN, spec.purposes)
        assertEquals("secp256r1", (spec.algorithmParameterSpec as ECGenParameterSpec).name)
        assertArrayEquals(arrayOf(KeyProperties.DIGEST_SHA256), spec.digests)
        assertArrayEquals(CHALLENGE, spec.attestationChallenge)
        assertEquals(strongBox, spec.isStrongBoxBacked)
    }

    private fun assertApprovalKeySpec(mode: UnlockMode, wantAuthTypes: Int) {
        for (strongBox in listOf(true, false)) {
            val spec = approvalKeySpec(ALIASES.approval, CHALLENGE, strongBox, mode)
            assertEquals(ALIASES.approval, spec.keystoreAlias)
            assertP256Signing(spec, strongBox)
            assertTrue(spec.isUserAuthenticationRequired)
            assertEquals(wantAuthTypes, spec.userAuthenticationType)
            assertEquals(0, spec.userAuthenticationValidityDurationSeconds)
            assertTrue(spec.isInvalidatedByBiometricEnrollment)
        }
    }

    @Test
    fun fingerprintOrScreenLockApprovalKeyNeedsEitherOnEveryUse() = assertApprovalKeySpec(
        UnlockMode.FINGERPRINT_OR_SCREEN_LOCK,
        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
    )

    @Test
    fun fingerprintOnlyApprovalKeyNeedsAStrongBiometricOnEveryUse() =
        assertApprovalKeySpec(UnlockMode.FINGERPRINT_ONLY, KeyProperties.AUTH_BIOMETRIC_STRONG)

    @Test
    fun deviceKeyNeedsNoUser() {
        for (strongBox in listOf(true, false)) {
            val spec = deviceKeySpec(ALIASES.device, CHALLENGE, strongBox)
            assertEquals(ALIASES.device, spec.keystoreAlias)
            assertP256Signing(spec, strongBox)
            assertFalse(spec.isUserAuthenticationRequired)
        }
    }

    private fun info(alias: String): KeyInfo {
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as PrivateKey
        return KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
    }

    @Test
    fun fingerprintOrScreenLockKeyKeepsItsPolicy() {
        KeystoreKeyMaker(hasStrongBox = false).create(ALIASES, CHALLENGE, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
        with(info(ALIASES.approval)) {
            assertTrue(isUserAuthenticationRequired)
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL, userAuthenticationType)
            assertEquals(0, userAuthenticationValidityDurationSeconds)
        }
    }

    @Test
    fun generatedKeysAreAttestedAndKeepTheirPolicy() {
        val keys = KeystoreKeyMaker(hasStrongBox = false).create(ALIASES, CHALLENGE, UnlockMode.FINGERPRINT_ONLY)
        assertFalse(info(ALIASES.device).isUserAuthenticationRequired)
        with(info(ALIASES.approval)) {
            assertTrue(isUserAuthenticationRequired)
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, userAuthenticationType)
            assertEquals(0, userAuthenticationValidityDurationSeconds)
            assertTrue(isInvalidatedByBiometricEnrollment)
        }
        val certs = CertificateFactory.getInstance("X.509")
        for ((spki, chain) in listOf(keys.deviceKey to keys.deviceKeyAttestation, keys.approvalKey to keys.approvalKeyAttestation)) {
            assertTrue(chain.size > 1)
            val leaf = certs.generateCertificate(chain.first().inputStream()) as X509Certificate
            assertArrayEquals(spki, leaf.publicKey.encoded)
        }
        assertFalse(keys.deviceKey.contentEquals(keys.approvalKey))
    }

    @Test
    fun deviceSignerSignsWithTheDeviceKey() {
        val keys = KeystoreKeyMaker(hasStrongBox = false).create(ALIASES, CHALLENGE, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
        val data = signedBytes("DUABCDEFGHIJKLMNOPQRSTUVWXYZ", 1762345678, ENROLL_PATH, "{}".toByteArray())
        val signature = keys.deviceSigner.sign(data)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keys.deviceKey))
        assertTrue(
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(data)
                verify(signature)
            },
        )
    }

    @Test
    fun approvalKeySignsNothingWithoutThePrompt() {
        for (mode in UnlockMode.entries) {
            KeystoreKeyMaker(hasStrongBox = false).create(ALIASES, CHALLENGE, mode)
            val signer = approvalSignature(ALIASES.approval).asSigner()
            assertThrows(mode.name, SignatureException::class.java) { signer.sign("frict-device-v1".toByteArray()) }
        }
    }

    @Test
    fun deleteKeysRemovesBothKeysOfOnlyThatAccount() {
        KeystoreKeyMaker(hasStrongBox = false).create(ALIASES, CHALLENGE, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
        KeystoreKeyMaker(hasStrongBox = false).create(OTHER, CHALLENGE, UnlockMode.FINGERPRINT_ONLY)
        deleteKeys(ALIASES)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(store.containsAlias(ALIASES.device))
        assertFalse(store.containsAlias(ALIASES.approval))
        assertTrue(store.containsAlias(OTHER.device))
        assertTrue(store.containsAlias(OTHER.approval))
        assertEquals(null, lostApprovalKey(OTHER.approval))
    }
}
