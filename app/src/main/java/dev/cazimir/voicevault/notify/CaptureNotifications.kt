package dev.cazimir.voicevault.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.cazimir.voicevault.MainActivity

/** Foreground recording status and durable export failure alerts. */
class CaptureNotifications(private val context: Context) {
    companion object {
        const val STATUS_CHANNEL_ID = "capture_status"
        const val FAILURE_CHANNEL_ID = "capture_failures"
        const val STATUS_NOTIFICATION_ID = 1
        private const val UNIDENTIFIED_FAILURE_NOTIFICATION_ID = 2
        private const val VAULT_ACCESS_NOTIFICATION_ID = 3
        const val EXTRA_RESELECT_VAULT = "dev.cazimir.voicevault.extra.RESELECT_VAULT"

        fun failureNotificationId(captureId: String): Int {
            val hash = captureId.hashCode()
            return if (hash in 1..3) hash + 3 else hash
        }

        fun genericTitle(captureId: String?): String =
            captureId?.let { "Recording needs attention · $it" } ?: "Recording needs attention"
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(STATUS_CHANNEL_ID, "Capture status", NotificationManager.IMPORTANCE_LOW)
        )
        manager.createNotificationChannel(
            NotificationChannel(FAILURE_CHANNEL_ID, "Capture failures", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.deleteNotificationChannel("capture_no_speech")
    }

    fun listeningNotification(): Notification = statusNotification("Listening…")

    fun updateToSaving() {
        manager.notify(STATUS_NOTIFICATION_ID, statusNotification("Saving audio…"))
    }

    fun postFailure(message: String, captureId: String? = null) {
        val id = captureId?.let { failureNotificationId(it) } ?: UNIDENTIFIED_FAILURE_NOTIFICATION_ID
        manager.notify(id, NotificationCompat.Builder(context, FAILURE_CHANNEL_ID)
            .setContentTitle(genericTitle(captureId))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(setupIntent(id, false))
            .build())
    }

    private fun setupIntent(id: Int, reselect: Boolean): PendingIntent = PendingIntent.getActivity(
        context, id,
        Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_RESELECT_VAULT, reselect)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun postVaultAccessLost() {
        val message = "MB-VoiceVault can't reach your output audio folder. Tap to choose it again. " +
            "Any previously captured audio is safe and saves once you do."
        val pending = setupIntent(VAULT_ACCESS_NOTIFICATION_ID, true)
        manager.notify(VAULT_ACCESS_NOTIFICATION_ID,
            NotificationCompat.Builder(context, FAILURE_CHANNEL_ID)
                .setContentTitle("Output audio folder not accessible")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .addAction(android.R.drawable.ic_menu_edit, "Select folder", pending)
                .build())
    }

    fun cancelVaultAccessLost() = manager.cancel(VAULT_ACCESS_NOTIFICATION_ID)

    fun cancelFailure(captureId: String) = manager.cancel(failureNotificationId(captureId))

    fun clearStaleFailures(expectedCaptureIds: Set<String>) {
        val expected = expectedCaptureIds.mapTo(mutableSetOf()) { failureNotificationId(it) }
        for (active in manager.activeNotifications) {
            if (active.notification.channelId != FAILURE_CHANNEL_ID || active.id in 1..3) continue
            if (active.id !in expected) manager.cancel(active.id)
        }
    }

    private fun statusNotification(title: String): Notification =
        NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
}
