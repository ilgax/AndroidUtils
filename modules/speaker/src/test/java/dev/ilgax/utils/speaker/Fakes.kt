package dev.ilgax.utils.speaker

import android.media.AudioManager

internal class MemoryStore : KeyValueStore {
    val map = HashMap<String, Any>()
    override fun getInt(key: String, def: Int) = map[key] as? Int ?: def
    override fun putInt(key: String, value: Int) { map[key] = value }
    override fun getString(key: String) = map[key] as? String
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/**
 * A speaker with one index per stream. [aliases] maps a stream to the stream whose index it shares (ring and notification
 * follow music on a tablet); zeroing RING flips the ringer mode to vibrate, as Android does.
 */
internal class FakeVolumes(
    val speaker: MutableMap<Int, Int> = mutableMapOf(
        AudioManager.STREAM_MUSIC to 9, AudioManager.STREAM_NOTIFICATION to 7, AudioManager.STREAM_RING to 6, AudioManager.STREAM_SYSTEM to 5,
        AudioManager.STREAM_ALARM to 8, AudioManager.STREAM_DTMF to 4, AudioManager.STREAM_ACCESSIBILITY to 3,
    ),
    val minimum: Map<Int, Int> = mapOf(AudioManager.STREAM_ALARM to 1),
    val aliases: Map<Int, Int> = emptyMap(),
) : VolumeBackend {
    val calls = ArrayList<String>()
    var failDevice = false
    var failRoute = false
    override var ringerMode: Int = AudioManager.RINGER_MODE_NORMAL

    private fun slot(stream: Int) = aliases[stream] ?: stream
    private fun clamp(stream: Int, index: Int) = maxOf(index, minimum[stream] ?: 0)

    override fun minIndex(stream: Int) = minimum[stream] ?: 0
    override fun speakerIndex(stream: Int) = speaker.getValue(slot(stream))
    override fun setSpeakerIndex(stream: Int, index: Int) {
        if (failDevice) throw IllegalStateException("device volume refused")
        calls.add("device $stream=$index")
        speaker[slot(stream)] = clamp(stream, index)
        if (stream == AudioManager.STREAM_RING && index == 0) ringerMode = AudioManager.RINGER_MODE_VIBRATE
    }
    override fun routeIndex(stream: Int) = speaker.getValue(slot(stream))
    override fun setRouteIndex(stream: Int, index: Int) {
        if (failRoute) throw IllegalStateException("stream volume refused")
        calls.add("route $stream=$index")
        speaker[slot(stream)] = clamp(stream, index)
        if (stream == AudioManager.STREAM_RING && index == 0) ringerMode = AudioManager.RINGER_MODE_VIBRATE
    }
    override fun settingsIndex(stream: Int): Int? = null
}

internal class FakeOps(var packages: Map<Int, List<String>> = emptyMap()) : AppOpsBackend {
    val modes = HashMap<Pair<Int, String>, Int>()
    val writes = ArrayList<String>()
    var failSet = false
    override fun packagesForUid(uid: Int) = packages[uid] ?: emptyList()
    override fun mode(uid: Int, pkg: String) = modes[uid to pkg] ?: 0
    override fun setMode(uid: Int, pkg: String, mode: Int) {
        if (failSet) throw SecurityException("denied")
        writes.add("$uid/$pkg=$mode")
        modes[uid to pkg] = mode
    }
}
