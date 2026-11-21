package app.frict.android

import android.hardware.biometrics.BiometricManager.Authenticators
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

const val DEVICE_KEY_ALIAS = "device_key"
const val APPROVAL_KEY_ALIAS = "approval_key"
private const val KEYSTORE = "AndroidKeyStore"

/**
 * What unlocks the approval_key, fixed when the key is made: changing it means enrolling
 * again. [keyAuthTypes] are the key's KeyProperties.AUTH_* types; [promptAuthenticators]
 * are the BiometricPrompt's, which must accept exactly those.
 */
enum class UnlockMode(val keyAuthTypes: Int, val promptAuthenticators: Int) {
    FINGERPRINT_OR_SCREEN_LOCK(
        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
        Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
    ),
    FINGERPRINT_ONLY(KeyProperties.AUTH_BIOMETRIC_STRONG, Authenticators.BIOMETRIC_STRONG),
    ;

    /** The platform rejects a negative button on a prompt that offers the screen lock. */
    val promptHasBackButton get() = promptAuthenticators and Authenticators.DEVICE_CREDENTIAL == 0
}

/** A new device_key and approval_key: public keys as SPKI DER, attestation chains leaf first. */
class EnrollmentKeys(
    val deviceKey: ByteArray,
    val approvalKey: ByteArray,
    val deviceKeyAttestation: List<ByteArray>,
    val approvalKeyAttestation: List<ByteArray>,
    val deviceSigner: Signer,
)

/**
 * Replaces both keys with new ones whose attestations carry [attestationChallenge]; [mode]
 * unlocks the approval_key.
 */
fun interface KeyMaker {
    fun create(attestationChallenge: ByteArray, mode: UnlockMode): EnrollmentKeys
}

fun deviceKeySpec(attestationChallenge: ByteArray, strongBox: Boolean): KeyGenParameterSpec =
    p256SigningSpec(DEVICE_KEY_ALIAS, attestationChallenge, strongBox).build()

fun approvalKeySpec(attestationChallenge: ByteArray, strongBox: Boolean, mode: UnlockMode): KeyGenParameterSpec =
    p256SigningSpec(APPROVAL_KEY_ALIAS, attestationChallenge, strongBox)
        .setUserAuthenticationRequired(true)
        .setUserAuthenticationParameters(0, mode.keyAuthTypes)
        .setInvalidatedByBiometricEnrollment(true)
        .build()

private fun p256SigningSpec(alias: String, attestationChallenge: ByteArray, strongBox: Boolean) =
    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setAttestationChallenge(attestationChallenge)
        .setIsStrongBoxBacked(strongBox)

/** Android Keystore keys, in StrongBox when [hasStrongBox] and it has room, else in the TEE. */
class KeystoreKeyMaker(private val hasStrongBox: Boolean) : KeyMaker {
    override fun create(attestationChallenge: ByteArray, mode: UnlockMode): EnrollmentKeys {
        generate(deviceKeySpec(attestationChallenge, hasStrongBox), deviceKeySpec(attestationChallenge, false))
        generate(approvalKeySpec(attestationChallenge, hasStrongBox, mode), approvalKeySpec(attestationChallenge, false, mode))
        val keys = keyStore()
        val chain = { alias: String -> keys.getCertificateChain(alias).map { it.encoded } }
        return EnrollmentKeys(
            deviceKey = keys.getCertificate(DEVICE_KEY_ALIAS).publicKey.encoded,
            approvalKey = keys.getCertificate(APPROVAL_KEY_ALIAS).publicKey.encoded,
            deviceKeyAttestation = chain(DEVICE_KEY_ALIAS),
            approvalKeyAttestation = chain(APPROVAL_KEY_ALIAS),
            deviceSigner = keystoreSigner(DEVICE_KEY_ALIAS),
        )
    }

    private fun generate(spec: KeyGenParameterSpec, teeSpec: KeyGenParameterSpec) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        try {
            generator.initialize(spec)
            generator.generateKeyPair()
        } catch (_: StrongBoxUnavailableException) {
            generator.initialize(teeSpec)
            generator.generateKeyPair()
        }
    }
}

fun keystoreSigner(alias: String) = Signer { data ->
    Signature.getInstance("SHA256withECDSA").run {
        initSign(keyStore().getKey(alias, null) as PrivateKey)
        update(data)
        sign()
    }
}

/**
 * A Signature over the approval_key for a BiometricPrompt CryptoObject. Keystore lets it
 * sign once, and only after that prompt succeeds.
 */
fun approvalSignature(): Signature = Signature.getInstance("SHA256withECDSA").apply {
    initSign(keyStore().getKey(APPROVAL_KEY_ALIAS, null) as PrivateKey)
}

/** Signs once with an authenticated [Signature], such as a BiometricPrompt result's. */
fun Signature.asSigner() = Signer { data ->
    update(data)
    sign()
}

fun deleteKeys() = keyStore().run {
    deleteEntry(DEVICE_KEY_ALIAS)
    deleteEntry(APPROVAL_KEY_ALIAS)
}

private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
