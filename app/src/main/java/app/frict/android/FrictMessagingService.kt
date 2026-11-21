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
private const val ALERT_CHANNEL = "alerts"
private const val FID_TIMEOUT_SECONDS = 30L

const val EXTRA_CHALLENGE_ID = "app.frict.android.CHALLENGE_ID"

/**
 * Wakes the app for a push: a visible notification for each, which FCM expects of a
 * high-priority message. The notification says only that something happened; the app
 * fetches what with signed requests once opened.
 *
 * With firebase_messaging_installation_id_enabled, FCM calls onRegistered, never onNewToken.
 */
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class FrictMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) = sendFid(applicationContext, installationId)

    override fun onMessageReceived(message: RemoteMessage) {
        if (PrefsAccountStore(applicationContext).load() == null) {
            Log.i(TAG, "ignored a push: not enrolled")
            return
        }
        when (val target = parsePush(message.data)) {
            is PushTarget.Challenge -> notify(target, LOGIN_CHANNEL, R.string.channel_logins, R.string.notify_login)
            is PushTarget.Alert -> notify(target, ALERT_CHANNEL, R.string.channel_alerts, R.string.alert_title)
            null -> Log.w(TAG, "ignored a push with data keys ${message.data.keys}")
        }
    }

    private fun notify(target: PushTarget, channelId: String, channelName: Int, title: Int) {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(channelId, getString(channelName), NotificationManager.IMPORTANCE_HIGH))
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (target is PushTarget.Challenge) open.putExtra(EXTRA_CHALLENGE_ID, target.id)
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(title))
            .setContentText(getString(R.string.notify_open))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, target.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        notifications.notify(target.id, 0, notification)
    }
}

/** A build without a Firebase project (release, until it has one) runs poll-only. */
private fun hasFirebase(context: Context) = FirebaseApp.getApps(context).isNotEmpty()

/** On every start: registers with FCM and sends this installation's FID to the server. */
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

/** Sends [fid] to the server if this device is enrolled. */
fun sendFid(context: Context, fid: String) = accountThread.execute {
    val account = PrefsAccountStore(context).load() ?: return@execute
    val result = try {
        approvals(account).sendFid(fid)
    } catch (e: Exception) {
        Log.w(TAG, "FID not sent", e)
        return@execute
    }
    Log.i(TAG, "FID update: $result")
}
