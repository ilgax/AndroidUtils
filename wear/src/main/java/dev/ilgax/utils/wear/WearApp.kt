package dev.ilgax.utils.wear

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) enableStrictMode()
        // Everything the first screen reads from disk or the Keystore is loaded here, off the main thread.
        Thread({
            WatchPrefs.get(this).preload()
            RemoteClient.get(this)
            WatchUpdater.get(this).footer
        }, "tabremote-preload").start()
    }

    /** Debug builds only: violations go to logcat (tag StrictMode) instead of crashing the app. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }
}
