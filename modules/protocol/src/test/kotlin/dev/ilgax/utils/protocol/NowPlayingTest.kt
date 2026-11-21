package dev.ilgax.utils.protocol

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

class NowPlayingTest {
    private val rnd = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun macOf(key: ByteArray): (ByteArray) -> ByteArray = { Hmac.sha256(key, it) }

    private val key = bytes(32)
    private val nonce = bytes(16)
    private val mac = macOf(key)

    private val sample = NowPlaying(
        access = true, state = PlayState.PLAYING, canNext = true, canPrevious = true, canSeek = true,
        speedPercent = 100, positionMs = 61_500, durationMs = 215_000, volume = 7, volumeMax = 15,
        artHash = 0x1234ABCD, artSize = 3_900, app = "Spotify", title = "Şişli'de Bir Gün", artist = "İlkay", album = "Çeşitli",
    )

    private fun valid(r: NowPlayingFrame.Result) = (r as NowPlayingFrame.Result.Valid).state

    // region commands

    @Test fun newCommandIdsAreStable() {
        assertEquals(16, Command.NEXT.id)
        assertEquals(17, Command.PREVIOUS.id)
        assertEquals(18, Command.SEEK.id)
        assertEquals(19, Command.GET_NOW_PLAYING.id)
        assertEquals(20, Command.GET_ART.id)
        assertEquals(0x9F, Status.NOT_AVAILABLE.code)
        assertEquals(Status.NOT_AVAILABLE, Status.fromStatus(0x9F))
    }

    @Test fun seekFrameRoundTripsAndFitsTheDefaultAtt() {
        for (s in listOf(-10, 30, 0, -32768, 32767)) {
            val frame = CommandFrame.encode(mac, nonce, Command.SEEK, Transport.encodeSeek(s))
            assertTrue(frame.size <= 20)
            val r = CommandFrame.verify(mac, nonce, frame) as CommandFrame.Result.Valid
            assertEquals(Command.SEEK, r.command)
            assertEquals(s, Transport.decodeSeek(r.args))
        }
    }

    @Test fun nextAndPreviousFramesVerifyAndTamperIsRejected() {
        for (c in listOf(Command.NEXT, Command.PREVIOUS)) {
            val frame = CommandFrame.encode(mac, nonce, c)
            assertEquals(c, (CommandFrame.verify(mac, nonce, frame) as CommandFrame.Result.Valid).command)
            // Turning NEXT into PREVIOUS (or any other id) breaks the tag.
            val forged = frame.copyOf().also { it[0] = Command.LOCK.id.toByte() }
            assertEquals(Status.AUTH_FAILED, (CommandFrame.verify(mac, nonce, forged) as CommandFrame.Result.Invalid).status)
            assertEquals(Status.AUTH_FAILED, (CommandFrame.verify(mac, bytes(16), frame) as CommandFrame.Result.Invalid).status)
        }
        val seek = CommandFrame.encode(mac, nonce, Command.SEEK, Transport.encodeSeek(30))
        val flipped = seek.copyOf().also { it[2] = (it[2] + 1).toByte() } // +30 -> +31
        assertTrue(CommandFrame.verify(mac, nonce, flipped) is CommandFrame.Result.Invalid)
    }

    @Test fun seekArgsAreStrict() {
        assertNull(Transport.decodeSeek(ByteArray(0)))
        assertNull(Transport.decodeSeek(ByteArray(1)))
        assertNull(Transport.decodeSeek(ByteArray(3)))
        assertThrows(IllegalArgumentException::class.java) { Transport.encodeSeek(40_000) }
        assertThrows(IllegalArgumentException::class.java) { Transport.encodeSeek(-32769) }
    }

    // endregion

    // region seek clamping and progress

    @Test fun seekClampsToTheTrack() {
        assertEquals(40_000L, Transport.seekTarget(30_000, 10, 200_000))
        assertEquals(0L, Transport.seekTarget(4_000, -10, 200_000))
        assertEquals(0L, Transport.seekTarget(0, -10, 200_000))
        assertEquals(200_000L, Transport.seekTarget(190_000, 30, 200_000))
        assertEquals(200_000L, Transport.seekTarget(200_000, 30, 200_000))
        assertEquals(35_000L, Transport.seekTarget(5_000, 30, 200_000))
        // Unknown duration: only the start is clamped.
        assertEquals(60_000L, Transport.seekTarget(30_000, 30, -1))
        assertEquals(0L, Transport.seekTarget(5_000, -10, 0))
    }

    @Test fun progressExtrapolatesAndClamps() {
        assertEquals(11_000L, Transport.positionAt(10_000, 100, 1_000, 200_000))
        assertEquals(10_000L, Transport.positionAt(10_000, 0, 5_000, 200_000)) // paused
        assertEquals(12_000L, Transport.positionAt(10_000, 200, 1_000, 200_000)) // 2x
        assertEquals(9_500L, Transport.positionAt(10_000, -50, 1_000, 200_000)) // rewinding at half speed
        assertEquals(200_000L, Transport.positionAt(199_500, 100, 10_000, 200_000)) // never past the end
        assertEquals(0L, Transport.positionAt(500, -100, 10_000, 200_000)) // never before the start
        assertEquals(10_000L, Transport.positionAt(10_000, 100, -3_000, 200_000)) // clock went backwards
        assertEquals(1_010_000L, Transport.positionAt(10_000, 100, 1_000_000, -1)) // unknown duration
        assertEquals(0.5f, Transport.fraction(100, 200)!!, 0.0001f)
        assertEquals(1f, Transport.fraction(300, 200)!!, 0.0001f)
        assertNull(Transport.fraction(100, -1))
        assertNull(Transport.fraction(100, 0))
    }

    // endregion

    // region state payload

    @Test fun stateRoundTrips() {
        val frame = NowPlayingFrame.encode(mac, nonce, sample)
        assertTrue("${frame.size} bytes", frame.size <= 512)
        assertEquals(sample, valid(NowPlayingFrame.verify(mac, nonce, frame)))
    }

    @Test fun emptyAndNoAccessStatesRoundTrip() {
        for (np in listOf(NowPlaying(), NowPlaying(access = false), NowPlaying(state = PlayState.PAUSED, durationMs = -1))) {
            assertEquals(np, valid(NowPlayingFrame.verify(mac, nonce, NowPlayingFrame.encode(mac, nonce, np))))
        }
    }

    @Test fun flagsAreIndependent() {
        for (bits in 0 until 16) {
            val np = NowPlaying(access = bits and 1 != 0, canNext = bits and 2 != 0, canPrevious = bits and 4 != 0, canSeek = bits and 8 != 0)
            assertEquals(np, valid(NowPlayingFrame.verify(mac, nonce, NowPlayingFrame.encode(mac, nonce, np))))
        }
    }

    @Test fun negativeSpeedAndUnknownDurationSurvive() {
        val np = NowPlaying(state = PlayState.PLAYING, speedPercent = -200, positionMs = 5, durationMs = -1)
        assertEquals(np, valid(NowPlayingFrame.verify(mac, nonce, NowPlayingFrame.encode(mac, nonce, np))))
    }

    @Test fun longTextIsTruncatedOnACodePointBoundary() {
        val long = "Ğ".repeat(500) + "😀".repeat(100)
        val np = sample.copy(app = long, title = long, artist = long, album = long)
        val frame = NowPlayingFrame.encode(mac, nonce, np)
        assertTrue("${frame.size} bytes", frame.size <= NowPlayingFrame.MAX_SIZE)
        val back = valid(NowPlayingFrame.verify(mac, nonce, frame))
        assertTrue(back.title.endsWith("…"))
        assertTrue(back.title.toByteArray().size <= NowPlayingFrame.MAX_TITLE)
        assertTrue(back.app.toByteArray().size <= NowPlayingFrame.MAX_APP)
        assertTrue(back.artist.toByteArray().size <= NowPlayingFrame.MAX_ARTIST)
        assertTrue(back.album.toByteArray().size <= NowPlayingFrame.MAX_ALBUM)
        assertFalse(back.title.contains('�'))
    }

    @Test fun clipNeverSplitsASurrogatePair() {
        for (n in 8..40) {
            val c = NowPlayingFrame.clip("a😀😀😀😀😀😀😀😀😀", n)
            assertTrue(c.toByteArray().size <= n)
            assertTrue(c.none { it == '�' })
            val cps = c.codePoints().toArray()
            assertTrue(cps.all { Character.isValidCodePoint(it) })
            // No lone surrogate halves.
            assertEquals(c, String(c.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
        assertEquals("short", NowPlayingFrame.clip("  short\n", 50))
        assertEquals("a b", NowPlayingFrame.clip("a\tb", 50))
    }

    @Test fun maxSizedStateStaysUnderTheLongReadLimit() {
        val np = sample.copy(
            app = "x".repeat(300), title = "😀".repeat(300), artist = "😀".repeat(300), album = "😀".repeat(300),
        )
        assertTrue(NowPlayingFrame.encode(mac, nonce, np).size <= NowPlayingFrame.MAX_SIZE)
        assertTrue(NowPlayingFrame.MAX_SIZE <= 512)
    }

    @Test fun tamperIsRejected() {
        val frame = NowPlayingFrame.encode(mac, nonce, sample)
        for (i in frame.indices) {
            val bad = frame.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertTrue("byte $i", NowPlayingFrame.verify(mac, nonce, bad) !is NowPlayingFrame.Result.Valid)
        }
    }

    @Test fun wrongKeyNonceOrTruncationIsRejected() {
        val frame = NowPlayingFrame.encode(mac, nonce, sample)
        assertEquals(NowPlayingFrame.Result.AuthFailed, NowPlayingFrame.verify(macOf(bytes(32)), nonce, frame))
        assertEquals(NowPlayingFrame.Result.AuthFailed, NowPlayingFrame.verify(mac, bytes(16), frame)) // replay on another nonce
        assertEquals(NowPlayingFrame.Result.AuthFailed, NowPlayingFrame.verify(mac, nonce, frame.copyOf(frame.size - 1)))
        assertEquals(NowPlayingFrame.Result.Malformed, NowPlayingFrame.verify(mac, nonce, ByteArray(0)))
        assertEquals(NowPlayingFrame.Result.AuthFailed, NowPlayingFrame.verify(mac, nonce, frame + 0))
        assertEquals(NowPlayingFrame.Result.Malformed, NowPlayingFrame.verify(mac, ByteArray(3), frame))
    }

    @Test fun otherProtocolTagsDoNotCrossOver() {
        // A macro page or art page tagged with the same key and nonce is not a now-playing state.
        val art = NowArt.encodePage(mac, nonce, ByteArray(10) { it.toByte() }, 0)
        assertTrue(NowPlayingFrame.verify(mac, nonce, art) !is NowPlayingFrame.Result.Valid)
        val state = NowPlayingFrame.encode(mac, nonce, sample)
        assertNull(NowArt.verifyPage(mac, nonce, state))
    }

    @Test fun validlySignedButMalformedBodiesAreRejected() {
        fun sign(body: ByteArray) = body + mac("now".toByteArray() + nonce + body).copyOf(16)
        val good = NowPlayingFrame.encode(mac, nonce, sample)
        val body = good.copyOf(good.size - 16)
        // Bad format byte.
        assertEquals(NowPlayingFrame.Result.Malformed, NowPlayingFrame.verify(mac, nonce, sign(body.copyOf().also { it[0] = 9 })))
        // Bad state code.
        assertEquals(NowPlayingFrame.Result.Malformed, NowPlayingFrame.verify(mac, nonce, sign(body.copyOf().also { it[2] = 7 })))
        // Invalid UTF-8 in the app name (first text starts at 21, its length byte first).
        val badUtf = body.copyOf().also { it[22] = 0xFF.toByte() }
        assertEquals(NowPlayingFrame.Result.Malformed, NowPlayingFrame.verify(mac, nonce, sign(badUtf)))
        // Text length running past the end.
        assertEquals(NowPlayingFrame.Result.Malformed, NowPlayingFrame.verify(mac, nonce, sign(body.copyOf().also { it[21] = 120.toByte() })))
        // Trailing byte.
        assertEquals(NowPlayingFrame.Result.Malformed, NowPlayingFrame.verify(mac, nonce, sign(body + 0)))
        // Volume above the max.
        assertEquals(NowPlayingFrame.Result.Malformed, NowPlayingFrame.verify(mac, nonce, sign(body.copyOf().also { it[13] = 99 })))
    }

    // endregion

    // region push policy

    @Test fun pushOnFirstStateAndOnAnyVisibleChange() {
        assertTrue(NowPlayingPush.needed(null, 0, sample))
        assertFalse(NowPlayingPush.needed(sample, 0, sample))
        assertTrue(NowPlayingPush.needed(sample, 0, sample.copy(title = "Other")))
        assertTrue(NowPlayingPush.needed(sample, 0, sample.copy(state = PlayState.PAUSED)))
        assertTrue(NowPlayingPush.needed(sample, 0, sample.copy(volume = 8)))
        assertTrue(NowPlayingPush.needed(sample, 0, sample.copy(artHash = 5)))
        assertTrue(NowPlayingPush.needed(sample, 0, sample.copy(canSeek = false)))
        assertTrue(NowPlayingPush.needed(sample, 0, sample.copy(access = false)))
    }

    @Test fun positionOnlyChangesArePushedOnlyWhenTheWatchWouldBeWrong() {
        // 10 s later the watch expects 71.5 s; a player ticking along is no news.
        assertFalse(NowPlayingPush.needed(sample, 10_000, sample.copy(positionMs = 71_600)))
        assertFalse(NowPlayingPush.needed(sample, 10_000, sample.copy(positionMs = 72_900)))
        // A seek is.
        assertTrue(NowPlayingPush.needed(sample, 10_000, sample.copy(positionMs = 120_000)))
        assertTrue(NowPlayingPush.needed(sample, 10_000, sample.copy(positionMs = 30_000)))
        // Paused: the watch expects the same position.
        val paused = sample.copy(state = PlayState.PAUSED, speedPercent = 0)
        assertFalse(NowPlayingPush.needed(paused, 60_000, paused.copy(positionMs = 61_900)))
        assertTrue(NowPlayingPush.needed(paused, 60_000, paused.copy(positionMs = 70_000)))
    }

    // endregion

    // region art

    private fun jpeg(n: Int) = ByteArray(n) { (it * 7 + 3).toByte() }

    private fun pull(art: ByteArray, hash: Int): Pair<ArtAssembler.Verdict, ByteArray?> {
        val asm = ArtAssembler(hash)
        var page = 0
        while (true) {
            val n = bytes(16)
            val frame = NowArt.encodePage(mac, n, art, page)
            assertTrue(frame.size <= NowArt.MAX_SIZE && frame.size <= 512)
            val p = NowArt.verifyPage(mac, n, frame) ?: return ArtAssembler.Verdict.REJECTED to null
            val v = asm.accept(p)
            if (v != ArtAssembler.Verdict.MORE) return v to asm.result
            page++
        }
    }

    @Test fun artRoundTripsAtEverySize() {
        for (size in listOf(1, 399, 400, 401, 800, 3_999, 4_096, 4_097)) {
            val art = jpeg(size)
            val (v, out) = pull(art, NowArt.hashOf(art))
            assertEquals("size $size", ArtAssembler.Verdict.DONE, v)
            assertArrayEquals(art, out)
        }
    }

    @Test fun artHashIsStableNonZeroAndSensitive() {
        val a = jpeg(500)
        assertEquals(NowArt.hashOf(a), NowArt.hashOf(a.copyOf()))
        assertNotEquals(0, NowArt.hashOf(a))
        assertNotEquals(NowArt.hashOf(a), NowArt.hashOf(a.copyOf().also { it[10] = (it[10] + 1).toByte() }))
        assertEquals(0, NowArt.hashOf(ByteArray(0)))
    }

    @Test fun artPageTamperIsRejected() {
        val art = jpeg(950)
        val frame = NowArt.encodePage(mac, nonce, art, 1)
        for (i in frame.indices) {
            val bad = frame.copyOf().also { it[i] = (it[i].toInt() xor 0x40).toByte() }
            assertNull("byte $i", NowArt.verifyPage(mac, nonce, bad))
        }
        assertNull(NowArt.verifyPage(mac, bytes(16), frame))
        assertNull(NowArt.verifyPage(macOf(bytes(32)), nonce, frame))
        assertNull(NowArt.verifyPage(mac, nonce, frame.copyOf(frame.size - 1)))
    }

    @Test fun assemblerRejectsOutOfOrderChangedOrWrongArt() {
        val art = jpeg(1_000)
        val hash = NowArt.hashOf(art)
        fun page(i: Int, a: ByteArray = art) = NowArt.verifyPage(mac, nonce, NowArt.encodePage(mac, nonce, a, i))!!
        // Skipping page 0.
        assertEquals(ArtAssembler.Verdict.REJECTED, ArtAssembler(hash).accept(page(1)))
        // A page of another image (the track changed while pulling).
        val asm = ArtAssembler(hash)
        assertEquals(ArtAssembler.Verdict.MORE, asm.accept(page(0)))
        assertEquals(ArtAssembler.Verdict.REJECTED, asm.accept(page(1, jpeg(1_100))))
        // The state announced one hash, the pages carry another.
        assertEquals(ArtAssembler.Verdict.REJECTED, ArtAssembler(hash + 1).accept(page(0)))
        // "No art" is never accepted as an image.
        assertEquals(ArtAssembler.Verdict.REJECTED, ArtAssembler(0).accept(page(0, ByteArray(0))))
        // Right hash announced but the bytes were altered consistently (hash recomputed over fewer bytes would differ): rejected at the end.
        val other = art.copyOf().also { it[999] = (it[999] + 1).toByte() }
        val a2 = ArtAssembler(hash)
        a2.accept(NowArt.verifyPage(mac, nonce, NowArt.encodePage(mac, nonce, other, 0))!!.let { NowArt.Page(hash, it.total, 0, it.data) })
        val last = NowArt.verifyPage(mac, nonce, NowArt.encodePage(mac, nonce, other, 1))!!
        a2.accept(NowArt.Page(hash, last.total, 1, last.data)).let { assertEquals(ArtAssembler.Verdict.MORE, it) }
        val third = NowArt.verifyPage(mac, nonce, NowArt.encodePage(mac, nonce, other, 2))!!
        assertEquals(ArtAssembler.Verdict.REJECTED, a2.accept(NowArt.Page(hash, third.total, 2, third.data)))
    }

    @Test fun noArtIsAValidEmptyPageZero() {
        val p = NowArt.verifyPage(mac, nonce, NowArt.encodePage(mac, nonce, ByteArray(0), 0))!!
        assertEquals(0, p.hash)
        assertEquals(0, p.total)
        assertEquals(0, p.data.size)
    }

    @Test fun pageArgsRoundTrip() {
        for (p in listOf(0, 1, 9, 255, 256, 65535)) assertEquals(p, NowArt.decodePageArgs(NowArt.encodePageArgs(p)))
        assertNull(NowArt.decodePageArgs(ByteArray(1)))
        val frame = CommandFrame.encode(mac, nonce, Command.GET_ART, NowArt.encodePageArgs(3))
        assertTrue(frame.size <= 20)
    }

    // endregion

    // region volume

    @Test fun coalescerHandsOutOneStepAtATimeAndWaits() {
        val c = VolumeCoalescer(intervalMs = 100, maxPending = 12)
        c.add(3)
        assertEquals(1, c.take(1_000))
        assertEquals(0, c.take(1_050)) // too soon and still in flight
        c.done()
        assertEquals(0, c.take(1_050)) // acknowledged, but the interval has not passed
        assertEquals(1, c.take(1_100))
        c.done()
        assertEquals(1, c.take(1_300))
        c.done()
        assertEquals(0, c.take(1_500))
        assertFalse(c.hasPending)
    }

    @Test fun coalescerWaitsForTheAnswerBeforeTheNextStep() {
        val c = VolumeCoalescer(intervalMs = 10)
        c.add(2)
        assertEquals(1, c.take(0))
        assertEquals(0, c.take(5_000)) // never acknowledged
        c.done()
        assertEquals(1, c.take(5_000))
    }

    @Test fun oppositeTicksCancelAndTheBacklogIsCapped() {
        val c = VolumeCoalescer(intervalMs = 0, maxPending = 5)
        c.add(4); c.add(-4)
        assertFalse(c.hasPending)
        c.add(100)
        var sent = 0
        var t = 0L
        while (true) {
            val s = c.take(t++)
            if (s == 0) break
            assertEquals(1, s)
            sent++
            c.done()
        }
        assertEquals(5, sent)
        c.add(-100)
        assertEquals(-1, c.take(t))
        c.clear()
        c.done()
        assertEquals(0, c.take(t + 1))
    }

    @Test fun waitMsCountsDownToTheNextSlot() {
        val c = VolumeCoalescer(intervalMs = 90)
        c.add(2)
        c.take(1_000)
        assertEquals(90, c.waitMs(1_000))
        assertEquals(40, c.waitMs(1_050))
        assertEquals(0, c.waitMs(2_000))
    }

    @Test fun rotaryAccumulatesFractionsAndDropsLeftoversOnReversal() {
        val r = RotaryAccumulator(1f)
        assertEquals(0, r.feed(0.4f))
        assertEquals(0, r.feed(0.4f))
        assertEquals(1, r.feed(0.4f)) // 1.2 -> one step, 0.2 left
        assertEquals(0, r.feed(-0.5f)) // reversal drops the leftover
        assertEquals(-1, r.feed(-0.6f))
        assertEquals(3, r.feed(3.2f))
        assertEquals(-2, RotaryAccumulator(1f).feed(-2.5f))
        assertEquals(0, RotaryAccumulator(1f).feed(0f))
    }

    // endregion
}
