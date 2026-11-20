package app.frict.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One thread for every account operation, so no two touch the keys or the store at once. */
private val accountThread: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

class MainActivity : ComponentActivity() {
    private var link by mutableStateOf("")
    private var account by mutableStateOf<Account?>(null)
    private var status by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var challenge by mutableStateOf<Challenge?>(null)
    private var pollError by mutableStateOf<String?>(null)

    /** [challenge]'s ID, for the account thread. */
    @Volatile
    private var shownId: String? = null

    /** Bumped on every resume and pause; a poll loop runs while its generation is current. */
    private val pollGeneration = AtomicInteger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeLink(intent)
        accountThread.execute {
            val saved = PrefsAccountStore(applicationContext).load()
            runOnUiThread { account = saved }
        }
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(24.dp),
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
    }

    private fun takeLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.let { link = it }
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
        Button(onClick = ::enroll, enabled = !busy && link.isNotBlank()) {
            Text(stringResource(R.string.enroll))
        }
        status?.let { Text(it) }
    }

    @Composable
    private fun Enrolled(account: Account) {
        Text(stringResource(R.string.enrolled, account.userId, account.host))
        challenge?.let { ChallengeCard(it) } ?: Text(stringResource(R.string.no_requests))
        status?.let { Text(it) }
        pollError?.let { Text(stringResource(R.string.poll_failed, it)) }
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

    private fun approvals(account: Account) = Approvals(
        account = account,
        api = DeviceApi("https://${account.host}"),
        deviceSigner = keystoreSigner(DEVICE_KEY_ALIAS),
        unixSeconds = { System.currentTimeMillis() / 1000 },
    )

    /** On the account thread: one poll, then the next after the server's interval. */
    private fun poll(generation: Int) {
        if (generation != pollGeneration.get()) return
        val result = PrefsAccountStore(applicationContext).load()?.let { approvals(it).poll(shownId) }
        result?.let { runOnUiThread { apply(it.update) } }
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

    /** Asks for a strong biometric bound to the approval_key, then sends [answer] signed with it. */
    private fun confirm(c: Challenge, answer: Answer) {
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
        BiometricPrompt.Builder(this)
            .setTitle(getString(title))
            .setSubtitle(c.username)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButton(getString(R.string.back), mainExecutor) { _, _ -> busy = false }
            .build()
            .authenticate(
                BiometricPrompt.CryptoObject(signature),
                CancellationSignal(),
                mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        send(c, answer, signature.asSigner())
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
        if (!hasStrongBiometric(this)) {
            status = getString(R.string.needs_biometric)
            return
        }
        busy = true
        status = null
        val target = link
        val enroller = Enroller(
            keys = KeystoreKeyMaker(packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)),
            store = PrefsAccountStore(applicationContext),
            api = { host -> DeviceApi("https://$host") },
            fid = { NO_FID_YET },
            unixSeconds = { System.currentTimeMillis() / 1000 },
        )
        accountThread.execute {
            val result = enroller.enroll(target)
            runOnUiThread {
                busy = false
                when (result) {
                    is EnrollResult.Enrolled -> account = result.account
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

/** approval_key can only be created once a strong biometric is enrolled. */
private fun hasStrongBiometric(context: Context): Boolean =
    context.getSystemService(BiometricManager::class.java)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
