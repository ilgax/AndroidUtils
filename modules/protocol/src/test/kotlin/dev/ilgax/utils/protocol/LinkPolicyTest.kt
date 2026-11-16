package dev.ilgax.utils.protocol

import org.junit.Assert.*
import org.junit.Test

class LinkPolicyTest {
    @Test fun linkStaysOpenOnlyWhileSomethingNeedsIt() {
        assertFalse(LinkPolicy.keepOpen(foreground = false, jobRunning = false, queued = false, awaitingConfirm = false))
        assertTrue(LinkPolicy.keepOpen(true, false, false, false))
        assertTrue(LinkPolicy.keepOpen(false, true, false, false))
        assertTrue(LinkPolicy.keepOpen(false, false, true, false))
        assertTrue(LinkPolicy.keepOpen(false, false, false, true))
    }

    @Test fun idleTimeoutIsAFewSeconds() {
        assertTrue(LinkPolicy.IDLE_MS in 3_000..8_000)
    }

    @Test fun cachedDeviceExpiresBeforeAddressRotation() {
        assertTrue(LinkPolicy.cachedDeviceUsable(0))
        assertTrue(LinkPolicy.cachedDeviceUsable(LinkPolicy.DEVICE_CACHE_MS - 1))
        assertFalse(LinkPolicy.cachedDeviceUsable(LinkPolicy.DEVICE_CACHE_MS))
        assertFalse(LinkPolicy.cachedDeviceUsable(-1))
        assertTrue(LinkPolicy.DEVICE_CACHE_MS < 15 * 60_000L)
    }

    @Test fun pollBacksOffOnFailuresAndSleepsWithTheScreenOff() {
        assertEquals(5_000, LinkPolicy.pollDelayMs(interactive = true, linked = false, failures = 0))
        assertEquals(30_000, LinkPolicy.pollDelayMs(true, true, 0))
        assertEquals(10_000, LinkPolicy.pollDelayMs(true, false, 1))
        assertEquals(20_000, LinkPolicy.pollDelayMs(true, false, 2))
        assertEquals(60_000, LinkPolicy.pollDelayMs(true, false, 4))
        assertEquals(60_000, LinkPolicy.pollDelayMs(true, false, 1000))
        assertEquals(LinkPolicy.POLL_SLEEP_MS, LinkPolicy.pollDelayMs(false, true, 0))
        assertEquals(LinkPolicy.POLL_SLEEP_MS, LinkPolicy.pollDelayMs(false, false, 3))
    }

    @Test fun aLiveLinkIsNotPolledAtTheFastRate() {
        assertTrue(LinkPolicy.shouldPoll(interactive = true, linked = false, lastPollAgeMs = 0))
        assertFalse(LinkPolicy.shouldPoll(true, true, 5_000))
        assertTrue(LinkPolicy.shouldPoll(true, true, LinkPolicy.POLL_LINKED_MS))
        assertFalse(LinkPolicy.shouldPoll(false, false, 999_999))
    }

    @Test fun pairedScanFiltersOnTheTabletId() {
        val id = byteArrayOf(1, 2, 3, 4)
        val f = ScanPolicy.filter(id, pairing = false)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 0), f.data)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1, 0), f.mask)
    }

    @Test fun pairingScanFiltersOnTheFlagBitOnly() {
        val f = ScanPolicy.filter(null, pairing = true)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 1), f.data)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 1), f.mask)
    }

    @Test fun filterMatchesWhatTheTabletAdvertises() {
        val id = byteArrayOf(9, 8, 7, 6)
        for (pairingAdv in listOf(false, true)) {
            val adv = Advertisement.encode(id, pairingAdv)
            val f = ScanPolicy.filter(id, pairing = false)
            val hw = adv.indices.all { (adv[it].toInt() and f.mask[it].toInt()) == (f.data[it].toInt() and f.mask[it].toInt()) }
            assertTrue(hw)
        }
        val other = Advertisement.encode(byteArrayOf(1, 1, 1, 1), false)
        val f = ScanPolicy.filter(id, false)
        assertFalse(other.indices.all { (other[it].toInt() and f.mask[it].toInt()) == (f.data[it].toInt() and f.mask[it].toInt()) })
    }

    @Test fun acceptsOnlyTheWantedTabletOrAnyInPairingMode() {
        val id = byteArrayOf(1, 2, 3, 4)
        val mine = Advertisement(id, pairing = false)
        val other = Advertisement(byteArrayOf(5, 5, 5, 5), pairing = false)
        val pairingTablet = Advertisement(byteArrayOf(5, 5, 5, 5), pairing = true)
        assertTrue(ScanPolicy.accepts(mine, false, id))
        assertFalse(ScanPolicy.accepts(other, false, id))
        assertFalse(ScanPolicy.accepts(mine, false, null))
        assertTrue(ScanPolicy.accepts(pairingTablet, true, null))
        assertFalse(ScanPolicy.accepts(other, true, null))
    }
}
