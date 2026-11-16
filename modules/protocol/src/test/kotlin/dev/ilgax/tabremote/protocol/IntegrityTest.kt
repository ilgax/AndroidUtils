package dev.ilgax.tabremote.protocol

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

class IntegrityTest {
    private val rnd = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun macOf(key: ByteArray): (ByteArray) -> ByteArray = { Hmac.sha256(key, it) }

    private val key = bytes(32)
    private val nonce = bytes(16)
    private val apk = bytes(32)
    private val cert = bytes(32)
    private val report = IntegrityReport(6, apk, cert)

    @Test fun frameRoundTrip() {
        val f = IntegrityFrame.encode(macOf(key), nonce, report)
        assertEquals(IntegrityFrame.SIZE, f.size)
        assertEquals(report, IntegrityFrame.verify(macOf(key), nonce, f))
    }

    @Test fun wrongNonceOrKeyRejected() {
        val f = IntegrityFrame.encode(macOf(key), nonce, report)
        assertNull(IntegrityFrame.verify(macOf(key), bytes(16), f))
        assertNull(IntegrityFrame.verify(macOf(bytes(32)), nonce, f))
    }

    @Test fun everyTamperedByteAndLengthRejected() {
        val f = IntegrityFrame.encode(macOf(key), nonce, report)
        for (i in f.indices) {
            val t = f.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("byte $i", IntegrityFrame.verify(macOf(key), nonce, t))
        }
        for (len in 0 until f.size) assertNull(IntegrityFrame.verify(macOf(key), nonce, f.copyOf(len)))
        assertNull(IntegrityFrame.verify(macOf(key), nonce, f + byteArrayOf(0)))
    }

    @Test fun commandFrameCannotBeReplayedAsReport() {
        // Different domain prefix: a tag computed for another frame type over the same bytes must not verify.
        val body = f(report).copyOf(IntegrityFrame.SIZE - Protocol.TAG_SIZE)
        val foreign = body + Hmac.sha256(key, "cmd".toByteArray() + nonce + body).copyOf(Protocol.TAG_SIZE)
        assertNull(IntegrityFrame.verify(macOf(key), nonce, foreign))
    }

    private fun f(r: IntegrityReport) = IntegrityFrame.encode(macOf(key), nonce, r)

    @Test fun badReportRefusedOnEncode() {
        assertThrows(IllegalArgumentException::class.java) { IntegrityFrame.encode(macOf(key), nonce, IntegrityReport(1, bytes(31), cert)) }
        assertThrows(IllegalArgumentException::class.java) { IntegrityFrame.encode(macOf(key), nonce, IntegrityReport(-1, apk, cert)) }
        assertThrows(IllegalArgumentException::class.java) { IntegrityFrame.encode(macOf(key), bytes(3), report) }
    }

    private val expected = ExpectedBuild(6, apk, cert)

    @Test fun verdicts() {
        assertEquals(IntegrityVerdict.VERIFIED, WatchIntegrity.assess(expected, report))
        assertEquals(IntegrityVerdict.CORRUPTED, WatchIntegrity.assess(expected, IntegrityReport(6, bytes(32), cert)))
        assertEquals(IntegrityVerdict.DIFFERENT_BUILD, WatchIntegrity.assess(expected, IntegrityReport(5, bytes(32), cert)))
        assertEquals(IntegrityVerdict.WRONG_KEY, WatchIntegrity.assess(expected, IntegrityReport(6, apk, bytes(32))))
    }

    @Test fun wrongKeyWinsOverEverythingElse() {
        assertEquals(IntegrityVerdict.WRONG_KEY, WatchIntegrity.assess(expected, IntegrityReport(5, bytes(32), bytes(32))))
    }

    @Test fun unknownExpectedCertSkipsKeyCheck() {
        val e = ExpectedBuild(6, apk, null)
        assertEquals(IntegrityVerdict.VERIFIED, WatchIntegrity.assess(e, IntegrityReport(6, apk, bytes(32))))
        assertEquals(IntegrityVerdict.CORRUPTED, WatchIntegrity.assess(e, IntegrityReport(6, bytes(32), bytes(32))))
    }

    @Test fun sameKeyNeverMatchesUnknown() {
        assertTrue(WatchIntegrity.sameKey(cert, cert.copyOf()))
        assertFalse(WatchIntegrity.sameKey(cert, bytes(32)))
        assertFalse(WatchIntegrity.sameKey(null, null))
        assertFalse(WatchIntegrity.sameKey(cert, null))
    }

    @Test fun certDigest() {
        val a = bytes(100)
        val b = bytes(100)
        val sha = { x: ByteArray -> MessageDigest.getInstance("SHA-256").digest(x) }
        assertNull(WatchIntegrity.certDigest(emptyList()))
        assertArrayEquals(sha(a), WatchIntegrity.certDigest(listOf(a)))
        assertArrayEquals(WatchIntegrity.certDigest(listOf(a, b)), WatchIntegrity.certDigest(listOf(b, a)))
        assertFalse(WatchIntegrity.certDigest(listOf(a, b))!!.contentEquals(sha(a)))
    }

    @Test fun hexParsing() {
        assertArrayEquals(byteArrayOf(0x97.toByte(), 0xce.toByte(), 1), WatchIntegrity.fromHex("97ce01"))
        assertNull(WatchIntegrity.fromHex("97c"))
        assertNull(WatchIntegrity.fromHex("zz"))
        assertNull(WatchIntegrity.fromHex(null))
    }

    // Offer flags: force and verify-request.
    private fun offer(force: Boolean) = UpdateOffer(6, 4000, bytes(16), bytes(32), 3, listOf("10.0.0.2"), force)

    @Test fun offerForceFlagRoundTrips() {
        for (force in listOf(false, true)) for (verify in listOf(false, true)) {
            val o = offer(force)
            val r = OfferFrame.verify(macOf(key), nonce, OfferFrame.encode(macOf(key), nonce, o, verify)) as OfferFrame.Result.Valid
            assertEquals(o, r.offer)
            assertEquals(force, r.offer!!.force)
            assertEquals(verify, r.verifyRequested)
        }
    }

    @Test fun verifyRequestWithoutOffer() {
        val r = OfferFrame.verify(macOf(key), nonce, OfferFrame.encode(macOf(key), nonce, null, true)) as OfferFrame.Result.Valid
        assertNull(r.offer)
        assertTrue(r.verifyRequested)
        val plain = OfferFrame.verify(macOf(key), nonce, OfferFrame.encode(macOf(key), nonce, null)) as OfferFrame.Result.Valid
        assertFalse(plain.verifyRequested)
    }

    @Test fun reportCodesRoundTrip() {
        for (p in UpdateReport.Phase.entries) {
            val r = UpdateReport.decode(UpdateReport(p, 2).encode())!!
            assertEquals(p, r.phase)
        }
        assertEquals(UpdateReport.Failure.CERT_MISMATCH, UpdateReport.Failure.fromId(UpdateReport.failed(UpdateReport.Failure.CERT_MISMATCH).value))
    }
}
