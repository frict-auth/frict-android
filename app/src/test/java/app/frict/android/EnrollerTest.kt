package app.frict.android

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val USER_ID = "DUABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val CODE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val NOW = 1762345678L

/** Software P-256 keys standing in for the Keystore, with placeholder attestation chains. */
class SoftwareKeyMaker : KeyMaker {
    lateinit var device: KeyPair
    lateinit var approval: KeyPair
    lateinit var challenge: ByteArray
    lateinit var mode: UnlockMode
    val made = mutableListOf<KeyAliases>()

    override fun create(aliases: KeyAliases, attestationChallenge: ByteArray, mode: UnlockMode): EnrollmentKeys {
        made += aliases
        device = p256()
        approval = p256()
        challenge = attestationChallenge
        this.mode = mode
        return EnrollmentKeys(
            deviceKey = device.public.encoded,
            approvalKey = approval.public.encoded,
            deviceKeyAttestation = listOf("device leaf".toByteArray(), "root".toByteArray()),
            approvalKeyAttestation = listOf("approval leaf".toByteArray(), "root".toByteArray()),
            deviceSigner = Signer { data ->
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(device.private)
                    update(data)
                    sign()
                }
            },
        )
    }

    private fun p256(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
}

class EnrollerTest {
    private val server = FakeFrictServer(USER_ID, CODE)
    private val keys = SoftwareKeyMaker()
    private val store = MemoryAccountStore()
    private val hosts = mutableListOf<String>()
    private val deleted = mutableListOf<KeyAliases>()
    private val aliases = KeyAliases("device_key.new", "approval_key.new")
    private val enroller = enroller(server)

    private fun enroller(s: FakeFrictServer, into: AccountStore = store) = Enroller(
        keys = keys,
        deleteKeys = { deleted += it },
        store = into,
        api = { host -> hosts += host; DeviceApi(s.url) },
        fid = { "fid-1" },
        unixSeconds = { NOW },
        newAliases = { aliases },
    )
    private val link = "frict://enroll?code=$CODE&host=api.frict.test&user_id=$USER_ID"

    @After
    fun close() = server.close()

    @Test
    fun enrollsAndSavesTheActivationsUser() {
        val want = Account("api.frict.test", USER_ID, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK, aliases, "alice @ api.frict.test")
        assertEquals(EnrollResult.Enrolled(want), enroller.enroll(link, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK))
        assertEquals(listOf(want), store.accounts)
        assertEquals(listOf(aliases), keys.made)
        assertEquals(emptyList<KeyAliases>(), deleted)
        assertEquals(listOf("api.frict.test"), hosts)
        assertEquals(ENROLL_PATH, server.requests.single().path)
    }

    @Test
    fun makesTheApprovalKeyAndSavesTheAccountWithTheChosenMode() {
        for (mode in UnlockMode.entries) {
            val modeStore = MemoryAccountStore()
            FakeFrictServer(USER_ID, CODE).use { s ->
                enroller(s, modeStore).enroll(link, mode)
            }
            assertEquals(mode, keys.mode)
            assertEquals(mode, modeStore.accounts.single().unlockMode)
        }
    }

    @Test
    fun signsWithDeviceKeyNotApprovalKey() {
        enroller.enroll(link, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
        val r = server.requests.single()
        val (user, unix, sig) = r.authorization.removePrefix("Frict-P256 ").split(':')
        assertEquals(USER_ID, user)
        assertEquals(NOW, unix.toLong())
        val signed = signedBytes(user, unix.toLong(), ENROLL_PATH, r.body)
        fun verifiesWith(kp: KeyPair) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(kp.public)
            update(signed)
            verify(b64url(sig))
        }
        assertTrue(verifiesWith(keys.device))
        assertFalse(verifiesWith(keys.approval))
    }

    @Test
    fun bodyCarriesEveryProtocolField() {
        enroller.enroll(link, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
        val body = server.requests.single().json
        assertEquals(setOf("activation_code", "device_key", "approval_key", "attestation", "fid"), body.keys().asSequence().toSet())
        assertEquals(CODE, body.getString("activation_code"))
        assertArrayEquals(keys.device.public.encoded, b64url(body.getString("device_key")))
        assertArrayEquals(keys.approval.public.encoded, b64url(body.getString("approval_key")))
        val attestation = body.getJSONObject("attestation")
        assertEquals(setOf("device_key", "approval_key"), attestation.keys().asSequence().toSet())
        val chain = { k: String -> attestation.getJSONArray(k).let { a -> List(a.length()) { String(b64url(a.getString(it))) } } }
        assertEquals(listOf("device leaf", "root"), chain("device_key"))
        assertEquals(listOf("approval leaf", "root"), chain("approval_key"))
        assertEquals("fid-1", body.getString("fid"))
    }

    @Test
    fun attestationChallengeIsTheActivationCode() {
        enroller.enroll(link, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
        assertArrayEquals(CODE.toByteArray(), keys.challenge)
    }

    @Test
    fun refusedEnrollmentSavesNothingAndDeletesItsKeys() {
        FakeFrictServer(USER_ID, "SOMEOTHERCODE").use { other ->
            assertEquals(EnrollResult.Rejected(401), enroller(other).enroll(link, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK))
        }
        assertEquals(emptyList<Account>(), store.accounts)
        assertEquals(listOf(aliases), deleted)
    }

    @Test
    fun anUnsavedEnrollmentDeletesItsKeys() {
        store.writesSucceed = false
        assertEquals(EnrollResult.NotSaved, enroller.enroll(link, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK))
        assertEquals(listOf(aliases), deleted)
    }

    @Test
    fun keysThatCannotBeMadeAreDeleted() {
        val e = Enroller({ _, _, _ -> error("no Keystore") }, { deleted += it }, store, { DeviceApi(server.url) }, { "fid-1" }, { NOW }, { aliases })
        assertEquals(EnrollResult.KeysUnavailable("no Keystore"), e.enroll(link, UnlockMode.FINGERPRINT_ONLY))
        assertEquals(listOf(aliases), deleted)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun anotherAccountIsAddedAfterTheOnesThere() {
        val other = testAccount("DU234567234567234567234567AB", host = "other.frict.test")
        store.accounts += other
        val got = enroller.enroll(link, UnlockMode.FINGERPRINT_ONLY)
        assertEquals(EnrollResult.Enrolled(Account("api.frict.test", USER_ID, UnlockMode.FINGERPRINT_ONLY, aliases, "alice @ api.frict.test")), got)
        assertEquals(listOf(other, (got as EnrollResult.Enrolled).account), store.accounts)
    }

    @Test
    fun theSameUserOnAnotherServerIsAnotherAccount() {
        store.accounts += testAccount(USER_ID, host = "other.frict.test")
        assertTrue(enroller.enroll(link, UnlockMode.FINGERPRINT_ONLY) is EnrollResult.Enrolled)
        assertEquals(2, store.accounts.size)
    }

    @Test
    fun theSameUserOnTheSameServerIsRefusedAndSendsNothing() {
        val existing = testAccount(USER_ID, host = "API.Frict.Test")
        store.accounts += existing
        assertEquals(EnrollResult.AlreadyEnrolled, enroller.enroll(link, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK))
        assertEquals(listOf(existing), store.accounts)
        assertTrue(server.requests.isEmpty())
        assertEquals(emptyList<KeyAliases>(), keys.made)
        assertEquals(emptyList<KeyAliases>(), deleted)
    }

    @Test
    fun newAccountsGetTheirOwnKeyAliases() {
        val a = KeyAliases.random()
        val b = KeyAliases.random()
        assertNotEquals(a, b)
        assertNotEquals(a.device, a.approval)
        for (it in listOf(a, b)) assertFalse(KeyAliases.LEGACY.device == it.device || KeyAliases.LEGACY.approval == it.approval)
    }

    @Test
    fun unreachableServerIsANetworkError() {
        val port = server.url.substringAfterLast(':')
        server.close()
        val e = Enroller(keys, { deleted += it }, store, { DeviceApi("http://127.0.0.1:$port") }, { "fid-1" }, { NOW }, { aliases })
        assertTrue(e.enroll(link, UnlockMode.FINGERPRINT_OR_SCREEN_LOCK) is EnrollResult.NetworkError)
        assertEquals(emptyList<Account>(), store.accounts)
        assertEquals(listOf(aliases), deleted)
    }
}
