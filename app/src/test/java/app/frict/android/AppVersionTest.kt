package app.frict.android

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val USER_ID = "DUABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val CODE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val CHALLENGE_ID = "AAAAAAAAAAAAAAAAAAAAAA"

class AppVersionTest {
    private val server = FakeFrictServer(USER_ID, CODE)
    private val device = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val signer = Signer { data ->
        Signature.getInstance("SHA256withECDSA").run {
            initSign(device.private)
            update(data)
            sign()
        }
    }
    private val account = Account("api.frict.test", USER_ID, UnlockMode.FINGERPRINT_ONLY)
    private val approvals = Approvals(account, DeviceApi(server.url), signer, { 1762345678L })

    init {
        server.enrolled(device.public, device.public)
    }

    @After
    fun close() = server.close()

    private fun enroller(store: AccountStore) =
        Enroller(SoftwareKeyMaker(), store, { DeviceApi(server.url) }, { "fid-1" }, { 1762345678L })

    @Test
    fun everyRequestCarriesTheVersionCode() {
        server.addChallenge(CHALLENGE_ID)
        enroller(MemoryAccountStore()).enroll("frict://enroll?code=$CODE&host=h&user_id=$USER_ID", UnlockMode.FINGERPRINT_ONLY)
        server.enrolled(device.public, device.public)
        approvals.poll(shownId = null)
        approvals.answer(CHALLENGE_ID, Answer.APPROVE, signer)
        approvals.sendFid("fid-2")
        approvals.remove()
        val paths = listOf(ENROLL_PATH, PENDING_PATH, CHALLENGE_PATH, ANSWER_PATH, FID_PATH, REMOVE_PATH)
        assertEquals(paths, server.requests.map { it.path })
        assertTrue(BuildConfig.VERSION_CODE > 0)
        assertEquals(paths.map { BuildConfig.VERSION_CODE.toString() }, server.requests.map { it.appVersion })
    }

    @Test
    fun theVersionIsNotSigned() {
        approvals.poll(shownId = null)
        val r = server.requests.single()
        val unix = r.authorization.split(':')[1].toLong()
        val signed = signedBytes(USER_ID, unix, PENDING_PATH, r.body)
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(device.public)
            update(signed)
            verify(b64url(r.authorization.substringAfterLast(':')))
        })
    }

    @Test
    fun updateRequiredPollFetchesNothing() {
        server.addChallenge(CHALLENGE_ID)
        server.updateRequired = true
        assertEquals(Poll(UpdateRequired, DEFAULT_POLL_SECONDS), approvals.poll(shownId = null))
        assertEquals(listOf(PENDING_PATH), server.requests.map { it.path })
    }

    @Test
    fun updateRequiredOnOpeningAPushedChallenge() {
        server.addChallenge(CHALLENGE_ID)
        server.updateRequired = true
        assertEquals(UpdateRequired, approvals.open(CHALLENGE_ID))
    }

    @Test
    fun updateRequiredAnswerIsNotRetried() {
        server.addChallenge(CHALLENGE_ID)
        server.updateRequired = true
        assertEquals(UpdateRequired, approvals.answer(CHALLENGE_ID, Answer.APPROVE, signer))
        assertEquals(1, server.requests.size)
        assertEquals(ChallengeState.PENDING, server.challenges[CHALLENGE_ID])
    }

    @Test
    fun updateRequiredFid() {
        server.updateRequired = true
        assertEquals(UpdateRequired, approvals.sendFid("fid-2"))
    }

    @Test
    fun updateRequiredRemovalRemovesNothingHereAndOffersNoRemoveAnyway() {
        server.updateRequired = true
        val store = MemoryAccountStore(account)
        val deleted = mutableListOf<String>()
        assertEquals(UpdateRequired, removePhone(approvals::remove, store, { deleted += "keys" }))
        assertEquals(account, store.account)
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun updateRequiredEnrollmentSavesNothing() {
        server.updateRequired = true
        val store = MemoryAccountStore()
        assertEquals(UpdateRequired, enroller(store).enroll("frict://enroll?code=$CODE&host=h&user_id=$USER_ID", UnlockMode.FINGERPRINT_ONLY))
        assertNull(store.account)
    }

    @Test
    fun any426WithoutAppUpdateRequiredFailsAsBefore() {
        server.failWith = 426
        assertEquals(Poll(PollUpdate.Failed("HTTP 426 internal"), DEFAULT_POLL_SECONDS), approvals.poll(shownId = null))
        assertEquals(AnswerResult.Rejected(426, "internal"), approvals.answer(CHALLENGE_ID, Answer.APPROVE, signer))
    }
}
