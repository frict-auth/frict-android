package app.frict.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit

private const val TAG = "FrictPush"
private const val LOGIN_CHANNEL = "logins"
private const val FID_TIMEOUT_SECONDS = 30L

const val EXTRA_CHALLENGE_ID = "app.frict.android.CHALLENGE_ID"
const val EXTRA_USER_ID = "app.frict.android.USER_ID"

/**
 * Wakes the app for a login push: a visible notification for each, which FCM expects of a
 * high-priority message. A push names a challenge and a user ID; only the server of the
 * account with that user ID is asked for the challenge ([pushedChallenge]), and the
 * notification shows what that server returns. A push for no account here is dropped.
 *
 * With firebase_messaging_installation_id_enabled, FCM calls onRegistered, never onNewToken.
 */
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class FrictMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) = sendFid(applicationContext, installationId)

    override fun onMessageReceived(message: RemoteMessage) {
        val push = parsePush(message.data)
        if (push == null) {
            Log.w(TAG, "ignored a push with data keys ${message.data.keys}")
            return
        }
        val owned = accountThread.submit<OwnedChallenge?> {
            pushedChallenge(push, PrefsAccountStore(applicationContext).load(), ::approvals)
        }.get()
        if (owned == null) {
            Log.i(TAG, "ignored a push: no account here has it pending")
            return
        }
        notify(owned)
    }

    private fun notify(owned: OwnedChallenge) {
        val challengeId = owned.challenge.id
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(LOGIN_CHANNEL, getString(R.string.channel_logins), NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CHALLENGE_ID, challengeId)
            .putExtra(EXTRA_USER_ID, owned.account.userId)
        val notification = Notification.Builder(this, LOGIN_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notify_login))
            .setContentText(getString(R.string.request_account, owned.account.name))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, challengeId.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        notifications.notify(challengeId, 0, notification)
    }
}

/** The "Sign-ins reported as not theirs" channel of app versions with alerts. */
const val RETIRED_ALERT_CHANNEL = "alerts"

/** Deletes notification channels no version of the app posts to any more. */
fun deleteRetiredChannels(context: Context) =
    context.getSystemService(NotificationManager::class.java).deleteNotificationChannel(RETIRED_ALERT_CHANNEL)

/** A build without a Firebase project (release, until it has one) runs poll-only. */
private fun hasFirebase(context: Context) = FirebaseApp.getApps(context).isNotEmpty()

/** On every start: registers with FCM and sends this installation's FID to every account's server. */
fun startPush(context: Context) {
    if (!hasFirebase(context)) {
        Log.i(TAG, "no Firebase project in this build: poll only")
        return
    }
    FirebaseMessaging.getInstance().register().addOnFailureListener { Log.w(TAG, "FCM registration failed", it) }
    FirebaseInstallations.getInstance().id
        .addOnSuccessListener { sendFid(context, it) }
        .addOnFailureListener { Log.w(TAG, "no Firebase Installation ID", it) }
}

/** This installation's FID, or null without one. Blocks; not on the main thread. */
fun currentFid(context: Context): String? {
    if (!hasFirebase(context)) return null
    return try {
        Tasks.await(FirebaseInstallations.getInstance().id, FID_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (e: Exception) {
        Log.w(TAG, "no Firebase Installation ID", e)
        null
    }
}

/** Sends [fid] to every account's server. */
fun sendFid(context: Context, fid: String) = accountThread.execute {
    sendFidToAll(fid, PrefsAccountStore(context).load(), ::approvals).forEach { (account, result) ->
        Log.i(TAG, "FID update for ${account.userId} on ${account.host}: $result")
    }
}

/**
 * Deletes this installation's FID, here and at Firebase, once no account is left to push
 * to. Blocks; not on the main thread.
 */
fun deleteInstallation(context: Context) {
    if (!hasFirebase(context)) return
    try {
        Tasks.await(FirebaseInstallations.getInstance().delete(), FID_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        Log.i(TAG, "Firebase installation deleted")
    } catch (e: Exception) {
        Log.w(TAG, "Firebase installation not deleted", e)
    }
}
