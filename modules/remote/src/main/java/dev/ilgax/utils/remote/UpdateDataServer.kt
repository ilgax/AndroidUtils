package dev.ilgax.utils.remote

import dev.ilgax.utils.protocol.Status
import dev.ilgax.utils.protocol.UpdateDataFrame
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Tablet side of the Bluetooth APK transfer: turns one authenticated UPDATE_DATA request into the notification frames of its
 * window. No Android types, so it is unit tested on the JVM; [GattController] owns the radio and sends the frames one by one.
 *
 * A request is served only if its MAC verifies against the connection's nonce, its token is the live offer's ([source]) and no
 * other watch is mid-window. One window at a time keeps the radio and the GATT thread free for commands in between.
 */
class UpdateDataServer(private val source: (token: ByteArray) -> Source?, private val clock: () -> Long = System::currentTimeMillis) {
    /** The APK of the live offer; [open] returns a fresh stream each call. */
    class Source(val token: ByteArray, val total: Int, val open: () -> InputStream)

    sealed interface Result {
        /** [frames] are the notification payloads in order; [end] is the offset after the last byte of the window. */
        class Window(val frames: List<ByteArray>, val end: Int, val total: Int) : Result
        class Rejected(val status: Status) : Result
    }

    private var active: String? = null
    private var activeSince = 0L

    /**
     * [mtu] is the link's negotiated MTU; chunks are sized to fit it. [mac] is the pairing key's HMAC. Rejections: AUTH_FAILED for
     * anything that does not verify, UPDATE_GONE for a token that is not the live offer, UPDATE_BUSY while another device holds
     * a window, FAILED for a range outside the APK or an MTU too small to carry data.
     */
    @Synchronized
    fun handle(device: String, mac: (ByteArray) -> ByteArray, nonce: ByteArray, mtu: Int, frame: ByteArray): Result {
        val request = when (val r = UpdateDataFrame.verifyRequest(mac, nonce, frame)) {
            is UpdateDataFrame.RequestResult.Invalid -> return Result.Rejected(Status.AUTH_FAILED)
            is UpdateDataFrame.RequestResult.Valid -> r.request
        }
        val holder = active
        if (holder != null && holder != device && clock() - activeSince < ACTIVE_MAX_MS) return Result.Rejected(Status.UPDATE_BUSY)
        val src = source(request.token)
        if (src == null || !MessageDigest.isEqual(src.token, request.token)) return Result.Rejected(Status.UPDATE_GONE)
        val perChunk = UpdateDataFrame.chunkPayload(mtu)
        if (perChunk < MIN_CHUNK || request.offset > src.total) return Result.Rejected(Status.FAILED)
        val len = minOf(request.window, src.total - request.offset)
        val data = try {
            read(src, request.offset, len)
        } catch (e: IOException) {
            return Result.Rejected(Status.FAILED)
        }
        val frames = ArrayList<ByteArray>()
        var at = 0
        // An empty window (the watch already holds everything) still answers once, so it learns the APK size and completes.
        do {
            val n = minOf(perChunk, len - at)
            frames += UpdateDataFrame.encodeChunk(request.offset + at, src.total, data, at, n)
            at += n
        } while (at < len)
        active = device
        activeSince = clock()
        return Result.Window(frames, request.offset + len, src.total)
    }

    /** The window was sent, or the device is gone: the next request may come from anyone. */
    @Synchronized
    fun release(device: String) {
        if (active == device) active = null
    }

    private fun read(src: Source, offset: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        src.open().use { input ->
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) {
                    if (input.read() < 0) throw IOException("short asset")
                    skipped++
                } else skipped += n
            }
            var got = 0
            while (got < len) {
                val n = input.read(out, got, len - got)
                if (n < 0) throw IOException("short asset")
                got += n
            }
        }
        return out
    }

    companion object {
        /** A window that was never released (a stuck link) stops blocking others after this. */
        const val ACTIVE_MAX_MS = 30_000L
        private const val MIN_CHUNK = 16
    }
}
