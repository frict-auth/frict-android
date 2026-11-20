package app.frict.android

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val USER_ID = "DUABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val NOW = 1762345678L
private const val FIRST = "AAAAAAAAAAAAAAAAAAAAAA"
private const val SECOND = "BBBBBBBBBBBBBBBBBBBBBB"

private fun p256(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

private fun signerOf(keys: KeyPair) = Signer { data ->
    Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private)
        update(data)
        sign()
    }
}

private fun RecordedRequest.verifiesWith(key: PublicKey): Boolean {
    val (user, unix, sig) = authorization.removePrefix("Frict-P256 ").split(':')
    return Signature.getInstance("SHA256withECDSA").run {
        initVerify(key)
        update(signedBytes(user, unix.toLong(), path, body))
        verify(b64url(sig))
    }
}

class ApprovalsTest {
    private val server = FakeFrictServer(USER_ID, "unused")
    private val device = p256()
    private val approval = p256()
    private val approvals = Approvals(Account("api.frict.test", USER_ID), DeviceApi(server.url), signerOf(device), { NOW })

    init {
        server.enrolled(device.public, approval.public)
    }

    @After
    fun close() = server.close()

    @Test
    fun pollShowsTheOldestPendingChallengeWithItsDetails() {
        server.addChallenge(FIRST, username = "geoffrey")
        server.addChallenge(SECOND, username = "someone")
        server.pollInterval = 7
        val expected = Challenge(
            id = FIRST,
            username = "geoffrey",
            ipaddr = "203.0.113.7",
            pushinfo = listOf("Domain" to "auth.example", "Application" to "Frict test"),
            type = "authentik Login request",
            created = 1762345678,
            expires = 1762345738,
        )
        assertEquals(Poll(PollUpdate.Show(expected), 7, emptyList()), approvals.poll(shownId = null))
        assertEquals(listOf(PENDING_PATH, CHALLENGE_PATH), server.requests.map { it.path })
        assertEquals(FIRST, server.requests[1].json.getString("challenge_id"))
    }

    @Test
    fun pollAndFetchAreSignedWithTheDeviceKey() {
        server.addChallenge(FIRST)
        approvals.poll(shownId = null)
        assertEquals(2, server.requests.size)
        for (r in server.requests) {
            assertTrue(r.verifiesWith(device.public))
            assertFalse(r.verifiesWith(approval.public))
        }
        assertEquals("{}", String(server.requests[0].body))
    }

    @Test
    fun nothingPendingIsNoChange() {
        assertEquals(Poll(PollUpdate.NoChange, 5, emptyList()), approvals.poll(shownId = null))
    }

    @Test
    fun shownChallengeStaysWhilePendingWithoutRefetching() {
        server.addChallenge(FIRST)
        assertEquals(PollUpdate.NoChange, approvals.poll(shownId = FIRST).update)
        assertEquals(listOf(PENDING_PATH), server.requests.map { it.path })
    }

    @Test
    fun shownChallengeIsWithdrawnOnceNotPending() {
        server.addChallenge(FIRST, state = ChallengeState.EXPIRED)
        server.addChallenge(SECOND)
        assertEquals(PollUpdate.Withdrawn(FIRST), approvals.poll(shownId = FIRST).update)
    }

    @Test
    fun failedPollSaysWhyAndWaitsTheDefault() {
        server.enrolled(p256().public, approval.public)
        server.addAlert(FIRST)
        assertEquals(Poll(PollUpdate.Failed("HTTP 401 unauthorized"), DEFAULT_POLL_SECONDS, null), approvals.poll(shownId = null))
    }

    @Test
    fun unreachableServerFailsThePoll() {
        server.close()
        assertTrue(approvals.poll(shownId = null).update is PollUpdate.Failed)
    }

    @Test
    fun pollListsAlertsOldestFirstFromTheDeviceKeySignedPending() {
        server.addAlert(FIRST, username = "geoffrey")
        server.addAlert(SECOND, username = "someone")
        val url = "https://auth.example/if/admin/#/identity/users;%7B%22ak-user-list-search%22:%22geoffrey%22%7D"
        val expected = Alert(
            id = FIRST,
            username = "geoffrey",
            ipaddr = "203.0.113.7",
            pushinfo = listOf("Domain" to "auth.example", "Application" to "Frict test"),
            type = "authentik Login request",
            attempted = 1762345678,
            reported = 1762345690,
            resetPasswordUrl = url,
            revokeSessionsUrl = url,
        )
        val alerts = approvals.poll(shownId = null).alerts
        assertEquals(listOf(FIRST, SECOND), alerts?.map { it.id })
        assertEquals(expected, alerts?.first())
        val r = server.requests.single()
        assertEquals(PENDING_PATH, r.path)
        assertTrue(r.verifiesWith(device.public))
        assertFalse(r.verifiesWith(approval.public))
    }

    @Test
    fun alertsComeWithChallengesInTheSamePoll() {
        server.addChallenge(FIRST)
        server.addAlert(SECOND)
        val poll = approvals.poll(shownId = FIRST)
        assertEquals(PollUpdate.NoChange, poll.update)
        assertEquals(listOf(SECOND), poll.alerts?.map { it.id })
    }

    @Test
    fun alertsWithoutAnIdAreSkipped() {
        server.addAlert(FIRST)
        server.alerts.put(JSONObject().put("username", "nobody")).put("not an object")
        assertEquals(listOf(FIRST), approvals.poll(shownId = null).alerts?.map { it.id })
    }

    @Test
    fun approveAnswersThatChallengeSignedWithTheApprovalKey() {
        server.addChallenge(FIRST)
        server.addChallenge(SECOND)
        assertEquals(AnswerResult.Answered(Answer.APPROVE), approvals.answer(FIRST, Answer.APPROVE, signerOf(approval)))
        assertEquals(mapOf(FIRST to ChallengeState.APPROVED, SECOND to ChallengeState.PENDING), server.challenges)
        val r = server.requests.single()
        assertEquals(ANSWER_PATH, r.path)
        assertTrue(r.verifiesWith(approval.public))
        assertFalse(r.verifiesWith(device.public))
        assertEquals(setOf("challenge_id", "decision"), r.json.fields())
        assertEquals("approve", r.json.getString("decision"))
    }

    @Test
    fun denyIsACancel() {
        server.addChallenge(FIRST)
        assertEquals(AnswerResult.Answered(Answer.DENY), approvals.answer(FIRST, Answer.DENY, signerOf(approval)))
        assertEquals(ChallengeState.DENIED_CANCEL, server.challenges[FIRST])
        with(server.requests.single().json) {
            assertEquals("deny", getString("decision"))
            assertEquals("cancel", getString("reason"))
        }
    }

    @Test
    fun notMeIsAFraudDeny() {
        server.addChallenge(FIRST)
        assertEquals(AnswerResult.Answered(Answer.NOT_ME), approvals.answer(FIRST, Answer.NOT_ME, signerOf(approval)))
        assertEquals(ChallengeState.DENIED_NOT_ME, server.challenges[FIRST])
        with(server.requests.single().json) {
            assertEquals("deny", getString("decision"))
            assertEquals("not_me", getString("reason"))
        }
    }

    @Test
    fun answerSignedWithTheDeviceKeyIsRefused() {
        server.addChallenge(FIRST)
        assertEquals(AnswerResult.Rejected(401, "unauthorized"), approvals.answer(FIRST, Answer.APPROVE, signerOf(device)))
        assertEquals(ChallengeState.PENDING, server.challenges[FIRST])
    }

    @Test
    fun expiredOrAnsweredChallengeIsNotPendingAndNotRetried() {
        server.addChallenge(FIRST, state = ChallengeState.EXPIRED)
        assertEquals(AnswerResult.NotPending, approvals.answer(FIRST, Answer.APPROVE, signerOf(approval)))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun unknownChallengeIsNotFound() {
        assertEquals(AnswerResult.NotFound, approvals.answer(FIRST, Answer.APPROVE, signerOf(approval)))
    }

    @Test
    fun pushinfoIsDecoded() {
        assertEquals(listOf("Domain" to "a b", "Application" to "x&y"), parsePushinfo("Domain=a+b&Application=x%26y"))
        assertEquals(emptyList<Pair<String, String>>(), parsePushinfo(""))
    }

    @Test
    fun fidIsSentSignedWithTheDeviceKey() {
        assertEquals(FidResult.Sent, approvals.sendFid("fid-from-firebase"))
        assertEquals("fid-from-firebase", server.fid)
        val request = server.requests.single()
        assertEquals(FID_PATH, request.path)
        assertEquals(setOf("fid"), request.json.fields())
        assertTrue(request.verifiesWith(device.public))
        assertFalse(request.verifiesWith(approval.public))
    }

    @Test
    fun refusedFidSaysWhy() {
        assertEquals(FidResult.Rejected(400, "bad_request"), approvals.sendFid(""))
        assertEquals(null, server.fid)
    }

    @Test
    fun openFetchesThePushedChallengeFromTheServer() {
        server.addChallenge(FIRST, username = "geoffrey")
        server.addChallenge(SECOND, username = "someone")
        val update = approvals.open(SECOND)
        assertEquals("someone", (update as PollUpdate.Show).challenge.username)
        assertEquals("203.0.113.7", update.challenge.ipaddr)
        assertEquals(listOf("Domain" to "auth.example", "Application" to "Frict test"), update.challenge.pushinfo)
        val request = server.requests.single()
        assertEquals(CHALLENGE_PATH, request.path)
        assertEquals(SECOND, request.json.getString("challenge_id"))
        assertTrue(request.verifiesWith(device.public))
    }

    @Test
    fun openingAChallengeNoLongerPendingShowsNothing() {
        server.addChallenge(FIRST, state = ChallengeState.APPROVED)
        assertEquals(PollUpdate.NoChange, approvals.open(FIRST))
    }
}
