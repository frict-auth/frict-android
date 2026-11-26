package app.frict.android

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val OWN = "DUABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val ADMIN = "DU234567234567234567234567AB"
private const val OWN_CHALLENGE = "AAAAAAAAAAAAAAAAAAAAAA"
private const val ADMIN_CHALLENGE = "BBBBBBBBBBBBBBBBBBBBBB"
private const val NOW = 1762345678L

/** One server holding one enrolled user, and that user's account and keys on this phone. */
private class Enrolled(userId: String, host: String) : AutoCloseable {
    val server = FakeFrictServer(userId, "unused")
    val account = testAccount(userId, host)
    val device = p256()
    val approval = p256()
    val approvals = Approvals(account, DeviceApi(server.url), signerOf(device), { NOW })

    init {
        server.enrolled(device.public, approval.public)
    }

    override fun close() = server.close()
}

private fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

private fun signerOf(kp: KeyPair) = Signer { data ->
    Signature.getInstance("SHA256withECDSA").run {
        initSign(kp.private)
        update(data)
        sign()
    }
}

/** Two accounts on two servers: the operator's own and an admin account. */
class MultiAccountTest {
    private val own = Enrolled(OWN, "own.frict.test")
    private val admin = Enrolled(ADMIN, "admin.frict.test")
    private val accounts = listOf(own.account, admin.account)
    private val approvals = { a: Account -> listOf(own, admin).single { it.account == a }.approvals }

    @After
    fun close() {
        own.close()
        admin.close()
    }

    private fun requests(e: Enrolled) = e.server.requests.map { it.path }

    @Test
    fun aPushedChallengeIsAnsweredByTheAccountWhoseServerHasIt() {
        admin.server.addChallenge(ADMIN_CHALLENGE, username = "admin")
        val owned = findOwner(ADMIN_CHALLENGE, accounts, approvals)!!
        assertEquals(admin.account, owned.account)
        assertEquals("admin", owned.challenge.username)
        val answered = approvals(owned.account).answer(ADMIN_CHALLENGE, Answer.APPROVE, signerOf(admin.approval))
        assertEquals(AnswerResult.Answered(Answer.APPROVE), answered)
        assertEquals(ChallengeState.APPROVED, admin.server.challenges[ADMIN_CHALLENGE])
        assertEquals(listOf(CHALLENGE_PATH), requests(own))
        assertEquals(listOf(CHALLENGE_PATH, ANSWER_PATH), requests(admin))
    }

    @Test
    fun aChallengeNoAccountsServerHasIsDropped() {
        assertNull(findOwner(OWN_CHALLENGE, accounts, approvals))
        assertEquals(listOf(CHALLENGE_PATH), requests(own))
        assertEquals(listOf(CHALLENGE_PATH), requests(admin))
    }

    @Test
    fun anAccountWithoutItsDeviceKeyDoesNotStopTheLookup() {
        admin.server.addChallenge(ADMIN_CHALLENGE)
        val lost = Approvals(own.account, DeviceApi(own.server.url), { throw KeyLostException(LostKey.DEVICE_KEY) }, { NOW })
        val owned = findOwner(ADMIN_CHALLENGE, accounts) { if (it == own.account) lost else admin.approvals }
        assertEquals(admin.account, owned?.account)
    }

    @Test
    fun eachAccountPollsItsOwnServer() {
        own.server.addChallenge(OWN_CHALLENGE, username = "operator")
        admin.server.addChallenge(ADMIN_CHALLENGE, username = "admin")
        admin.server.pollInterval = 3
        val polls = pollAll(accounts, emptyMap(), approvals).toMap()
        assertEquals("operator", ((polls.getValue(own.account).update) as PollUpdate.Show).challenge.username)
        assertEquals("admin", ((polls.getValue(admin.account).update) as PollUpdate.Show).challenge.username)
        assertEquals(3L, polls.getValue(admin.account).nextSeconds)
    }

    @Test
    fun aChallengeShownForOneAccountIsWithdrawnOnlyThere() {
        own.server.addChallenge(OWN_CHALLENGE)
        admin.server.addChallenge(ADMIN_CHALLENGE, state = ChallengeState.APPROVED)
        val shown = mapOf(own.account to OWN_CHALLENGE, admin.account to ADMIN_CHALLENGE)
        val polls = pollAll(accounts, shown, approvals).toMap()
        assertEquals(PollUpdate.NoChange, polls.getValue(own.account).update)
        assertEquals(PollUpdate.Withdrawn(ADMIN_CHALLENGE), polls.getValue(admin.account).update)
    }

    @Test
    fun anotherAccountsApprovalKeyCannotAnswer() {
        admin.server.addChallenge(ADMIN_CHALLENGE)
        val wrongKey = admin.approvals.answer(ADMIN_CHALLENGE, Answer.APPROVE, signerOf(own.approval))
        assertEquals(AnswerResult.Rejected(401, "unauthorized"), wrongKey)
        assertEquals(ChallengeState.PENDING, admin.server.challenges[ADMIN_CHALLENGE])
    }

    @Test
    fun theFidGoesToEveryAccountsServer() {
        val lost = Approvals(own.account, DeviceApi(own.server.url), { throw KeyLostException(LostKey.DEVICE_KEY) }, { NOW })
        val results = sendFidToAll("fid-2", accounts) { if (it == own.account) lost else admin.approvals }
        assertEquals("not sent: the device_key is gone", results[own.account])
        assertEquals("fid-2", admin.server.fid)
        sendFidToAll("fid-3", accounts, approvals)
        assertEquals("fid-3", own.server.fid)
        assertEquals("fid-3", admin.server.fid)
    }
}
