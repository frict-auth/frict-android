package app.frict.android

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetiredChannelTest {
    @Test
    fun launchDeletesTheAlertChannelOfEarlierVersions() {
        val notifications = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(RETIRED_ALERT_CHANNEL, "Sign-ins reported as not theirs", NotificationManager.IMPORTANCE_HIGH))
        assertNotNull(notifications.getNotificationChannel(RETIRED_ALERT_CHANNEL))
        ActivityScenario.launch(MainActivity::class.java).use {
            assertNull(notifications.getNotificationChannel(RETIRED_ALERT_CHANNEL))
        }
    }
}
