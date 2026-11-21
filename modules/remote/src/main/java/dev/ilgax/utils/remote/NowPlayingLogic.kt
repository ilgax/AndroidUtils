package dev.ilgax.utils.remote

/** Pure decisions of the media-session side, free of Android types so they can be unit tested. */
internal object NowPlayingLogic {
    // android.media.session.PlaybackState values.
    const val STATE_NONE = 0
    const val STATE_STOPPED = 1
    const val STATE_PAUSED = 2
    const val STATE_PLAYING = 3
    const val STATE_FAST_FORWARDING = 4
    const val STATE_REWINDING = 5
    const val STATE_BUFFERING = 6
    const val STATE_SKIPPING_TO_PREVIOUS = 9
    const val STATE_SKIPPING_TO_NEXT = 10
    const val STATE_SKIPPING_TO_QUEUE_ITEM = 11

    // PlaybackState.ACTION_* bits.
    const val ACTION_SKIP_TO_PREVIOUS = 16L
    const val ACTION_SKIP_TO_NEXT = 32L
    const val ACTION_SEEK_TO = 256L

    /** A session counts as playing while it is moving, loading or switching track. */
    fun isPlaying(state: Int): Boolean = when (state) {
        STATE_PLAYING, STATE_FAST_FORWARDING, STATE_REWINDING, STATE_BUFFERING,
        STATE_SKIPPING_TO_PREVIOUS, STATE_SKIPPING_TO_NEXT, STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    /**
     * Index of the "current" session in [states], which is in the system's priority order (most recently active first): the first
     * one that is playing, otherwise the first that is paused, otherwise simply the first (most recently active). Null for none.
     */
    fun pick(states: List<Int>): Int? {
        if (states.isEmpty()) return null
        states.indexOfFirst { isPlaying(it) }.let { if (it >= 0) return it }
        states.indexOfFirst { it == STATE_PAUSED }.let { if (it >= 0) return it }
        return 0
    }

    fun canNext(actions: Long) = actions and ACTION_SKIP_TO_NEXT != 0L
    fun canPrevious(actions: Long) = actions and ACTION_SKIP_TO_PREVIOUS != 0L
    fun canSeek(actions: Long) = actions and ACTION_SEEK_TO != 0L

    /** Speed in percent for the wire: 0 unless the session is actually moving. */
    fun speedPercent(state: Int, speed: Float): Int =
        if (!isPlaying(state) || state == STATE_BUFFERING) 0 else Math.round(speed * 100f).coerceIn(-32768, 32767)

    /** What to try for the cover: a square edge and JPEG quality, best first. The first result within [TARGET_BYTES] wins. */
    val ART_LADDER = listOf(96 to 70, 96 to 55, 80 to 55, 64 to 50)
    const val ART_TARGET_BYTES = 4_096
    /** Beyond this the art is dropped rather than sent. */
    const val ART_MAX_BYTES = 6_144

    /**
     * Walks [ART_LADDER] with [encode] (edge, quality -> JPEG, or null): the first result within [ART_TARGET_BYTES] wins; if none is,
     * the smallest within [ART_MAX_BYTES]; otherwise null (no art). Stops encoding as soon as one fits.
     */
    fun chooseArt(encode: (edge: Int, quality: Int) -> ByteArray?): ByteArray? {
        var best: ByteArray? = null
        for ((edge, quality) in ART_LADDER) {
            val b = encode(edge, quality) ?: continue
            if (b.size <= ART_TARGET_BYTES) return b
            if (best == null || b.size < best.size) best = b
        }
        return best?.takeIf { it.size <= ART_MAX_BYTES }
    }
}
