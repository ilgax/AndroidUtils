package dev.ilgax.utils.speaker

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerVolumeKeeperTest {
    private val store = MemoryStore()
    private val logs = ArrayList<String>()
    private val errors = ArrayList<String>()

    private fun keeper(v: VolumeBackend) = SpeakerVolumeKeeper(store, v, { logs.add(it) }, { w, _ -> errors.add(w) })
    private val music = AudioManager.STREAM_MUSIC
    private val ring = AudioManager.STREAM_RING
    private val alarm = AudioManager.STREAM_ALARM

    @Test fun everyStreamIsSavedSeparatelyZeroedAndRestored() {
        val v = FakeVolumes()
        val original = v.speaker.toMap()
        val k = keeper(v)

        val out = k.enforce(active = false, device = true, reason = "test")

        assertEquals(7, SpeakerStreams.ALL.size)
        for (s in SpeakerStreams.ALL) assertEquals("saved ${SpeakerStreams.name(s)}", original[s], k.saved(s))
        assertEquals(0, v.speaker[music]); assertEquals(0, v.speaker[ring]); assertEquals(0, v.speaker[AudioManager.STREAM_DTMF])
        assertEquals(0, v.speaker[AudioManager.STREAM_ACCESSIBILITY])
        assertTrue(out.notes.isEmpty())

        assertTrue(k.restore(active = false, device = true))
        assertEquals(original, v.speaker.toMap())
        for (s in SpeakerStreams.ALL) assertNull(k.saved(s))
        assertFalse(k.hasSaved())
    }

    @Test fun minimumAboveZeroIsSetToTheMinimumAndLogged() {
        val v = FakeVolumes()
        keeper(v).enforce(false, true, "test")
        assertEquals(1, v.speaker[alarm])
        assertTrue(logs.any { "alarm" in it && "minimum of 1" in it })
        // A second pass has nothing more to do and does not complain again.
        logs.clear()
        keeper(v).enforce(false, true, "again")
        assertTrue(logs.none { "zeroed" in it })
        // The original alarm level comes back.
        keeper(v).restore(false, true)
        assertEquals(8, v.speaker[alarm])
    }

    @Test fun streamsSharingAnIndexSaveTheirOriginalNotTheZeroedValue() {
        val v = FakeVolumes(
            speaker = mutableMapOf(music to 9, AudioManager.STREAM_ALARM to 8, AudioManager.STREAM_DTMF to 4, AudioManager.STREAM_ACCESSIBILITY to 3, AudioManager.STREAM_SYSTEM to 5),
            aliases = mapOf(ring to music, AudioManager.STREAM_NOTIFICATION to music),
        )
        val k = keeper(v)
        k.enforce(false, true, "test")
        assertEquals(9, k.saved(music))
        assertEquals(9, k.saved(ring))
        assertEquals(9, k.saved(AudioManager.STREAM_NOTIFICATION))
        k.restore(false, true)
        assertEquals(9, v.speaker[music])
    }

    @Test fun aSecondEnforceKeepsTheFirstSavedValue() {
        val v = FakeVolumes()
        val k = keeper(v)
        k.enforce(false, true, "one")
        v.speaker[music] = 4 // the user raised it while the switch was ON
        k.enforce(false, true, "two")
        assertEquals(0, v.speaker[music])
        assertEquals(9, k.saved(music))
    }

    @Test fun ringerModeIsSavedBeforeZeroingAndRestored() {
        val v = FakeVolumes()
        val k = keeper(v)
        k.enforce(false, true, "test")
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, v.ringerMode) // zeroing RING flipped it
        assertEquals(AudioManager.RINGER_MODE_NORMAL, k.savedRinger())
        assertTrue(logs.any { "ringer mode" in it })

        // Still the first value after more enforcing.
        k.enforce(false, true, "again")
        assertEquals(AudioManager.RINGER_MODE_NORMAL, k.savedRinger())

        assertTrue(k.restore(false, true))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, v.ringerMode)
        assertNull(k.savedRinger())
    }

    @Test fun silentRingerStaysSilentAfterOff() {
        val v = FakeVolumes().also { it.ringerMode = AudioManager.RINGER_MODE_SILENT }
        val k = keeper(v)
        k.enforce(false, true, "test")
        v.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        k.restore(false, true)
        assertEquals(AudioManager.RINGER_MODE_SILENT, v.ringerMode)
    }

    @Test fun theSingleSavedIndexOfTheOldVersionBecomesTheMusicValue() {
        store.putInt("saved_speaker_idx", 11)
        val v = FakeVolumes().also { it.speaker[music] = 0 }
        val k = keeper(v)
        assertEquals(11, k.saved(music))
        assertEquals(-1, store.getInt("saved_speaker_idx", -1))
        k.restore(false, true)
        assertEquals(11, v.speaker[music])
    }

    @Test fun anExistingMusicValueWinsOverTheLegacyKey() {
        store.putInt(SpeakerVolumeKeeper.key(music), 6)
        store.putInt("saved_speaker_idx", 11)
        assertEquals(6, keeper(FakeVolumes()).saved(music))
    }

    @Test fun aFailedRestoreKeepsTheSavedValueForTheNextTry() {
        val v = FakeVolumes()
        val k = keeper(v)
        k.enforce(false, true, "test")
        v.failDevice = true
        assertFalse(k.restore(false, true))
        assertEquals(9, k.saved(music))
        assertTrue(k.hasSaved())
        v.failDevice = false
        assertTrue(k.restore(false, true))
        assertEquals(9, v.speaker[music])
    }

    @Test fun whileTheSpeakerIsTheActiveRouteTheStreamPathIsUsed() {
        val v = FakeVolumes()
        val k = keeper(v)
        k.enforce(active = true, device = true, reason = "test")
        assertTrue(v.calls.all { it.startsWith("route") })
        assertEquals(0, v.speaker[music])
        k.restore(active = true, device = true)
        assertEquals(9, v.speaker[music])
    }

    @Test fun withoutTheHiddenServiceAndAnotherRouteNothingCanBeDoneAndItIsReported() {
        val v = FakeVolumes()
        val out = keeper(v).enforce(active = false, device = false, reason = "test")
        assertTrue(out.notes.any { "music index unknown" in it })
        assertTrue(v.calls.isEmpty())
        assertFalse(SpeakerStreams.ALL.any { keeper(v).saved(it) != null })
    }

    @Test fun snapshotReportsCurrentSavedAndTargetWithoutChangingAnything() {
        val v = FakeVolumes()
        val k = keeper(v)
        k.enforce(false, true, "test")
        val snap = k.snapshot(false, true).associateBy { it.stream }
        assertEquals(0, snap.getValue(music).current)
        assertEquals(9, snap.getValue(music).saved)
        assertEquals(1, snap.getValue(alarm).target)
        assertEquals(1, snap.getValue(alarm).current)
    }
}
