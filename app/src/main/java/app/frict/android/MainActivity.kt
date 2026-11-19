package app.frict.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One thread for every account operation, so no two touch the keys or the store at once. */
private val accountThread: ExecutorService = Executors.newSingleThreadExecutor()

class MainActivity : ComponentActivity() {
    private var link by mutableStateOf("")
    private var account by mutableStateOf<Account?>(null)
    private var status by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

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
                        account?.let { Text(stringResource(R.string.enrolled, it.userId, it.host)) } ?: EnrollForm()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeLink(intent)
    }

    private fun takeLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.dataString?.let { link = it }
    }

    @androidx.compose.runtime.Composable
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
