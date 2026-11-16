package dev.ilgax.utils.remote

import android.app.NotificationManager

internal object NotificationVisibility {
    /** [channelImportance] is null while the channel does not exist yet (then only the app-wide switch matters). */
    fun isHidden(channelImportance: Int?, appNotificationsEnabled: Boolean): Boolean =
        !appNotificationsEnabled || channelImportance == NotificationManager.IMPORTANCE_NONE
}
