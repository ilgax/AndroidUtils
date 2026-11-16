package dev.ilgax.utils.speaker

import android.media.AudioManager

/** The streams that are zeroed on the built-in speaker, with the names used in logs, diagnostics and the Settings fallback. */
internal object SpeakerStreams {
    val ALL = listOf(
        AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_RING, AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_ALARM, AudioManager.STREAM_DTMF, AudioManager.STREAM_ACCESSIBILITY,
    )

    fun name(stream: Int) = when (stream) {
        AudioManager.STREAM_MUSIC -> "music"
        AudioManager.STREAM_NOTIFICATION -> "notification"
        AudioManager.STREAM_RING -> "ring"
        AudioManager.STREAM_SYSTEM -> "system"
        AudioManager.STREAM_ALARM -> "alarm"
        AudioManager.STREAM_DTMF -> "dtmf"
        AudioManager.STREAM_ACCESSIBILITY -> "accessibility"
        else -> "stream$stream"
    }
}

/**
 * Saves each stream's speaker index before it is zeroed and puts all of them back, plus the ringer mode (zeroing RING can flip
 * it to vibrate or silent). Everything is read before anything is changed: on some builds streams share one index (ring and
 * notification follow music on a tablet), and reading after the first write would save a zero.
 */
internal class SpeakerVolumeKeeper(
    private val store: KeyValueStore,
    private val audio: VolumeBackend,
    private val log: (String) -> Unit,
    private val error: (String, Throwable) -> Unit,
) {
    class StreamState(val stream: Int, val current: Int?, val saved: Int?, val target: Int)
    class Outcome(val states: List<StreamState>, val notes: List<String>)

    fun saved(stream: Int): Int? {
        migrate()
        return store.getInt(key(stream), -1).takeIf { it >= 0 }
    }

    fun savedRinger(): Int? = store.getInt(K_RINGER, -1).takeIf { it >= 0 }

    fun hasSaved(): Boolean = SpeakerStreams.ALL.any { saved(it) != null } || savedRinger() != null

    /** Current and saved index of every stream, without changing anything (diagnostics). */
    fun snapshot(active: Boolean, device: Boolean): List<StreamState> = SpeakerStreams.ALL.map {
        StreamState(it, read(it, active, device), saved(it), target(it))
    }

    /** Idempotent: saves what is not saved yet and lowers every stream that is above its target (0, or its minimum). */
    fun enforce(active: Boolean, device: Boolean, reason: String): Outcome {
        migrate()
        val notes = ArrayList<String>()
        val current = SpeakerStreams.ALL.associateWith { read(it, active, device) }
        if (savedRinger() == null) {
            try { store.putInt(K_RINGER, audio.ringerMode) } catch (t: Throwable) { error("read ringer mode", t) }
        }
        val states = ArrayList<StreamState>()
        for (stream in SpeakerStreams.ALL) {
            val name = SpeakerStreams.name(stream)
            val cur = current[stream]
            val target = target(stream)
            if (cur == null) {
                notes.add("$name index unknown")
                states.add(StreamState(stream, null, saved(stream), target))
                continue
            }
            var saved = saved(stream)
            if (saved == null && cur > target) {
                store.putInt(key(stream), cur)
                saved = cur
                log("saved speaker $name index $cur")
            }
            if (cur > target) {
                val done = apply(stream, target, active, device)
                log("zeroed speaker $name $cur->$target via ${if (active) "setStreamVolume" else "setDeviceVolume"} ok=$done ($reason)")
                if (target > 0) log("$name has a minimum of $target and cannot reach 0; relying on the per-app mute")
                if (!done) notes.add("could not zero $name (active=$active)")
            }
            states.add(StreamState(stream, cur, saved, target))
        }
        val ringer = savedRinger()
        if (ringer != null) {
            val now = try { audio.ringerMode } catch (t: Throwable) { ringer }
            if (now != ringer) log("ringer mode is $now (was $ringer); it is put back on OFF")
        }
        return Outcome(states, notes)
    }

    /** Puts every saved index and the ringer mode back. True when nothing is left to restore. */
    fun restore(active: Boolean, device: Boolean): Boolean {
        migrate()
        var all = true
        for (stream in SpeakerStreams.ALL) {
            val saved = saved(stream) ?: continue
            val name = SpeakerStreams.name(stream)
            if (apply(stream, saved, active, device)) {
                store.remove(key(stream))
                log("restored speaker $name index $saved")
            } else {
                all = false
                log("ERR could not restore $name index $saved; kept saved value")
            }
        }
        savedRinger()?.let { ringer ->
            try {
                if (audio.ringerMode != ringer) audio.ringerMode = ringer
                store.remove(K_RINGER)
                log("restored ringer mode $ringer")
            } catch (t: Throwable) {
                all = false
                error("restore ringer mode", t)
            }
        }
        return all
    }

    private fun target(stream: Int) = try { maxOf(0, audio.minIndex(stream)) } catch (t: Throwable) { 0 }

    private fun read(stream: Int, active: Boolean, device: Boolean): Int? {
        val name = SpeakerStreams.name(stream)
        if (device) {
            try { return audio.speakerIndex(stream) } catch (t: Throwable) { error("getDeviceVolume(speaker,$name)", t) }
        }
        if (active) {
            try { return audio.routeIndex(stream) } catch (t: Throwable) { error("getStreamVolume($name)", t) }
        }
        return try { audio.settingsIndex(stream) } catch (t: Throwable) { null }
    }

    /** Device path while another route is active, stream path while the speaker is the route. */
    private fun apply(stream: Int, index: Int, active: Boolean, device: Boolean): Boolean {
        val name = SpeakerStreams.name(stream)
        var done = false
        if (device && !active) {
            try { audio.setSpeakerIndex(stream, index); done = true } catch (t: Throwable) { error("setDeviceVolume(speaker,$name,$index)", t) }
        }
        if (active) {
            try { audio.setRouteIndex(stream, index); done = true } catch (t: Throwable) { error("setStreamVolume($name,$index)", t) }
        }
        return done
    }

    /** The single saved music index of 0.7.0 becomes the music value. */
    private fun migrate() {
        val legacy = store.getInt(K_LEGACY, -1)
        if (legacy < 0) return
        if (store.getInt(key(AudioManager.STREAM_MUSIC), -1) < 0) store.putInt(key(AudioManager.STREAM_MUSIC), legacy)
        store.remove(K_LEGACY)
    }

    companion object {
        const val K_LEGACY = "saved_speaker_idx"
        const val K_RINGER = "saved_ringer_mode"
        fun key(stream: Int) = "saved_idx_$stream"
    }
}
