package dev.ilgax.utils.protocol

/** When the watch keeps its GATT link to the tablet open, when it may skip the scan, and how often it polls. Pure. */
object LinkPolicy {
    /** A link with nothing queued is dropped after this long (a burst of taps reuses it). */
    const val IDLE_MS = 5_000L

    /** The tablet advertises with a resolvable private address that rotates (about every 15 min); trust a remembered one for less. */
    const val DEVICE_CACHE_MS = 10 * 60_000L

    /** Poll interval for an offer while the app is visible and the subscribed link is up: the tablet's ping does the real work. */
    const val POLL_LINKED_MS = 30_000L
    /** Poll interval while visible but there is no live link (it has to be re-established to receive pings). */
    const val POLL_UNLINKED_MS = 5_000L
    const val POLL_BACKOFF_MAX_MS = 60_000L
    /** Screen off or ambient: nothing to do, look again later. */
    const val POLL_SLEEP_MS = 60_000L

    /** Keep the link open: the app is visible, or work is running, queued, or a pairing awaits its confirmation. */
    fun keepOpen(foreground: Boolean, jobRunning: Boolean, queued: Boolean, awaitingConfirm: Boolean): Boolean =
        foreground || jobRunning || queued || awaitingConfirm

    /** A device from an earlier scan may be connected to directly (no scan) while its address is still likely valid. */
    fun cachedDeviceUsable(ageMs: Long): Boolean = ageMs in 0 until DEVICE_CACHE_MS

    /**
     * Delay until the next offer poll. Failures back off 5 s, 10 s, 20 s ... capped at [POLL_BACKOFF_MAX_MS]; a live
     * subscribed link polls rarely; an inactive screen barely at all.
     */
    fun pollDelayMs(interactive: Boolean, linked: Boolean, failures: Int): Long {
        if (!interactive) return POLL_SLEEP_MS
        if (failures > 0) {
            var d = POLL_UNLINKED_MS
            repeat((failures).coerceAtMost(8)) { d = minOf(d * 2, POLL_BACKOFF_MAX_MS) }
            return d
        }
        return if (linked) POLL_LINKED_MS else POLL_UNLINKED_MS
    }

    /** Whether a poll tick should do work: not while the screen is inactive, and not when a live link will be pinged anyway. */
    fun shouldPoll(interactive: Boolean, linked: Boolean, lastPollAgeMs: Long): Boolean =
        interactive && (!linked || lastPollAgeMs >= POLL_LINKED_MS)
}

/** What the watch asks the BLE scanner for, and which results it accepts. Pure. */
object ScanPolicy {
    /** Scan filter on the manufacturer data: [data] with [mask]. The tablet's id for a paired scan, the pairing flag for pairing. */
    class Filter(val data: ByteArray, val mask: ByteArray)

    /** Hardware-filterable: only advertisements of this tablet (or of any tablet in pairing mode) wake the watch. */
    fun filter(wantedTabletId: ByteArray?, pairing: Boolean): Filter {
        val size = Advertisement.SIZE
        val data = ByteArray(size)
        val mask = ByteArray(size)
        if (pairing) {
            data[Protocol.TABLET_ID_SIZE] = 1
            mask[Protocol.TABLET_ID_SIZE] = 1
        } else if (wantedTabletId != null) {
            require(wantedTabletId.size == Protocol.TABLET_ID_SIZE) { "bad tablet id" }
            wantedTabletId.copyInto(data)
            for (i in 0 until Protocol.TABLET_ID_SIZE) mask[i] = 0xFF.toByte()
        }
        return Filter(data, mask)
    }

    /** Final check of a scan result (the hardware filter may be absent or coarser on some controllers). */
    fun accepts(adv: Advertisement, pairing: Boolean, wantedTabletId: ByteArray?): Boolean =
        if (pairing) adv.pairing else wantedTabletId != null && adv.tabletId.contentEquals(wantedTabletId)
}
