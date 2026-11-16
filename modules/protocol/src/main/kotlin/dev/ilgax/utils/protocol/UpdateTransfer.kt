package dev.ilgax.utils.protocol

import java.security.MessageDigest

/**
 * Bluetooth transfer of the update APK over the UPDATE_DATA characteristic ([Protocol.UPDATE_DATA_UUID]).
 *
 * Stop-and-wait windows: the watch reads a NONCE, writes a [Request] (authenticated, bound to that nonce) and the tablet answers
 * with up to [WINDOW_MAX] bytes as notifications, one [Chunk] each; the watch asks for the next window when the previous one is
 * complete. Request layout: `token(16) | offset(4) | window(2) | tag(16)`, tag = HMAC-SHA256(key, "upd" | nonce | body)
 * truncated to 16 bytes, like a command frame. The token is the current offer's, so a request is bound to that offer.
 * Chunk layout: `offset(4) | total(4) | data`; every chunk repeats the APK size so the watch needs no separate query.
 *
 * Why chunks carry no MAC: the APK is not trusted because of how it arrives but because the watch checks its SHA-256 against the
 * hash in the HMAC-authenticated offer and its signing certificate against the running app's before anything is installed. A
 * forged, reordered, truncated or corrupted stream can therefore only make the transfer fail (and fall back to Wi-Fi), never
 * install something else. The request is authenticated so that only the paired watch can make the tablet spend radio time and
 * read its APK. Pure JVM.
 */
object UpdateDataFrame {
    /** Largest window a request may ask for; the watch asks for this much. */
    const val WINDOW_MAX = 16 * 1024
    const val TOKEN_SIZE = 16
    const val REQUEST_SIZE = TOKEN_SIZE + 4 + 2 + Protocol.TAG_SIZE
    /** `offset(4) | total(4)`. */
    const val CHUNK_HEADER = 8
    /** ATT adds one opcode byte and a 2 byte handle to every write and notification. */
    const val ATT_OVERHEAD = 3
    /** Smallest MTU the request fits in one write; below it the watch does not try Bluetooth. */
    const val MIN_MTU = REQUEST_SIZE + ATT_OVERHEAD
    /** Largest APK either side accepts. */
    const val MAX_TOTAL = 64 * 1024 * 1024
    /** The MTU Utils asks for (the Android maximum); the link settles on the smaller of both sides. */
    const val WANTED_MTU = 517
    private val PREFIX = "upd".toByteArray(Charsets.US_ASCII)

    enum class Failure { MALFORMED, AUTH_FAILED }

    class Request(val token: ByteArray, val offset: Int, val window: Int)

    sealed interface RequestResult {
        class Valid(val request: Request) : RequestResult
        class Invalid(val failure: Failure) : RequestResult
    }

    fun encodeRequest(mac: (ByteArray) -> ByteArray, nonce: ByteArray, token: ByteArray, offset: Int, window: Int): ByteArray {
        require(nonce.size == Protocol.NONCE_SIZE && token.size == TOKEN_SIZE) { "bad nonce or token" }
        require(offset >= 0 && window in 1..WINDOW_MAX) { "bad range" }
        val body = token + u32(offset) + byteArrayOf((window ushr 8).toByte(), window.toByte())
        return body + tag(mac, nonce, body)
    }

    /** Authenticates before parsing anything. */
    fun verifyRequest(mac: (ByteArray) -> ByteArray, nonce: ByteArray, frame: ByteArray): RequestResult {
        if (nonce.size != Protocol.NONCE_SIZE || frame.size != REQUEST_SIZE) return RequestResult.Invalid(Failure.MALFORMED)
        val body = frame.copyOfRange(0, REQUEST_SIZE - Protocol.TAG_SIZE)
        val given = frame.copyOfRange(REQUEST_SIZE - Protocol.TAG_SIZE, REQUEST_SIZE)
        if (!MessageDigest.isEqual(tag(mac, nonce, body), given)) return RequestResult.Invalid(Failure.AUTH_FAILED)
        val offset = readU32(body, TOKEN_SIZE)
        val window = ((body[TOKEN_SIZE + 4].toInt() and 0xFF) shl 8) or (body[TOKEN_SIZE + 5].toInt() and 0xFF)
        if (offset < 0 || window !in 1..WINDOW_MAX) return RequestResult.Invalid(Failure.MALFORMED)
        return RequestResult.Valid(Request(body.copyOfRange(0, TOKEN_SIZE), offset, window))
    }

    /** Data bytes per notification at [mtu]. */
    fun chunkPayload(mtu: Int): Int = (mtu - ATT_OVERHEAD - CHUNK_HEADER).coerceAtLeast(0)

    fun encodeChunk(offset: Int, total: Int, data: ByteArray, from: Int = 0, len: Int = data.size - from): ByteArray =
        u32(offset) + u32(total) + data.copyOfRange(from, from + len)

    class Chunk(val offset: Int, val total: Int, val data: ByteArray)

    /** Null unless the frame is well formed: header present, and `offset + data` inside `0..total..MAX_TOTAL`. */
    fun decodeChunk(frame: ByteArray): Chunk? {
        if (frame.size < CHUNK_HEADER) return null
        val offset = readU32(frame, 0)
        val total = readU32(frame, 4)
        val n = frame.size - CHUNK_HEADER
        if (offset < 0 || total < 0 || total > MAX_TOTAL || offset > total || n > total - offset) return null
        return Chunk(offset, total, frame.copyOfRange(CHUNK_HEADER, frame.size))
    }

    private fun tag(mac: (ByteArray) -> ByteArray, nonce: ByteArray, body: ByteArray): ByteArray =
        mac(PREFIX + nonce + body).copyOf(Protocol.TAG_SIZE)

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun readU32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}

/**
 * Reassembles one window on the watch. Chunks must arrive in order; [next] is always the end of the contiguous good bytes, so it is
 * also the offset to resume from. Accepted bytes go to [accept]'s sink immediately.
 */
class WindowAssembler(val start: Int, val window: Int, knownTotal: Int = -1) {
    enum class Verdict {
        /** New bytes, written to the sink. */
        ACCEPTED,
        /** Nothing new (a repeat of bytes already held). */
        DUPLICATE,
        /** A chunk starts after [next]: bytes were lost, ask again from [next]. */
        GAP,
        /** Malformed, outside the window, or the total changed: the stream cannot be trusted. */
        INVALID,
    }

    /** APK size as the tablet reports it; -1 until the first chunk. */
    var total: Int = knownTotal
        private set
    /** End of the contiguous good bytes. */
    var next: Int = start
        private set
    /** The last chunk that was not accepted, for the caller's failure classification. */
    var failure: Verdict? = null
        private set

    private fun end(): Int = if (total < 0) start + window else minOf(start + window, total)

    /** True when every byte of the window (clipped to the APK's end) is in. */
    val complete: Boolean get() = total >= 0 && next >= end()

    fun accept(frame: ByteArray, sink: (ByteArray, Int, Int) -> Unit): Verdict {
        val v = classify(frame, sink)
        if (v == Verdict.GAP || v == Verdict.INVALID) failure = v
        return v
    }

    private fun classify(frame: ByteArray, sink: (ByteArray, Int, Int) -> Unit): Verdict {
        val c = UpdateDataFrame.decodeChunk(frame) ?: return Verdict.INVALID
        if (total < 0) {
            if (c.total < start) return Verdict.INVALID
            total = c.total
        } else if (c.total != total) return Verdict.INVALID
        if (c.offset + c.data.size > end()) return Verdict.INVALID
        if (c.offset > next) return Verdict.GAP
        val skip = next - c.offset
        if (skip >= c.data.size) return Verdict.DUPLICATE
        sink(c.data, skip, c.data.size - skip)
        next += c.data.size - skip
        return Verdict.ACCEPTED
    }
}

/** Where to continue a partly downloaded APK. Pure. */
object ResumePlan {
    /**
     * The offset to request next: the length of the partial file, or 0 when it cannot be trusted (longer than the APK is,
     * or it belongs to another offer's APK). [total] is -1 while unknown.
     */
    fun resumeFrom(fileLength: Long, total: Int, sameBuild: Boolean = true): Int = when {
        !sameBuild || fileLength < 0 || fileLength > UpdateDataFrame.MAX_TOTAL -> 0
        total >= 0 && fileLength > total -> 0
        else -> fileLength.toInt()
    }
}

/** Bluetooth first, Wi-Fi as the fallback: when to give up on Bluetooth. Pure. */
object TransferPolicy {
    /** No byte of progress for this long and the watch switches to Wi-Fi. */
    const val STALL_MS = 20_000L
    /** Failed window requests in a row, without a byte of progress between them, before switching. */
    const val MAX_CONSECUTIVE_FAILURES = 3

    enum class FallbackReason {
        /** The offer does not carry the Bluetooth flag: an older Utils, or "Wi-Fi only" on the tablet. */
        NOT_OFFERED,
        /** The tablet has no UPDATE_DATA characteristic. */
        NO_CHANNEL,
        /** The link negotiated an MTU too small for the request. */
        MTU_TOO_SMALL,
        REPEATED_FAILURES,
        STALLED,
        /** The tablet refused the request (stale token, unauthenticated, switched off). */
        REJECTED,
        /** The whole APK arrived but did not hash to the offered SHA-256. */
        CORRUPT,
    }

    sealed interface Start {
        data object Bluetooth : Start
        class Wifi(val reason: FallbackReason) : Start
    }

    fun initial(offer: UpdateOffer): Start = if (offer.bluetooth) Start.Bluetooth else Start.Wifi(FallbackReason.NOT_OFFERED)

    /** Why one window request did not complete. */
    enum class WindowFailure {
        TIMEOUT, LINK_LOST, GAP, BAD_FRAME,
        /** The tablet is serving something else right now. Transient. */
        BUSY,
        NO_CHANNEL, MTU_TOO_SMALL, REJECTED,
    }

    sealed interface Decision {
        /** Ask again from the last good offset after [delayMs]. */
        class Retry(val delayMs: Long) : Decision
        class Fallback(val reason: FallbackReason) : Decision
    }

    class Tracker(startMs: Long) {
        private var lastProgress = startMs
        private var failures = 0

        /** Bytes arrived at [nowMs]. */
        fun onProgress(nowMs: Long) {
            lastProgress = nowMs
            failures = 0
        }

        fun stalled(nowMs: Long): Boolean = nowMs - lastProgress >= STALL_MS

        fun onWindowFailed(failure: WindowFailure, nowMs: Long): Decision {
            when (failure) {
                WindowFailure.NO_CHANNEL -> return Decision.Fallback(FallbackReason.NO_CHANNEL)
                WindowFailure.MTU_TOO_SMALL -> return Decision.Fallback(FallbackReason.MTU_TOO_SMALL)
                WindowFailure.REJECTED -> return Decision.Fallback(FallbackReason.REJECTED)
                else -> Unit
            }
            failures++
            if (stalled(nowMs)) return Decision.Fallback(FallbackReason.STALLED)
            if (failures >= MAX_CONSECUTIVE_FAILURES) return Decision.Fallback(FallbackReason.REPEATED_FAILURES)
            return Decision.Retry(backoffMs(failures))
        }

        /** 0.5 s, 1 s, capped at 2 s. */
        private fun backoffMs(n: Int): Long = minOf(500L shl (n - 1).coerceIn(0, 2), 2_000L)
    }
}
