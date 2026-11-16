package dev.ilgax.utils.speaker

import android.content.SharedPreferences

/*
 * The seams between the kill switch's bookkeeping and everything that needs Android or a Shizuku binder, so the
 * bookkeeping (what was saved, what was muted, what must be restored) runs on the JVM against fakes.
 */

/** The few preference operations the bookkeeping needs. */
internal interface KeyValueStore {
    fun getInt(key: String, def: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

internal class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getInt(key: String, def: Int) = prefs.getInt(key, def)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun remove(key: String) = prefs.edit().remove(key).apply()
}

/** Volumes and the ringer mode, as the volume bookkeeping sees them. Every call may throw. */
internal interface VolumeBackend {
    /** Lowest index the stream accepts (1 for alarm on many builds, 0 for most). */
    fun minIndex(stream: Int): Int

    /** The built-in speaker's own index for [stream], read through the hidden audio service. */
    fun speakerIndex(stream: Int): Int
    fun setSpeakerIndex(stream: Int, index: Int)

    /** The index of the stream on whatever route is active (the speaker, when this is used). */
    fun routeIndex(stream: Int): Int
    fun setRouteIndex(stream: Int, index: Int)

    /** Last-resort read of the stored per-device index when nothing else works; null when unknown. */
    fun settingsIndex(stream: Int): Int?

    var ringerMode: Int
}

/** The PLAY_AUDIO app op of other apps. Every call may throw. */
internal interface AppOpsBackend {
    fun packagesForUid(uid: Int): List<String>
    fun mode(uid: Int, pkg: String): Int
    fun setMode(uid: Int, pkg: String, mode: Int)
}

/** One active player: who owns it, and whether the audio system routed it to a built-in speaker (null: not known). */
internal data class PlayerRoute(val uid: Int, val started: Boolean, val onSpeaker: Boolean?)
