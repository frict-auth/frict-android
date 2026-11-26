package app.frict.android

import java.security.KeyPair
import java.security.Signature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val USER_ID = "DUABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val CODE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val NOW = 1762345678L
private const val LINK = "frict://enroll?code=$CODE&host=api.frict.test:8443&user_id=$USER_ID"

class AccountLabelTest {
    private val keys = SoftwareKeyMaker()
    private val store = MemoryAccountStore()
    private val servers = mutableListOf<FakeFrictServer>()

    @After
    fun close() = servers.forEach { it.close() }

    private fun enroll(username: String? = "alice"): Pair<FakeFrictServer, Account> {
        val server = FakeFrictServer(USER_ID, CODE, username).also { servers += it }
        val enroller = Enroller(keys, {}, store, { DeviceApi(server.url) }, { "fid-1" }, { NOW })
        val result = enroller.enroll(LINK, UnlockMode.FINGERPRINT_ONLY)
        return server to (result as EnrollResult.Enrolled).account
    }

    @Test
    fun theDefaultLabelIsTheServersUsernameAtTheLinksHostname() {
        val (_, account) = enroll("alice")
        assertEquals("alice @ api.frict.test", account.label)
        assertEquals(listOf(account), store.accounts)
        assertEquals("bob @ auth.example.org", defaultLabel("bob", "auth.example.org:443", USER_ID))
    }

    @Test
    fun aServerThatSendsNoUsernameGetsTheHostnameAndShortId() {
        assertEquals("api.frict.test · DUABCDEF", enroll(username = null).second.label)
        assertEquals("api.frict.test · DUABCDEF", defaultLabel(" ", "api.frict.test", USER_ID))
    }

    @Test
    fun anEditedLabelIsSaved() {
        val (_, account) = enroll()
        val named = nameAccount(account, "  Work admin  ", store)
        assertEquals("Work admin", named?.label)
        assertEquals(listOf(named), store.accounts)
        assertEquals("Work admin", named?.name)
    }

    @Test
    fun anEmptyLabelFallsBackToTheDefault() {
        val (_, account) = enroll()
        for (edited in listOf("", "   ")) {
            assertEquals("alice @ api.frict.test", nameAccount(account, edited, store)?.label)
        }
        assertEquals("alice @ api.frict.test", store.accounts.single().label)
    }

    @Test
    fun labelsAreCappedWithoutSplittingACharacter() {
        val long = "a".repeat(MAX_LABEL_LENGTH - 1) + "🔑" + "tail"
        val capped = chosenLabel(long, "default")
        assertEquals(MAX_LABEL_LENGTH, capped.codePointCount(0, capped.length))
        assertTrue(capped.endsWith("🔑"))
        assertEquals(MAX_LABEL_LENGTH, chosenLabel("b".repeat(500), "default").length)
        val longUser = defaultLabel("u".repeat(200), "api.frict.test", USER_ID)
        assertEquals(MAX_LABEL_LENGTH, longUser.length)
    }

    @Test
    fun aLabelThatCannotBeSavedReturnsNull() {
        val (_, account) = enroll()
        store.writesSucceed = false
        assertNull(nameAccount(account, "Work admin", store))
        assertEquals("alice @ api.frict.test", store.accounts.single().label)
    }

    @Test
    fun anAccountEnrolledBeforeLabelsShowsItsHostnameAndShortId() {
        val legacy = Account("api.frict.test:8443", USER_ID, UnlockMode.FINGERPRINT_ONLY, KeyAliases.LEGACY)
        assertNull(legacy.label)
        assertEquals("api.frict.test · DUABCDEF", legacy.name)
        assertEquals(legacy, accountsFromJson(accountsToJson(listOf(legacy))).single())
        assertFalse(accountsToJson(listOf(legacy)).contains("label"))
    }

    @Test
    fun theLabelIsNeverSentToTheServer() {
        val (server, enrolled) = enroll()
        val account = nameAccount(enrolled, "Label-7Q9 desk", store)!!
        server.addChallenge("AAAAAAAAAAAAAAAAAAAAAA")
        val approvals = Approvals(account, DeviceApi(server.url), signerOf(keys.device), { NOW })
        approvals.poll(null)
        approvals.answer("AAAAAAAAAAAAAAAAAAAAAA", Answer.APPROVE, signerOf(keys.approval))
        approvals.sendFid("fid-2")
        approvals.remove()
        val paths = server.requests.map { it.path }
        assertEquals(listOf(ENROLL_PATH, PENDING_PATH, CHALLENGE_PATH, ANSWER_PATH, FID_PATH, REMOVE_PATH), paths)
        for (r in server.requests) {
            val sent = r.authorization + String(r.body, Charsets.UTF_8)
            for (label in listOf("Label-7Q9", "alice @ ", " · ")) assertFalse("${r.path} sent $sent", sent.contains(label))
        }
    }

    private fun signerOf(kp: KeyPair) = Signer { data ->
        Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(data)
            sign()
        }
    }
}
