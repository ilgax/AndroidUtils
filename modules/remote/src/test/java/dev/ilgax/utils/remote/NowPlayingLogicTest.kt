package dev.ilgax.utils.remote

import org.junit.Assert.*
import org.junit.Test

class NowPlayingLogicTest {
    private val playing = NowPlayingLogic.STATE_PLAYING
    private val paused = NowPlayingLogic.STATE_PAUSED
    private val stopped = NowPlayingLogic.STATE_STOPPED
    private val none = NowPlayingLogic.STATE_NONE

    @Test fun nothingActiveMeansNoSession() {
        assertNull(NowPlayingLogic.pick(emptyList()))
    }

    @Test fun aPlayingSessionBeatsNewerPausedOnes() {
        // The list is most recently active first: the paused one is newer, the playing one wins anyway.
        assertEquals(2, NowPlayingLogic.pick(listOf(paused, stopped, playing)))
        assertEquals(0, NowPlayingLogic.pick(listOf(playing, playing)))
        assertEquals(1, NowPlayingLogic.pick(listOf(paused, NowPlayingLogic.STATE_BUFFERING)))
    }

    @Test fun withoutAPlayingOneThePausedOneWinsThenTheMostRecent() {
        assertEquals(1, NowPlayingLogic.pick(listOf(stopped, paused, none)))
        assertEquals(0, NowPlayingLogic.pick(listOf(stopped, none)))
        assertEquals(0, NowPlayingLogic.pick(listOf(none)))
    }

    @Test fun movingStatesCountAsPlaying() {
        for (s in listOf(
            NowPlayingLogic.STATE_PLAYING, NowPlayingLogic.STATE_FAST_FORWARDING, NowPlayingLogic.STATE_REWINDING,
            NowPlayingLogic.STATE_BUFFERING, NowPlayingLogic.STATE_SKIPPING_TO_NEXT, NowPlayingLogic.STATE_SKIPPING_TO_PREVIOUS,
            NowPlayingLogic.STATE_SKIPPING_TO_QUEUE_ITEM,
        )) assertTrue("state $s", NowPlayingLogic.isPlaying(s))
        for (s in listOf(none, stopped, paused, 7 /* error */, 8 /* connecting */)) assertFalse("state $s", NowPlayingLogic.isPlaying(s))
    }

    @Test fun capabilitiesComeFromTheActionBits() {
        val all = NowPlayingLogic.ACTION_SKIP_TO_NEXT or NowPlayingLogic.ACTION_SKIP_TO_PREVIOUS or NowPlayingLogic.ACTION_SEEK_TO
        assertTrue(NowPlayingLogic.canNext(all) && NowPlayingLogic.canPrevious(all) && NowPlayingLogic.canSeek(all))
        assertFalse(NowPlayingLogic.canNext(0) || NowPlayingLogic.canPrevious(0) || NowPlayingLogic.canSeek(0))
        assertTrue(NowPlayingLogic.canNext(NowPlayingLogic.ACTION_SKIP_TO_NEXT))
        assertFalse(NowPlayingLogic.canPrevious(NowPlayingLogic.ACTION_SKIP_TO_NEXT))
        // Play/pause (4 + 2 + 512) says nothing about skipping or seeking.
        assertFalse(NowPlayingLogic.canSeek(2L or 4L or 512L))
    }

    @Test fun speedIsZeroUnlessMoving() {
        assertEquals(100, NowPlayingLogic.speedPercent(playing, 1f))
        assertEquals(150, NowPlayingLogic.speedPercent(playing, 1.5f))
        assertEquals(0, NowPlayingLogic.speedPercent(paused, 1f))
        assertEquals(0, NowPlayingLogic.speedPercent(NowPlayingLogic.STATE_BUFFERING, 1f))
        assertEquals(-200, NowPlayingLogic.speedPercent(NowPlayingLogic.STATE_REWINDING, -2f))
    }

    @Test fun coverUsesTheFirstRungThatFitsAndStopsThere() {
        val tried = ArrayList<Pair<Int, Int>>()
        val art = NowPlayingLogic.chooseArt { edge, q -> tried += edge to q; ByteArray(3_000) }
        assertEquals(3_000, art!!.size)
        assertEquals(listOf(96 to 70), tried)
    }

    @Test fun coverStepsDownTheLadderWhenTooBig() {
        val sizes = mapOf(96 to 70 to 5_000, 96 to 55 to 4_300, 80 to 55 to 3_900, 64 to 50 to 2_000)
        val tried = ArrayList<Pair<Int, Int>>()
        val art = NowPlayingLogic.chooseArt { e, q -> tried += e to q; ByteArray(sizes.getValue(e to q)) }
        assertEquals(3_900, art!!.size)
        assertEquals(3, tried.size)
    }

    @Test fun coverFallsBackToTheSmallestUnderTheHardCapOrNone() {
        assertEquals(5_500, NowPlayingLogic.chooseArt { _, q -> ByteArray(if (q == 50) 5_500 else 6_000) }!!.size)
        assertNull(NowPlayingLogic.chooseArt { _, _ -> ByteArray(7_000) })
        assertNull(NowPlayingLogic.chooseArt { _, _ -> null })
        // A failed rung is skipped, not fatal.
        assertEquals(1_000, NowPlayingLogic.chooseArt { e, _ -> if (e == 96) null else ByteArray(1_000) }!!.size)
    }

    @Test fun grantCommandIsTheShellOne() {
        assertEquals(
            listOf("/system/bin/cmd", "notification", "allow_listener", "dev.ilgax.utils/dev.ilgax.utils.remote.NowPlayingListener"),
            NotificationAccess.grantArgv("dev.ilgax.utils/dev.ilgax.utils.remote.NowPlayingListener"),
        )
    }
}
