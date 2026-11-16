package dev.ilgax.tabremote.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** The two update notifications: "update ready" (app closed) and "confirm the install" (system asks for a tap). */
object UpdateNotifier {
    private const val CHANNEL = "update"
    private const val ID_READY = 41
    private const val ID_CONFIRM = 42

    /** MainActivity extra: the user tapped the notification, so start the update now. */
    const val EXTRA_START_UPDATE = "dev.ilgax.tabremote.wear.START_UPDATE"

    fun ready(context: Context) {
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_START_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        post(context, ID_READY, context.getString(R.string.notif_update_ready), context.getString(R.string.notif_update_ready_text), open, 1)
    }

    /** [confirm] is the system's own install confirmation intent. */
    fun confirm(context: Context, confirm: Intent) {
        post(context, ID_CONFIRM, context.getString(R.string.notif_confirm), context.getString(R.string.notif_confirm_text), confirm, 2)
    }

    fun clear(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(ID_READY)
        nm.cancel(ID_CONFIRM)
    }

    private fun post(context: Context, id: Int, title: String, text: String, target: Intent, requestCode: Int) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_HIGH),
            )
            val pi = PendingIntent.getActivity(
                context, requestCode, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            nm.notify(
                id,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_tile_icon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: SecurityException) {
            // Notifications not allowed: the update still waits in the app.
        }
    }
}
