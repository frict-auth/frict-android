package app.frict.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private val CHALLENGE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toByteArray()

/** Needs a secure lock screen and an enrolled strong biometric: scripts/emulator.sh sets both up. */
@RunWith(AndroidJUnit4::class)
class DeviceKeysTest {
    private fun assertP256Signing(spec: KeyGenParameterSpec, strongBox: Boolean) {
        assertEquals(KeyProperties.PURPOSE_SIGN, spec.purposes)
        assertEquals("secp256r1", (spec.algorithmParameterSpec as ECGenParameterSpec).name)
        assertArrayEquals(arrayOf(KeyProperties.DIGEST_SHA256), spec.digests)
        assertArrayEquals(CHALLENGE, spec.attestationChallenge)
        assertEquals(strongBox, spec.isStrongBoxBacked)
    }

    @Test
    fun approvalKeyNeedsAStrongBiometricOnEveryUse() {
        for (strongBox in listOf(true, false)) {
            val spec = approvalKeySpec(CHALLENGE, strongBox)
            assertEquals(APPROVAL_KEY_ALIAS, spec.keystoreAlias)
            assertP256Signing(spec, strongBox)
            assertTrue(spec.isUserAuthenticationRequired)
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, spec.userAuthenticationType)
            assertEquals(0, spec.userAuthenticationValidityDurationSeconds)
            assertTrue(spec.isInvalidatedByBiometricEnrollment)
        }
    }

    @Test
    fun deviceKeyNeedsNoUser() {
        for (strongBox in listOf(true, false)) {
            val spec = deviceKeySpec(CHALLENGE, strongBox)
            assertEquals(DEVICE_KEY_ALIAS, spec.keystoreAlias)
            assertP256Signing(spec, strongBox)
            assertFalse(spec.isUserAuthenticationRequired)
        }
    }

    @Test
    fun generatedKeysAreAttestedAndKeepTheirPolicy() {
        val keys = KeystoreKeyMaker(hasStrongBox = false).create(CHALLENGE)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        fun info(alias: String): KeyInfo {
            val key = store.getKey(alias, null) as PrivateKey
            return KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        }
        assertFalse(info(DEVICE_KEY_ALIAS).isUserAuthenticationRequired)
        with(info(APPROVAL_KEY_ALIAS)) {
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
        val keys = KeystoreKeyMaker(hasStrongBox = false).create(CHALLENGE)
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
}
