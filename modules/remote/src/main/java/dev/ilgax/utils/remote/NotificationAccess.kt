package dev.ilgax.utils.remote

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.ilgax.utils.core.shizuku.ShizukuShell
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus

/** The notification-listener grant that media control needs. Shizuku can give it without any settings screen. */
object NotificationAccess {
    fun component(context: Context) = ComponentName(context, NowPlayingListener::class.java)

    fun isGranted(context: Context): Boolean = try {
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
    } catch (e: Exception) {
        false
    }

    /** `cmd notification allow_listener <package/class>`, run as the shell user. */
    internal fun grantArgv(flattened: String) = listOf("/system/bin/cmd", "notification", "allow_listener", flattened)

    /** Grants the access through Shizuku when it is ready and not granted yet. True when access is granted afterwards. */
    suspend fun ensure(context: Context): Boolean {
        val app = context.applicationContext
        if (isGranted(app)) return true
        if (ShizukuState.current(app) != ShizukuStatus.Ready) return false
        ShizukuShell.get(app).run(*grantArgv(component(app).flattenToString()).toTypedArray())
        return isGranted(app)
    }

    /** The system screen where the user turns it on by hand (the detail page of this listener where there is one, the list otherwise). */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())

    fun listSettingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}
