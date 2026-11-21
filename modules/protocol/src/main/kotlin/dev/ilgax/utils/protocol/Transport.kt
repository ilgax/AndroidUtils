package dev.ilgax.utils.protocol

/** Pure helpers of the media transport commands (NEXT, PREVIOUS, SEEK) and of playback progress. */
object Transport {
    const val MIN_SEEK_SECONDS = -32768
    const val MAX_SEEK_SECONDS = 32767

    /** SEEK args: the signed number of seconds as 2 big-endian bytes (the watch sends -10 and +30). */
    fun encodeSeek(seconds: Int): ByteArray {
        require(seconds in MIN_SEEK_SECONDS..MAX_SEEK_SECONDS) { "seek out of range" }
        return byteArrayOf((seconds shr 8).toByte(), seconds.toByte())
    }

    /** Null unless exactly 2 bytes. */
    fun decodeSeek(args: ByteArray): Int? =
        if (args.size != 2) null else ((args[0].toInt() shl 8) or (args[1].toInt() and 0xFF)).toShort().toInt()

    /**
     * Where a seek by [deltaSeconds] from [positionMs] lands: clamped to 0..[durationMs]. An unknown duration (<= 0) only clamps
     * at the start.
     */
    fun seekTarget(positionMs: Long, deltaSeconds: Int, durationMs: Long): Long {
        val t = positionMs + deltaSeconds * 1000L
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return t.coerceIn(0L, upper)
    }

    /**
     * Playback position [elapsedMs] after it was [positionMs] at [speedPercent] (100 = normal, 0 = paused, negative = rewinding),
     * clamped to 0..[durationMs] (an unknown duration only clamps at 0). Used by the tablet to read "now" out of a session's
     * last update and by the watch to animate its progress between updates.
     */
    fun positionAt(positionMs: Long, speedPercent: Int, elapsedMs: Long, durationMs: Long): Long {
        val moved = if (elapsedMs <= 0) 0L else elapsedMs * speedPercent / 100
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return (positionMs + moved).coerceIn(0L, upper)
    }

    /** Progress 0..1, or null when the duration is unknown. */
    fun fraction(positionMs: Long, durationMs: Long): Float? =
        if (durationMs <= 0) null else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/**
 * Turns bezel ticks into at most one volume command at a time. The watch sends VOLUME_UP / VOLUME_DOWN one step each, so a fast
 * spin must not queue dozens of them: pending steps are summed (opposite directions cancel), capped at [maxPending], and handed
 * out one by one, no sooner than [intervalMs] after the previous one and only after it was acknowledged. Pure; the caller supplies
 * the clock.
 */
class VolumeCoalescer(private val intervalMs: Long = 90, private val maxPending: Int = 12) {
    private var pending = 0
    private var lastSentAt = Long.MIN_VALUE / 2
    private var inFlight = false

    val hasPending: Boolean get() = pending != 0

    /** Adds [steps] (positive = louder). */
    fun add(steps: Int) {
        pending = (pending + steps).coerceIn(-maxPending, maxPending)
    }

    /** The next single step to send now (+1 / -1), or 0 when there is none, one is still in flight, or it is too soon. */
    fun take(nowMs: Long): Int {
        if (pending == 0 || inFlight || nowMs - lastSentAt < intervalMs) return 0
        val step = if (pending > 0) 1 else -1
        pending -= step
        inFlight = true
        lastSentAt = nowMs
        return step
    }

    /** The command taken last has been answered (whatever the result). */
    fun done() {
        inFlight = false
    }

    /** Milliseconds until [take] may hand out the next step (0 = now or when the in-flight one is done). */
    fun waitMs(nowMs: Long): Long = (intervalMs - (nowMs - lastSentAt)).coerceAtLeast(0)

    fun clear() {
        pending = 0
    }
}

/** Collects fractional rotary scroll units into whole volume steps; a reversal drops the leftover of the other direction. */
class RotaryAccumulator(private val stepUnits: Float = 1f) {
    private var acc = 0f

    /** [units]: positive = clockwise (louder). Returns the whole signed steps now complete. */
    fun feed(units: Float): Int {
        if (units == 0f) return 0
        if ((units > 0) != (acc > 0) && acc != 0f) acc = 0f
        acc += units
        val steps = (acc / stepUnits).toInt()
        acc -= steps * stepUnits
        return steps
    }
}
