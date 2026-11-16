package dev.ilgax.utils.remote

import dev.ilgax.utils.protocol.Hmac
import dev.ilgax.utils.protocol.Status
import dev.ilgax.utils.protocol.UpdateDataFrame
import dev.ilgax.utils.protocol.WindowAssembler
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

class UpdateDataServerTest {
    private val rnd = SecureRandom()
    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }

    private val apk = bytes(40_000)
    private val key = bytes(32)
    private val mac: (ByteArray) -> ByteArray = { Hmac.sha256(key, it) }
    private val nonce = bytes(16)
    private val token = bytes(16)

    private var live: UpdateDataServer.Source? = UpdateDataServer.Source(token, apk.size) { ByteArrayInputStream(apk) }
    private var now = 1_000L
    private val server = UpdateDataServer({ t -> live?.takeIf { it.token.contentEquals(t) } }, clock = { now })

    private fun request(offset: Int, window: Int = 16 * 1024, tok: ByteArray = token, n: ByteArray = nonce, m: (ByteArray) -> ByteArray = mac) =
        UpdateDataFrame.encodeRequest(m, n, tok, offset, window)

    private fun window(r: UpdateDataServer.Result) = r as UpdateDataServer.Result.Window
    private fun rejected(r: UpdateDataServer.Result) = (r as UpdateDataServer.Result.Rejected).status

    @Test fun servesAWindowAsChunksThatReassemble() {
        val w = window(server.handle("A", mac, nonce, 247, request(0)))
        assertEquals(16 * 1024, w.end)
        assertEquals(apk.size, w.total)
        assertTrue(w.frames.all { it.size <= 247 - 3 })
        assertEquals(Math.ceil(16 * 1024 / 236.0).toInt(), w.frames.size)
        val a = WindowAssembler(0, 16 * 1024)
        val out = ByteArrayOutputStream()
        for (f in w.frames) a.accept(f) { b, from, n -> out.write(b, from, n) }
        assertTrue(a.complete)
        assertArrayEquals(apk.copyOf(16 * 1024), out.toByteArray())
    }

    @Test fun wholeApkInWindowsEndingWithAShortOne() {
        val out = ByteArrayOutputStream()
        var offset = 0
        var windows = 0
        while (offset < apk.size) {
            val w = window(server.handle("A", mac, nonce, 517, request(offset)))
            for (f in w.frames) {
                val c = UpdateDataFrame.decodeChunk(f)!!
                assertEquals(out.size(), c.offset) // in order, no gaps
                out.write(c.data)
            }
            server.release("A")
            offset = w.end
            windows++
        }
        assertEquals(3, windows) // 16 KB + 16 KB + 7232
        assertArrayEquals(apk, out.toByteArray())
    }

    @Test fun resumeFromAnyOffsetServesTheRightBytes() {
        val w = window(server.handle("A", mac, nonce, 247, request(12_345, 1000)))
        val c = UpdateDataFrame.decodeChunk(w.frames.first())!!
        assertEquals(12_345, c.offset)
        assertArrayEquals(apk.copyOfRange(12_345, 12_345 + c.data.size), c.data)
        assertEquals(13_345, w.end)
    }

    @Test fun offsetAtTheEndAnswersOneEmptyChunk() {
        val w = window(server.handle("A", mac, nonce, 247, request(apk.size)))
        assertEquals(1, w.frames.size)
        val c = UpdateDataFrame.decodeChunk(w.frames[0])!!
        assertEquals(0, c.data.size)
        assertEquals(apk.size, c.total)
    }

    @Test fun offsetPastTheEndFails() {
        assertEquals(Status.FAILED, rejected(server.handle("A", mac, nonce, 247, request(apk.size + 1))))
    }

    @Test fun unauthenticatedRequestsAreRejectedBeforeAnythingElse() {
        val wrongKey: (ByteArray) -> ByteArray = { Hmac.sha256(bytes(32), it) }
        assertEquals(Status.AUTH_FAILED, rejected(server.handle("A", mac, nonce, 247, request(0, m = wrongKey))))
        assertEquals(Status.AUTH_FAILED, rejected(server.handle("A", mac, nonce, 247, request(0, n = bytes(16))))) // replay under another nonce
        assertEquals(Status.AUTH_FAILED, rejected(server.handle("A", mac, nonce, 247, request(0).also { it[20] = (it[20].toInt() xor 1).toByte() })))
        assertEquals(Status.AUTH_FAILED, rejected(server.handle("A", mac, nonce, 247, ByteArray(10))))
        // Even with no live offer, a bad MAC is AUTH_FAILED, not UPDATE_GONE: nothing leaks to an unauthenticated peer.
        live = null
        assertEquals(Status.AUTH_FAILED, rejected(server.handle("A", mac, nonce, 247, request(0, m = wrongKey))))
    }

    @Test fun staleOrWrongTokenIsGone() {
        assertEquals(Status.UPDATE_GONE, rejected(server.handle("A", mac, nonce, 247, request(0, tok = bytes(16)))))
        live = null // offer expired, cancelled or replaced
        assertEquals(Status.UPDATE_GONE, rejected(server.handle("A", mac, nonce, 247, request(0))))
    }

    @Test fun newOfferTokenInvalidatesTheOldOne() {
        val newToken = bytes(16)
        live = UpdateDataServer.Source(newToken, apk.size) { ByteArrayInputStream(apk) }
        assertEquals(Status.UPDATE_GONE, rejected(server.handle("A", mac, nonce, 247, request(0))))
        assertTrue(server.handle("A", mac, nonce, 247, request(0, tok = newToken)) is UpdateDataServer.Result.Window)
    }

    @Test fun onlyOneTransferAtATime() {
        window(server.handle("A", mac, nonce, 247, request(0)))
        assertEquals(Status.UPDATE_BUSY, rejected(server.handle("B", mac, nonce, 247, request(0))))
        // The same device may re-request (its window timed out); it supersedes itself.
        window(server.handle("A", mac, nonce, 247, request(0)))
        server.release("A")
        window(server.handle("B", mac, nonce, 247, request(0)))
    }

    @Test fun aStuckWindowStopsBlockingOthersAfterAWhile() {
        window(server.handle("A", mac, nonce, 247, request(0)))
        now += UpdateDataServer.ACTIVE_MAX_MS - 1
        assertEquals(Status.UPDATE_BUSY, rejected(server.handle("B", mac, nonce, 247, request(0))))
        now += 2
        window(server.handle("B", mac, nonce, 247, request(0)))
    }

    @Test fun releaseOfAnotherDeviceDoesNotFreeTheHolder() {
        window(server.handle("A", mac, nonce, 247, request(0)))
        server.release("B")
        assertEquals(Status.UPDATE_BUSY, rejected(server.handle("B", mac, nonce, 247, request(0))))
    }

    @Test fun rejectedRequestsDoNotTakeTheTransferSlot() {
        server.handle("A", mac, nonce, 247, request(0, tok = bytes(16)))
        window(server.handle("B", mac, nonce, 247, request(0)))
    }

    @Test fun tinyMtuIsRefused() {
        assertEquals(Status.FAILED, rejected(server.handle("A", mac, nonce, 23, request(0))))
    }

    @Test fun unreadableAssetIsAFailureNotACrash() {
        live = UpdateDataServer.Source(token, apk.size) { ByteArrayInputStream(ByteArray(100)) } // shorter than advertised
        assertEquals(Status.FAILED, rejected(server.handle("A", mac, nonce, 247, request(0))))
        // The slot was not taken by the failed read.
        live = UpdateDataServer.Source(token, apk.size) { ByteArrayInputStream(apk) }
        window(server.handle("B", mac, nonce, 247, request(0)))
    }
}
