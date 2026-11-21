package dev.ilgax.utils.remote

import android.service.notification.NotificationListenerService

/**
 * Does nothing with notifications. Android only lets an app list the active media sessions
 * (MediaSessionManager.getActiveSessions) when it has an enabled notification listener, so this is the key that grant is for.
 * See [NotificationAccess].
 */
class NowPlayingListener : NotificationListenerService() {
    /** The grant just took effect (or the service restarted): a subscribed watch may have been waiting for it. */
    override fun onListenerConnected() {
        NowPlayingMonitor.get(this).refresh()
    }
}
