package app.frict.android

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : ComponentActivity() {
    private var link by mutableStateOf("")
    private var unlockMode by mutableStateOf(UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
    private var account by mutableStateOf<Account?>(null)
    private var status by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var challenge by mutableStateOf<Challenge?>(null)
    private var pollError by mutableStateOf<String?>(null)
    private var alerts by mutableStateOf<List<Alert>>(emptyList())
    private var confirmingRemove by mutableStateOf(false)

    /** [challenge]'s ID, for the account thread. */
    @Volatile
    private var shownId: String? = null

    /** Bumped on every resume and pause; a poll loop runs while its generation is current. */
    private val pollGeneration = AtomicInteger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeLink(intent)
        takeChallenge(intent)
        accountThread.execute {
            val saved = PrefsAccountStore(applicationContext).load()
            runOnUiThread { enrolled(saved) }
        }
        startPush(applicationContext)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val scroll = rememberScrollState()
                    Column(
                        if (account == null) Modifier.padding(24.dp) else Modifier.verticalScroll(scroll).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        account?.let { Enrolled(it) } ?: EnrollForm()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val generation = pollGeneration.incrementAndGet()
        accountThread.execute { poll(generation) }
    }

    override fun onPause() {
        pollGeneration.incrementAndGet()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeLink(intent)
        takeChallenge(intent)
    }

    private fun takeLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.let { link = it }
    }

    /** A login notification's challenge: fetched from the server and shown. */
    private fun takeChallenge(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_CHALLENGE_ID) ?: return
        intent.removeExtra(EXTRA_CHALLENGE_ID)
        accountThread.execute {
            val update = PrefsAccountStore(applicationContext).load()?.let { approvals(it).open(id) }
            update?.let { runOnUiThread { apply(it) } }
        }
    }

    /** Pushes are shown as notifications, so ask for them once there is an account to push to. */
    private fun enrolled(saved: Account?) {
        account = saved
        if (saved != null && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    @Composable
    private fun EnrollForm() {
        Text(stringResource(R.string.not_enrolled))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text(stringResource(R.string.activation_link)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Column(Modifier.selectableGroup().fillMaxWidth()) {
            Text(stringResource(R.string.unlock_with_title), style = MaterialTheme.typography.titleMedium)
            UnlockMode.entries.forEach { UnlockChoice(it) }
        }
        Button(onClick = ::enroll, enabled = !busy && link.isNotBlank()) {
            Text(stringResource(R.string.enroll))
        }
        status?.let { Text(it) }
    }

    @Composable
    private fun UnlockChoice(mode: UnlockMode) {
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = unlockMode == mode, enabled = !busy, role = Role.RadioButton) { unlockMode = mode }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = unlockMode == mode, onClick = null, enabled = !busy)
            Column(Modifier.padding(start = 16.dp)) {
                Text(stringResource(mode.label))
                Text(stringResource(mode.explanation), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun Enrolled(account: Account) {
        Text(stringResource(R.string.enrolled, account.userId, account.host))
        Text(stringResource(R.string.unlocks_with, stringResource(account.unlockMode.label)))
        challenge?.let { ChallengeCard(it) } ?: Text(stringResource(R.string.no_requests))
        status?.let { Text(it) }
        pollError?.let { Text(stringResource(R.string.poll_failed, it)) }
        alerts.forEach { AlertCard(it) }
        OutlinedButton(onClick = { confirmingRemove = true }, enabled = !busy) { Text(stringResource(R.string.remove_phone)) }
        if (confirmingRemove) RemoveDialog()
    }

    @Composable
    private fun RemoveDialog() {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text(stringResource(R.string.remove_phone_title)) },
            text = { Text(stringResource(R.string.remove_phone_explanation)) },
            confirmButton = { TextButton(onClick = ::remove) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    private fun remove() {
        confirmingRemove = false
        busy = true
        accountThread.execute {
            val removed = unenroll(PrefsAccountStore(applicationContext), ::deleteKeys)
            runOnUiThread {
                busy = false
                if (removed) {
                    account = null
                    show(null)
                    alerts = emptyList()
                    pollError = null
                    link = ""
                    status = getString(R.string.removed)
                } else {
                    status = getString(R.string.not_removed)
                }
            }
        }
    }

    @Composable
    private fun AlertCard(a: Alert) {
        Text(stringResource(R.string.alert_title), style = MaterialTheme.typography.titleLarge)
        if (a.username.isNotEmpty()) Text(stringResource(R.string.alert_user, a.username))
        if (a.ipaddr.isNotEmpty()) Text(stringResource(R.string.request_from, a.ipaddr))
        a.pushinfo.forEach { (key, value) -> Text(stringResource(R.string.request_field, key, value)) }
        if (a.type.isNotEmpty()) Text(a.type)
        Text(stringResource(R.string.alert_attempted, formatTime(a.attempted)))
        Text(stringResource(R.string.alert_reported, formatTime(a.reported)))
        AlertLink(stringResource(R.string.reset_password), a.resetPasswordUrl)
        AlertLink(stringResource(R.string.revoke_sessions), a.revokeSessionsUrl)
    }

    @Composable
    private fun AlertLink(label: String, url: String) {
        val openable = openableLink(url)
        if (openable == null) {
            Text(stringResource(R.string.link_not_opened, label, url))
            return
        }
        Text(
            label,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { openInBrowser(openable) },
        )
    }

    private fun openInBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            status = getString(R.string.no_browser, url)
        }
    }

    @Composable
    private fun ChallengeCard(c: Challenge) {
        Text(stringResource(R.string.sign_in_request), style = MaterialTheme.typography.titleLarge)
        if (c.username.isNotEmpty()) Text(stringResource(R.string.request_user, c.username))
        if (c.ipaddr.isNotEmpty()) Text(stringResource(R.string.request_from, c.ipaddr))
        c.pushinfo.forEach { (key, value) -> Text(stringResource(R.string.request_field, key, value)) }
        if (c.type.isNotEmpty()) Text(c.type)
        Button(onClick = { confirm(c, Answer.APPROVE) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.approve))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { confirm(c, Answer.DENY) }, enabled = !busy) { Text(stringResource(R.string.deny)) }
            OutlinedButton(onClick = { confirm(c, Answer.NOT_ME) }, enabled = !busy) { Text(stringResource(R.string.not_me)) }
        }
    }

    private fun show(c: Challenge?) {
        challenge = c
        shownId = c?.id
    }

    /** On the account thread: one poll, then the next after the server's interval. */
    private fun poll(generation: Int) {
        if (generation != pollGeneration.get()) return
        val result = PrefsAccountStore(applicationContext).load()?.let { approvals(it).poll(shownId) }
        result?.let {
            runOnUiThread {
                apply(it.update)
                it.alerts?.let { shown -> alerts = shown }
            }
        }
        accountThread.schedule({ poll(generation) }, result?.nextSeconds ?: DEFAULT_POLL_SECONDS, TimeUnit.SECONDS)
    }

    private fun apply(update: PollUpdate) {
        if (update !is PollUpdate.Failed) pollError = null
        when (update) {
            is PollUpdate.Show -> if (challenge == null) {
                show(update.challenge)
                status = null
            }
            is PollUpdate.Withdrawn -> if (challenge?.id == update.id && !busy) {
                show(null)
                status = getString(R.string.request_withdrawn)
            }
            PollUpdate.NoChange -> Unit
            is PollUpdate.Failed -> pollError = update.message
        }
    }

    /** Asks for the account's unlock, bound to the approval_key, then sends [answer] signed with it. */
    private fun confirm(c: Challenge, answer: Answer) {
        val mode = account?.unlockMode ?: return
        val signature = try {
            approvalSignature()
        } catch (e: Exception) {
            status = getString(R.string.approval_key_unavailable, e.message ?: e.javaClass.simpleName)
            return
        }
        busy = true
        status = null
        val title = when (answer) {
            Answer.APPROVE -> R.string.confirm_approve
            Answer.DENY -> R.string.confirm_deny
            Answer.NOT_ME -> R.string.confirm_not_me
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(title))
            .setSubtitle(c.username)
            .setAllowedAuthenticators(mode.promptAuthenticators)
        if (mode.promptHasBackButton) prompt.setNegativeButton(getString(R.string.back), mainExecutor) { _, _ -> busy = false }
        prompt.build()
            .authenticate(
                BiometricPrompt.CryptoObject(signature),
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authenticated = result.cryptoObject?.signature
                        if (authenticated == null) {
                            busy = false
                            status = getString(R.string.approval_key_unavailable, "no signature in the prompt's result")
                            return
                        }
                        send(c, answer, authenticated.asSigner())
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        busy = false
                        status = errString.toString()
                    }
                },
            )
    }

    private fun send(c: Challenge, answer: Answer, approvalSigner: Signer) {
        val current = account ?: return
        accountThread.execute {
            val result = approvals(current).answer(c.id, answer, approvalSigner)
            runOnUiThread {
                busy = false
                if (challenge?.id == c.id) show(null)
                status = describe(result)
            }
        }
    }

    private fun describe(result: AnswerResult): String = when (result) {
        is AnswerResult.Answered -> getString(
            when (result.answer) {
                Answer.APPROVE -> R.string.approved
                Answer.DENY -> R.string.denied
                Answer.NOT_ME -> R.string.reported
            },
        )
        AnswerResult.NotPending -> getString(R.string.not_pending)
        AnswerResult.NotFound -> getString(R.string.not_found)
        is AnswerResult.Rejected -> getString(R.string.answer_rejected, result.status, result.error)
        is AnswerResult.NetworkError -> getString(R.string.network_error, result.message)
    }

    private fun enroll() {
        val mode = unlockMode
        enrollBlocker(mode, isDeviceSecure(this), hasStrongBiometric(this))?.let {
            status = getString(it)
            return
        }
        busy = true
        status = null
        val target = link
        val enroller = Enroller(
            keys = KeystoreKeyMaker(packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)),
            store = PrefsAccountStore(applicationContext),
            api = { host -> DeviceApi("https://$host") },
            fid = { currentFid(applicationContext) ?: NO_FID },
            unixSeconds = { System.currentTimeMillis() / 1000 },
        )
        accountThread.execute {
            val result = enroller.enroll(target, mode)
            runOnUiThread {
                busy = false
                when (result) {
                    is EnrollResult.Enrolled -> enrolled(result.account)
                    else -> status = describe(result)
                }
            }
        }
    }

    private fun describe(result: EnrollResult): String = when (result) {
        is EnrollResult.Enrolled -> getString(R.string.enrolled, result.account.userId, result.account.host)
        EnrollResult.AlreadyEnrolled -> getString(R.string.already_enrolled)
        EnrollResult.NotAnActivationLink -> getString(R.string.not_an_activation_link)
        is EnrollResult.KeysUnavailable -> getString(R.string.keys_unavailable, result.message)
        is EnrollResult.Rejected -> getString(R.string.enroll_rejected, result.status)
        is EnrollResult.NetworkError -> getString(R.string.network_error, result.message)
        EnrollResult.NotSaved -> getString(R.string.not_saved)
    }
}

private val UnlockMode.label
    get() = when (this) {
        UnlockMode.FINGERPRINT_OR_SCREEN_LOCK -> R.string.unlock_fingerprint_or_screen_lock
        UnlockMode.FINGERPRINT_ONLY -> R.string.unlock_fingerprint_only
    }

private val UnlockMode.explanation
    get() = when (this) {
        UnlockMode.FINGERPRINT_OR_SCREEN_LOCK -> R.string.unlock_fingerprint_or_screen_lock_explanation
        UnlockMode.FINGERPRINT_ONLY -> R.string.unlock_fingerprint_only_explanation
    }

private fun isDeviceSecure(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

private fun hasStrongBiometric(context: Context): Boolean =
    context.getSystemService(BiometricManager::class.java)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

private val timeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

private fun formatTime(unixSeconds: Long): String = timeFormat.format(Instant.ofEpochSecond(unixSeconds))
