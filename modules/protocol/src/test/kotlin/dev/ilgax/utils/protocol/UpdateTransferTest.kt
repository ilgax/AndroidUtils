package dev.ilgax.utils.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class UpdateTransferTest {
    private val rnd = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private val key = bytes(32)
    private val mac: (ByteArray) -> ByteArray = { Hmac.sha256(key, it) }
    private val nonce = bytes(16)
    private val token = bytes(16)

    // region request framing

    @Test fun requestRoundTrip() {
        val f = UpdateDataFrame.encodeRequest(mac, nonce, token, 12345, 16384)
        assertEquals(UpdateDataFrame.REQUEST_SIZE, f.size)
        val r = (UpdateDataFrame.verifyRequest(mac, nonce, f) as UpdateDataFrame.RequestResult.Valid).request
        assertArrayEquals(token, r.token)
        assertEquals(12345, r.offset)
        assertEquals(16384, r.window)
    }

    @Test fun requestFitsTheSmallestMtuWeTry() {
        assertTrue(UpdateDataFrame.MIN_MTU <= 64)
        assertEquals(41, UpdateDataFrame.MIN_MTU)
    }

    @Test fun everyTamperedByteIsRejected() {
        val f = UpdateDataFrame.encodeRequest(mac, nonce, token, 100, 8192)
        for (i in f.indices) {
            val t = f.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            val r = UpdateDataFrame.verifyRequest(mac, nonce, t)
            assertTrue("byte $i", r is UpdateDataFrame.RequestResult.Invalid)
            assertEquals("byte $i", UpdateDataFrame.Failure.AUTH_FAILED, (r as UpdateDataFrame.RequestResult.Invalid).failure)
        }
    }

    @Test fun wrongKeyWrongNonceAndReplayAreRejected() {
        val f = UpdateDataFrame.encodeRequest(mac, nonce, token, 0, 1024)
        val other: (ByteArray) -> ByteArray = { Hmac.sha256(bytes(32), it) }
        assertTrue(UpdateDataFrame.verifyRequest(other, nonce, f) is UpdateDataFrame.RequestResult.Invalid)
        assertTrue(UpdateDataFrame.verifyRequest(mac, bytes(16), f) is UpdateDataFrame.RequestResult.Invalid) // a later nonce
    }

    @Test fun badSizesAreMalformedAndNeverParsed() {
        val f = UpdateDataFrame.encodeRequest(mac, nonce, token, 0, 1024)
        for (bad in listOf(ByteArray(0), f.copyOf(37), f + 0, bytes(38).copyOf(20))) {
            val r = UpdateDataFrame.verifyRequest(mac, nonce, bad) as UpdateDataFrame.RequestResult.Invalid
            assertEquals(UpdateDataFrame.Failure.MALFORMED, r.failure)
        }
        assertTrue(UpdateDataFrame.verifyRequest(mac, bytes(5), f) is UpdateDataFrame.RequestResult.Invalid)
    }

    @Test fun authenticatedButOutOfRangeRequestIsMalformed() {
        // Build frames by hand: encodeRequest refuses these.
        fun signed(offset: Int, window: Int): ByteArray {
            val body = token + byteArrayOf((offset ushr 24).toByte(), (offset ushr 16).toByte(), (offset ushr 8).toByte(), offset.toByte()) +
                byteArrayOf((window ushr 8).toByte(), window.toByte())
            return body + mac("upd".toByteArray() + nonce + body).copyOf(16)
        }
        for (f in listOf(signed(0, 0), signed(0, UpdateDataFrame.WINDOW_MAX + 1), signed(-1, 100))) {
            val r = UpdateDataFrame.verifyRequest(mac, nonce, f) as UpdateDataFrame.RequestResult.Invalid
            assertEquals(UpdateDataFrame.Failure.MALFORMED, r.failure)
        }
        assertThrows(IllegalArgumentException::class.java) { UpdateDataFrame.encodeRequest(mac, nonce, token, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { UpdateDataFrame.encodeRequest(mac, nonce, token, -1, 10) }
    }

    @Test fun updRequestIsNotAValidCommandOrIntegrityFrame() {
        val f = UpdateDataFrame.encodeRequest(mac, nonce, token, 0, 1024)
        assertTrue(CommandFrame.verify(mac, nonce, f) is CommandFrame.Result.Invalid)
        // A command frame cannot be replayed as an update request either (domain separation by label).
        val c = CommandFrame.encode(mac, nonce, Command.PING)
        assertTrue(UpdateDataFrame.verifyRequest(mac, nonce, c) is UpdateDataFrame.RequestResult.Invalid)
    }

    // endregion

    // region chunks

    @Test fun chunkRoundTripAndSizes() {
        assertEquals(236, UpdateDataFrame.chunkPayload(247))
        assertEquals(506, UpdateDataFrame.chunkPayload(517))
        assertEquals(0, UpdateDataFrame.chunkPayload(5))
        val data = bytes(100)
        val c = UpdateDataFrame.decodeChunk(UpdateDataFrame.encodeChunk(500, 1000, data, 10, 50))!!
        assertEquals(500, c.offset)
        assertEquals(1000, c.total)
        assertArrayEquals(data.copyOfRange(10, 60), c.data)
    }

    @Test fun malformedChunksAreRejected() {
        assertNull(UpdateDataFrame.decodeChunk(ByteArray(7)))
        assertNull(UpdateDataFrame.decodeChunk(UpdateDataFrame.encodeChunk(900, 1000, bytes(101)))) // runs past the total
        assertNull(UpdateDataFrame.decodeChunk(UpdateDataFrame.encodeChunk(1001, 1000, ByteArray(0)))) // offset past the total
        assertNull(UpdateDataFrame.decodeChunk(UpdateDataFrame.encodeChunk(0, UpdateDataFrame.MAX_TOTAL + 1, ByteArray(1))))
        assertNull(UpdateDataFrame.decodeChunk(byteArrayOf(-1, 0, 0, 0, 0, 0, 0, 10) + ByteArray(1))) // negative offset
        assertNotNull(UpdateDataFrame.decodeChunk(UpdateDataFrame.encodeChunk(1000, 1000, ByteArray(0))))
    }

    // endregion

    // region reassembly

    private val apk = bytes(5000)

    /** What the tablet would send for a window, [per] bytes per notification. */
    private fun frames(offset: Int, window: Int, per: Int): List<ByteArray> {
        val len = minOf(window, apk.size - offset)
        val out = ArrayList<ByteArray>()
        var at = 0
        do {
            val n = minOf(per, len - at)
            out += UpdateDataFrame.encodeChunk(offset + at, apk.size, apk, offset + at, n)
            at += n
        } while (at < len)
        return out
    }

    private class Sink {
        val out = ByteArrayOutputStream()
        val fn: (ByteArray, Int, Int) -> Unit = { b, from, n -> out.write(b, from, n) }
    }

    @Test fun inOrderWindowReassembles() {
        val a = WindowAssembler(0, 2000)
        val s = Sink()
        for (f in frames(0, 2000, 236)) assertEquals(WindowAssembler.Verdict.ACCEPTED, a.accept(f, s.fn))
        assertTrue(a.complete)
        assertEquals(2000, a.next)
        assertEquals(apk.size, a.total)
        assertArrayEquals(apk.copyOf(2000), s.out.toByteArray())
    }

    @Test fun windowsChainUntilTheEndAndLastWindowIsClipped() {
        val s = Sink()
        var offset = 0
        var windows = 0
        var total = -1
        while (total < 0 || offset < total) {
            val a = WindowAssembler(offset, 2048, total)
            for (f in frames(offset, 2048, 500)) a.accept(f, s.fn)
            assertTrue(a.complete)
            total = a.total
            offset = a.next
            windows++
        }
        assertEquals(3, windows) // 2048 + 2048 + 904
        assertArrayEquals(apk, s.out.toByteArray())
    }

    @Test fun lostChunkIsAGapAndNothingAfterItIsWritten() {
        val a = WindowAssembler(0, 2000)
        val s = Sink()
        val f = frames(0, 2000, 500)
        assertEquals(WindowAssembler.Verdict.ACCEPTED, a.accept(f[0], s.fn))
        assertEquals(WindowAssembler.Verdict.GAP, a.accept(f[2], s.fn)) // f[1] lost
        assertEquals(WindowAssembler.Verdict.GAP, a.failure)
        assertEquals(500, a.next) // resume point = last good byte
        assertFalse(a.complete)
        assertArrayEquals(apk.copyOf(500), s.out.toByteArray())
    }

    @Test fun duplicateAndOverlappingChunksDoNotCorruptTheFile() {
        val a = WindowAssembler(0, 2000)
        val s = Sink()
        val f = frames(0, 2000, 500)
        a.accept(f[0], s.fn)
        assertEquals(WindowAssembler.Verdict.DUPLICATE, a.accept(f[0], s.fn))
        // A chunk that restarts earlier but extends the file contributes only its new tail.
        val overlap = UpdateDataFrame.encodeChunk(400, apk.size, apk, 400, 300)
        assertEquals(WindowAssembler.Verdict.ACCEPTED, a.accept(overlap, s.fn))
        assertEquals(700, a.next)
        assertArrayEquals(apk.copyOf(700), s.out.toByteArray())
    }

    @Test fun chunkBeyondTheWindowOrWithAnotherTotalIsInvalid() {
        val a = WindowAssembler(0, 1000)
        val s = Sink()
        assertEquals(WindowAssembler.Verdict.INVALID, a.accept(UpdateDataFrame.encodeChunk(900, apk.size, apk, 900, 200), s.fn))
        assertEquals(0, s.out.size())
        assertEquals(WindowAssembler.Verdict.ACCEPTED, a.accept(frames(0, 1000, 500)[0], s.fn))
        assertEquals(WindowAssembler.Verdict.INVALID, a.accept(UpdateDataFrame.encodeChunk(500, apk.size + 1, apk, 500, 100), s.fn))
        assertEquals(WindowAssembler.Verdict.INVALID, a.accept(ByteArray(3), s.fn))
        assertEquals(WindowAssembler.Verdict.INVALID, a.failure)
    }

    @Test fun knownTotalIsEnforcedAcrossWindows() {
        val a = WindowAssembler(2000, 2000, knownTotal = apk.size)
        assertEquals(WindowAssembler.Verdict.INVALID, a.accept(UpdateDataFrame.encodeChunk(2000, 4000, apk, 2000, 100), Sink().fn))
    }

    @Test fun emptyWindowAtTheEndCompletes() {
        val a = WindowAssembler(apk.size, 1024)
        assertFalse(a.complete)
        a.accept(UpdateDataFrame.encodeChunk(apk.size, apk.size, ByteArray(0)), Sink().fn)
        assertTrue(a.complete)
    }

    @Test fun totalSmallerThanTheStartIsInvalid() {
        val a = WindowAssembler(3000, 1024)
        assertEquals(WindowAssembler.Verdict.INVALID, a.accept(UpdateDataFrame.encodeChunk(0, 1000, ByteArray(10)), Sink().fn))
    }

    // endregion

    // region resume

    @Test fun resumeContinuesAtTheLastGoodByte() {
        assertEquals(0, ResumePlan.resumeFrom(0, -1))
        assertEquals(4096, ResumePlan.resumeFrom(4096, -1))
        assertEquals(4096, ResumePlan.resumeFrom(4096, 216000))
        assertEquals(216000, ResumePlan.resumeFrom(216000, 216000)) // complete, only the hash is left
    }

    @Test fun resumeRestartsWhenThePartialFileCannotBeTrusted() {
        assertEquals(0, ResumePlan.resumeFrom(5000, 4000)) // longer than the APK
        assertEquals(0, ResumePlan.resumeFrom(5000, 9000, sameBuild = false)) // another offer's APK
        assertEquals(0, ResumePlan.resumeFrom(-1, -1))
        assertEquals(0, ResumePlan.resumeFrom(Long.MAX_VALUE, -1))
    }

    @Test fun resumeAfterAGapProducesTheSameFile() {
        val s = Sink()
        var offset = 0
        var total = -1
        var dropOnce = true
        while (total < 0 || offset < total) {
            val a = WindowAssembler(offset, 2048, total)
            val fs = frames(offset, 2048, 500)
            for ((i, f) in fs.withIndex()) {
                if (dropOnce && offset == 2048 && i == 1) { dropOnce = false; continue } // a notification lost on the air
                if (a.accept(f, s.fn) == WindowAssembler.Verdict.GAP) break
            }
            total = a.total
            offset = ResumePlan.resumeFrom(s.out.size().toLong(), total)
            assertEquals(a.next, offset)
        }
        assertArrayEquals(apk, s.out.toByteArray())
    }

    // endregion

    // region fallback policy

    private fun offer(bt: Boolean) = UpdateOffer(9, 1234, token, bytes(32), 0, listOf("192.168.1.2"), bluetooth = bt)

    @Test fun startsOnBluetoothOnlyWhenOffered() {
        assertTrue(TransferPolicy.initial(offer(true)) is TransferPolicy.Start.Bluetooth)
        val w = TransferPolicy.initial(offer(false)) as TransferPolicy.Start.Wifi
        assertEquals(TransferPolicy.FallbackReason.NOT_OFFERED, w.reason)
    }

    @Test fun transientFailuresRetryWithBackoff() {
        val t = TransferPolicy.Tracker(0)
        val d1 = t.onWindowFailed(TransferPolicy.WindowFailure.TIMEOUT, 1_000) as TransferPolicy.Decision.Retry
        val d2 = t.onWindowFailed(TransferPolicy.WindowFailure.LINK_LOST, 2_000) as TransferPolicy.Decision.Retry
        assertEquals(500, d1.delayMs)
        assertEquals(1000, d2.delayMs)
    }

    @Test fun threeFailuresInARowFallBack() {
        val t = TransferPolicy.Tracker(0)
        t.onWindowFailed(TransferPolicy.WindowFailure.GAP, 1_000)
        t.onWindowFailed(TransferPolicy.WindowFailure.BAD_FRAME, 2_000)
        val d = t.onWindowFailed(TransferPolicy.WindowFailure.BUSY, 3_000) as TransferPolicy.Decision.Fallback
        assertEquals(TransferPolicy.FallbackReason.REPEATED_FAILURES, d.reason)
    }

    @Test fun progressResetsTheFailureCount() {
        val t = TransferPolicy.Tracker(0)
        repeat(10) { i ->
            val now = 1_000L * (i + 1)
            t.onProgress(now) // a window that moved the offset forward, then a drop
            assertTrue(t.onWindowFailed(TransferPolicy.WindowFailure.LINK_LOST, now + 1) is TransferPolicy.Decision.Retry)
        }
    }

    @Test fun noProgressForTwentySecondsIsAStall() {
        val t = TransferPolicy.Tracker(0)
        assertFalse(t.stalled(19_999))
        assertTrue(t.stalled(20_000))
        val d = t.onWindowFailed(TransferPolicy.WindowFailure.TIMEOUT, 20_000) as TransferPolicy.Decision.Fallback
        assertEquals(TransferPolicy.FallbackReason.STALLED, d.reason)
        t.onProgress(20_500)
        assertFalse(t.stalled(30_000))
    }

    @Test fun fatalFailuresFallBackAtOnce() {
        val cases = mapOf(
            TransferPolicy.WindowFailure.NO_CHANNEL to TransferPolicy.FallbackReason.NO_CHANNEL,
            TransferPolicy.WindowFailure.MTU_TOO_SMALL to TransferPolicy.FallbackReason.MTU_TOO_SMALL,
            TransferPolicy.WindowFailure.REJECTED to TransferPolicy.FallbackReason.REJECTED,
        )
        for ((failure, reason) in cases) {
            val d = TransferPolicy.Tracker(0).onWindowFailed(failure, 10) as TransferPolicy.Decision.Fallback
            assertEquals(reason, d.reason)
        }
    }

    // endregion

    // region offer flag compatibility

    private fun encode(o: UpdateOffer?) = OfferFrame.encode(mac, nonce, o)
    private fun decode(f: ByteArray) = (OfferFrame.verify(mac, nonce, f) as OfferFrame.Result.Valid).offer

    @Test fun bluetoothFlagRoundTripsAndDefaultsOff() {
        assertTrue(decode(encode(offer(true)))!!.bluetooth)
        assertFalse(decode(encode(offer(false)))!!.bluetooth)
        assertFalse(UpdateOffer(1, 1, token, bytes(32), 0, listOf("10.0.0.1")).bluetooth)
        val a = offer(true)
        assertNotEquals(a, UpdateOffer(a.versionCode, a.port, a.token, a.sha256, a.ageSec, a.addresses, bluetooth = false))
    }

    @Test fun flagIsOneBitInTheSameFormatAndLength() {
        val a = encode(offer(true))
        val b = encode(offer(false))
        assertEquals(b.size, a.size)
        assertEquals(1, a[0].toInt()) // format unchanged
        assertEquals(b[1].toInt() or 8, a[1].toInt())
    }

    @Test fun anOldWatchParsesTheNewOfferByIgnoringTheFlag() {
        // The pre-Bluetooth parser: flags bit0/1/2 only, exact length. Re-implemented here from the old OfferFrame.verify.
        val frame = encode(offer(true).let { UpdateOffer(it.versionCode, it.port, it.token, it.sha256, 3, it.addresses, force = true, bluetooth = true) })
        val body = frame.copyOfRange(0, frame.size - OfferFrame.TAG_SIZE)
        val flags = body[1].toInt() and 0xFF
        assertTrue(flags and 1 != 0)
        assertTrue(flags and 2 != 0) // force still readable
        val n = body[2 + 4 + 2 + 2 + 16 + 32].toInt() and 0xFF
        assertEquals(2 + 4 + 2 + 2 + 16 + 32 + 1 + n * 4, body.size) // old exact-length check still holds
    }

    @Test fun newWatchReadsAnOldTabletOffer() {
        // An old tablet never sets bit 3: flags 0b001 (offer) with the same layout.
        val bytes = encode(offer(false))
        assertEquals(0, bytes[1].toInt() and 8)
        val o = decode(bytes)!!
        assertFalse(o.bluetooth)
        assertTrue(TransferPolicy.initial(o) is TransferPolicy.Start.Wifi)
    }

    @Test fun flagIsAuthenticated() {
        val f = encode(offer(false))
        f[1] = (f[1].toInt() or 8).toByte() // a man in the middle switching Bluetooth on
        assertTrue(OfferFrame.verify(mac, nonce, f) is OfferFrame.Result.Invalid)
    }

    @Test fun noOfferFrameIsUnchanged() {
        assertNull(decode(encode(null)))
        assertEquals(2 + OfferFrame.TAG_SIZE, encode(null).size)
    }

    @Test fun newStatusCodesRoundTrip() {
        assertEquals(Status.UPDATE_GONE, Status.fromStatus(0x9D))
        assertEquals(Status.UPDATE_BUSY, Status.fromStatus(0x9E))
    }

    @Test fun expectedTransferTimeIsInTheSecondsNotMinutes() {
        // 211 KB in 16 KB windows: the request overhead is a handful of round trips, not the bottleneck.
        val apk = 211 * 1024
        assertEquals(14, (apk + UpdateDataFrame.WINDOW_MAX - 1) / UpdateDataFrame.WINDOW_MAX)
        assertEquals(916, (apk + UpdateDataFrame.chunkPayload(247) - 1) / UpdateDataFrame.chunkPayload(247))
        assertEquals(428, (apk + UpdateDataFrame.chunkPayload(517) - 1) / UpdateDataFrame.chunkPayload(517))
    }

    // endregion
}
