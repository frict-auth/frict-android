package app.frict.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One account on screen. */
private class AccountUi(val account: Account) {
    var challenge by mutableStateOf<Challenge?>(null)
    var status by mutableStateOf<String?>(null)
    var pollError by mutableStateOf<String?>(null)

    /** Set once Keystore lost one of this account's keys: the only way on is removing the account. */
    var keyLost by mutableStateOf<LostKey?>(null)

    /** This account's server answered a request with 426. */
    var updateRequired by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    private var link by mutableStateOf("")
    private var unlockMode by mutableStateOf(UnlockMode.FINGERPRINT_OR_SCREEN_LOCK)
    private val accounts = mutableStateListOf<AccountUi>()

    /** Showing the enrollment form while there are accounts. */
    private var adding by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var confirmingRemove by mutableStateOf<AccountUi?>(null)

    /** An account the server could not be told to remove, and why, while offering to remove it anyway. */
    private var serverNotTold by mutableStateOf<Pair<AccountUi, String>?>(null)
    private var scanning by mutableStateOf(false)

    /** An enrollment was answered with 426. */
    private var enrollUpdateRequired by mutableStateOf(false)

    /** The account just enrolled, saved with its default label, while the person enrolling names it. */
    private var naming by mutableStateOf<Account?>(null)
    private var labelDraft by mutableStateOf("")

    // The check is for FragmentActivity, whose versions before fragment 1.3.0 lose results;
    // this is a ComponentActivity, and fragment 1.1.0 is on the classpath only through Firebase.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else status = getString(R.string.camera_refused)
    }

    /** The ID of the challenge on screen for each account, for the account thread. */
    private val shownIds = ConcurrentHashMap<Account, String>()

    /** Bumped on every resume and pause; a poll loop runs while its generation is current. */
    private val pollGeneration = AtomicInteger()

    /** The generation whose poll loop is running, if any; only the account thread touches it. */
    private var pollingGeneration: Int? = null
    private var resumed = false

    private val store get() = PrefsAccountStore(applicationContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeLink(intent)
        takeChallenge(intent)
        accountThread.execute {
            val saved = store.load()
            runOnUiThread { loaded(saved) }
        }
        deleteRetiredChannels(applicationContext)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val scroll = rememberScrollState()
                    val form = accounts.isEmpty() || adding || naming != null
                    Column(
                        if (form) Modifier.padding(24.dp) else Modifier.verticalScroll(scroll).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (enrollUpdateRequired || accounts.any { it.updateRequired }) UpdatePrompt()
                        val named = naming
                        when {
                            named != null -> NameForm(named)
                            form -> EnrollForm()
                            else -> AccountList()
                        }
                        TextButton(onClick = ::openPrivacyPolicy) { Text(stringResource(R.string.privacy_policy)) }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        pollGeneration.incrementAndGet()
        accountThread.execute { checkApprovalKeys() }
        startPolling()
    }

    /** Starts the poll loop of the current generation unless it is running. */
    private fun startPolling() = accountThread.execute {
        val generation = pollGeneration.get()
        if (pollingGeneration == generation) return@execute
        pollingGeneration = generation
        poll(generation)
    }

    /** On the account thread: whether Keystore still holds a usable approval_key for each account. */
    private fun checkApprovalKeys() {
        for (account in store.load()) {
            val lost = try {
                lostApprovalKey(account.keys.approval)
            } catch (e: Exception) {
                Log.w(TAG, "could not check the approval key of ${account.userId}", e)
                null
            }
            lost?.let { runOnUiThread { lose(account, it) } }
        }
    }

    private fun ui(account: Account) = accounts.find { it.account == account }

    private fun lose(account: Account, key: LostKey) {
        val ui = ui(account) ?: return
        ui.keyLost = maxOf(key, ui.keyLost ?: key)
        show(ui, null)
    }

    @Composable
    private fun UpdatePrompt() {
        Text(stringResource(R.string.update_required))
        Button(onClick = ::openPlayListing) { Text(stringResource(R.string.update_frict)) }
    }

    private fun openPlayListing() {
        val listing = "https://play.google.com/store/apps/details?id=$packageName".toUri()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, listing))
        } catch (_: ActivityNotFoundException) {
            status = getString(R.string.no_play_store)
        }
    }

    private fun openPrivacyPolicy() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, BuildConfig.PRIVACY_POLICY_URL.toUri()))
        } catch (_: ActivityNotFoundException) {
            status = getString(R.string.no_browser, BuildConfig.PRIVACY_POLICY_URL)
        }
    }

    override fun onPause() {
        resumed = false
        pollGeneration.incrementAndGet()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeLink(intent)
        takeChallenge(intent)
    }

    private fun takeLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.dataString?.let {
            link = it
            adding = true
        }
    }

    /** A login notification's challenge: shown on the account it names. */
    private fun takeChallenge(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_CHALLENGE_ID) ?: return
        val userId = intent.getStringExtra(EXTRA_USER_ID)
        intent.removeExtra(EXTRA_CHALLENGE_ID)
        intent.removeExtra(EXTRA_USER_ID)
        userId ?: return
        accountThread.execute {
            val owned = pushedChallenge(LoginPush(id, userId), store.load(), ::approvals) ?: return@execute
            runOnUiThread { apply(owned.account, PollUpdate.Show(owned.challenge)) }
        }
    }

    private fun loaded(saved: List<Account>) {
        accounts.clear()
        accounts.addAll(saved.map(::AccountUi))
        if (saved.isNotEmpty()) pushToAccounts()
    }

    /** Pushes are shown as notifications, so ask for them once there is an account to push to. */
    private fun pushToAccounts() {
        startPush(applicationContext)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    @Composable
    private fun EnrollForm() {
        Text(stringResource(if (accounts.isEmpty()) R.string.not_enrolled else R.string.add_account))
        if (scanning) {
            Scanner()
            return
        }
        OutlinedButton(onClick = ::scan, enabled = !busy) { Text(stringResource(R.string.scan_qr_code)) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (accounts.isNotEmpty()) OutlinedButton(onClick = ::stopAdding, enabled = !busy) { Text(stringResource(R.string.cancel)) }
            Button(onClick = ::enroll, enabled = !busy && link.isNotBlank()) { Text(stringResource(R.string.enroll)) }
        }
        status?.let { Text(it) }
    }

    @Composable
    private fun NameForm(account: Account) {
        Text(stringResource(R.string.name_account_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.name_account_explanation))
        OutlinedTextField(
            value = labelDraft,
            onValueChange = { labelDraft = capLabel(it) },
            label = { Text(stringResource(R.string.account_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { name(account) }, enabled = !busy) { Text(stringResource(R.string.save)) }
    }

    private fun name(account: Account) {
        busy = true
        val edited = labelDraft
        accountThread.execute {
            val named = nameAccount(account, edited, store)
            runOnUiThread {
                busy = false
                naming = null
                enrolled(named ?: account)
                if (named == null) status = getString(R.string.label_not_saved, account.name)
            }
        }
    }

    private fun stopAdding() {
        adding = false
        link = ""
        status = null
    }

    @Composable
    private fun Scanner() {
        QrScanner(
            activity = this,
            onLink = {
                scanning = false
                link = it
                status = getString(R.string.scanned)
            },
            onNotAnActivationLink = { status = getString(R.string.scanned_not_an_activation_link) },
            onCameraError = {
                scanning = false
                status = getString(R.string.camera_unavailable, it)
            },
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
        OutlinedButton(onClick = { scanning = false }) { Text(stringResource(R.string.stop_scanning)) }
        status?.let { Text(it) }
    }

    /** The camera permission is asked for here, when the user first wants to scan. */
    private fun scan() {
        status = null
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanning = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
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
    private fun AccountList() {
        for (ui in accounts) {
            val c = ui.challenge ?: continue
            if (ui.keyLost != null) continue
            ChallengeCard(ui, c)
            HorizontalDivider()
        }
        accounts.forEach {
            AccountSection(it)
            HorizontalDivider()
        }
        status?.let { Text(it) }
        OutlinedButton(onClick = { adding = true }, enabled = !busy) { Text(stringResource(R.string.add_account)) }
        confirmingRemove?.let { RemoveDialog(it) }
        serverNotTold?.let { (ui, reason) -> RemoveAnywayDialog(ui, reason) }
    }

    @Composable
    private fun AccountSection(ui: AccountUi) {
        val account = ui.account
        Text(account.name, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.unlocks_with, stringResource(account.unlockMode.label)))
        val lost = ui.keyLost
        if (lost != null) {
            Text(stringResource(R.string.key_lost_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(lost.explanation(account.unlockMode)))
        } else if (ui.challenge == null) {
            Text(stringResource(R.string.no_requests))
        }
        ui.status?.let { Text(it) }
        if (lost == null) ui.pollError?.let { Text(stringResource(R.string.poll_failed, it)) }
        OutlinedButton(onClick = { confirmingRemove = ui }, enabled = !busy) { Text(stringResource(R.string.remove_account)) }
    }

    @Composable
    private fun RemoveDialog(ui: AccountUi) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = null },
            title = { Text(stringResource(R.string.remove_account_title, ui.account.name)) },
            text = { Text(stringResource(R.string.remove_account_explanation)) },
            confirmButton = { TextButton(onClick = { remove(ui) }) { Text(stringResource(R.string.remove)) } },
            dismissButton = { TextButton(onClick = { confirmingRemove = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    @Composable
    private fun RemoveAnywayDialog(ui: AccountUi, reason: String) {
        AlertDialog(
            onDismissRequest = { serverNotTold = null },
            title = { Text(stringResource(R.string.server_not_told_title)) },
            text = { Text(stringResource(R.string.server_not_told, reason)) },
            confirmButton = { TextButton(onClick = { removeAnyway(ui) }) { Text(stringResource(R.string.remove_anyway)) } },
            dismissButton = { TextButton(onClick = { serverNotTold = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    private fun remove(ui: AccountUi) {
        confirmingRemove = null
        busy = true
        status = null
        val account = ui.account
        accountThread.execute {
            val result = removeAccount(account, { approvals(account).remove() }, store, ::deleteKeys) { deleteInstallation(applicationContext) }
            runOnUiThread {
                busy = false
                when (result) {
                    RemoveResult.Removed -> removed(ui)
                    RemoveResult.NotRemovedHere -> ui.status = getString(R.string.not_removed_here)
                    is RemoveResult.ServerNotTold -> serverNotTold = ui to result.reason
                    UpdateRequired -> ui.updateRequired = true
                }
            }
        }
    }

    private fun removeAnyway(ui: AccountUi) {
        serverNotTold = null
        busy = true
        accountThread.execute {
            val removed = unenroll(ui.account, store, ::deleteKeys) { deleteInstallation(applicationContext) }
            runOnUiThread {
                busy = false
                if (removed) removed(ui) else ui.status = getString(R.string.not_removed)
            }
        }
    }

    private fun removed(ui: AccountUi) {
        accounts.remove(ui)
        shownIds.remove(ui.account)
        link = ""
        status = getString(R.string.removed, ui.account.name)
    }

    @Composable
    private fun ChallengeCard(ui: AccountUi, c: Challenge) {
        Text(stringResource(R.string.sign_in_request), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.request_account, ui.account.name))
        if (c.ipaddr.isNotEmpty()) Text(stringResource(R.string.request_from, c.ipaddr))
        c.pushinfo.forEach { (key, value) -> Text(stringResource(R.string.request_field, key, value)) }
        if (c.type.isNotEmpty()) Text(c.type)
        Button(onClick = { confirm(ui, c, Answer.APPROVE) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.approve))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = { confirm(ui, c, Answer.DENY) }, enabled = !busy) { Text(stringResource(R.string.deny)) }
            OutlinedButton(onClick = { confirm(ui, c, Answer.NOT_ME) }, enabled = !busy) { Text(stringResource(R.string.not_me)) }
        }
    }

    private fun show(ui: AccountUi, c: Challenge?) {
        ui.challenge = c
        if (c == null) shownIds.remove(ui.account) else shownIds[ui.account] = c.id
    }

    /**
     * On the account thread: one poll of every account, then the next after the shortest
     * interval a server set. With no account the loop stops; enrolling starts it again.
     */
    private fun poll(generation: Int) {
        if (generation != pollGeneration.get()) return
        val saved = store.load()
        if (saved.isEmpty()) {
            pollingGeneration = null
            return
        }
        val polls = pollAll(saved, shownIds, ::approvals)
        runOnUiThread { polls.forEach { (account, poll) -> apply(account, poll.update) } }
        val next = polls.minOf { it.second.nextSeconds }
        accountThread.schedule({ poll(generation) }, next, TimeUnit.SECONDS)
    }

    private fun apply(account: Account, update: PollUpdate) {
        val ui = ui(account) ?: return
        if (update !is PollUpdate.Failed) ui.pollError = null
        if (update != UpdateRequired && update !is PollUpdate.Failed) ui.updateRequired = false
        when (update) {
            is PollUpdate.Show -> if (ui.challenge == null && ui.keyLost == null) {
                show(ui, update.challenge)
                ui.status = null
            }
            is PollUpdate.Withdrawn -> if (ui.challenge?.id == update.id && !busy) {
                show(ui, null)
                ui.status = getString(R.string.request_withdrawn)
            }
            PollUpdate.NoChange -> Unit
            is PollUpdate.Failed -> ui.pollError = update.message
            is PollUpdate.KeyLost -> lose(account, update.key)
            UpdateRequired -> ui.updateRequired = true
        }
    }

    /** Asks for the account's unlock, bound to its approval_key, then sends [answer] signed with it. */
    private fun confirm(ui: AccountUi, c: Challenge, answer: Answer) {
        val account = ui.account
        val mode = account.unlockMode
        val signature = try {
            approvalSignature(account.keys.approval)
        } catch (e: KeyLostException) {
            lose(account, e.key)
            return
        } catch (e: Exception) {
            ui.status = getString(R.string.approval_key_unavailable, e.message ?: e.javaClass.simpleName)
            return
        }
        busy = true
        ui.status = null
        val title = when (answer) {
            Answer.APPROVE -> R.string.confirm_approve
            Answer.DENY -> R.string.confirm_deny
            Answer.NOT_ME -> R.string.confirm_not_me
        }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(getString(title))
            .setSubtitle(getString(R.string.request_account, account.name))
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
                            ui.status = getString(R.string.approval_key_unavailable, "no signature in the prompt's result")
                            return
                        }
                        send(ui, c, answer, authenticated.asSigner())
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        busy = false
                        ui.status = errString.toString()
                    }
                },
            )
    }

    private fun send(ui: AccountUi, c: Challenge, answer: Answer, approvalSigner: Signer) {
        accountThread.execute {
            val result = approvals(ui.account).answer(c.id, answer, approvalSigner)
            runOnUiThread {
                busy = false
                if (result == UpdateRequired) {
                    ui.updateRequired = true
                    return@runOnUiThread
                }
                if (ui.challenge?.id == c.id) show(ui, null)
                ui.status = describe(result)
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
        UpdateRequired -> getString(R.string.update_required)
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
            deleteKeys = ::deleteKeys,
            store = store,
            api = { host -> DeviceApi("https://$host") },
            fid = { currentFid(applicationContext) ?: NO_FID },
            unixSeconds = { System.currentTimeMillis() / 1000 },
        )
        accountThread.execute {
            val result = enroller.enroll(target, mode)
            runOnUiThread {
                busy = false
                if (result != UpdateRequired) enrollUpdateRequired = false
                when (result) {
                    is EnrollResult.Enrolled -> {
                        naming = result.account
                        labelDraft = result.account.name
                    }
                    UpdateRequired -> enrollUpdateRequired = true
                    else -> status = describe(result)
                }
            }
        }
    }

    private fun enrolled(account: Account) {
        adding = false
        link = ""
        status = null
        accounts.add(AccountUi(account))
        pushToAccounts()
        if (resumed) startPolling()
    }

    private fun describe(result: EnrollResult): String = when (result) {
        is EnrollResult.Enrolled -> result.account.name
        EnrollResult.AlreadyEnrolled -> getString(R.string.already_enrolled)
        EnrollResult.NotAnActivationLink -> getString(R.string.not_an_activation_link)
        is EnrollResult.KeysUnavailable -> getString(R.string.keys_unavailable, result.message)
        is EnrollResult.Rejected -> getString(R.string.enroll_rejected, result.status)
        is EnrollResult.NetworkError -> getString(R.string.network_error, result.message)
        EnrollResult.NotSaved -> getString(R.string.not_saved)
        UpdateRequired -> getString(R.string.update_required)
    }
}

private const val TAG = "Frict"

/** What happened to this key, for an account whose approval_key [mode] unlocks. */
fun LostKey.explanation(mode: UnlockMode): Int = when (this) {
    LostKey.DEVICE_KEY -> R.string.device_key_lost
    LostKey.APPROVAL_KEY -> when (mode) {
        UnlockMode.FINGERPRINT_ONLY -> R.string.approval_key_lost_fingerprint_only
        UnlockMode.FINGERPRINT_OR_SCREEN_LOCK -> R.string.approval_key_lost_fingerprint_or_screen_lock
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
