package dev.ilgax.utils.protocol

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

enum class PlayState(val code: Int) {
    /** No media session. */
    NONE(0),
    PAUSED(1),
    PLAYING(2);

    companion object {
        fun fromCode(code: Int): PlayState? = entries.firstOrNull { it.code == code }
    }
}

/**
 * What the tablet reports about the media that is playing (or was playing last).
 *
 * [positionMs] is the position at the moment the tablet built the state (already extrapolated from the session's own last update);
 * the watch extrapolates onward from its receive time with [speedPercent] ([Transport.positionAt]). [durationMs] is -1 when unknown.
 * [volume] / [volumeMax] are the tablet's STREAM_MUSIC values; [volumeMax] = 0 means "not reported". [artHash] = 0 means no art.
 * [access] = false: the tablet's Utils has no notification access, so it cannot see media sessions at all.
 */
data class NowPlaying(
    val access: Boolean = true,
    val state: PlayState = PlayState.NONE,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val canSeek: Boolean = false,
    val speedPercent: Int = 0,
    val positionMs: Int = 0,
    val durationMs: Int = -1,
    val volume: Int = 0,
    val volumeMax: Int = 0,
    val artHash: Int = 0,
    val artSize: Int = 0,
    val app: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
)

/**
 * NOW_PLAYING payload, one authenticated long read after GET_NOW_PLAYING:
 * `format(1) | flags(1) | state(1) | speed(2, signed percent) | position(4, ms) | duration(4, ms, -1 unknown) | volume(1) | volumeMax(1)
 * | artHash(4) | artSize(2) | app | title | artist | album | tag(16)` where each text is `len(1) | UTF-8`.
 * flags: bit0 notification access, bit1 can skip next, bit2 can skip previous, bit3 can seek.
 * tag = HMAC-SHA256(key, "now" | nonce | everything before the tag), 16 bytes, nonce = the one of the GET_NOW_PLAYING frame.
 *
 * How changes reach the watch: the tablet sends a content-free ping ([Protocol.PING_NOW_PLAYING]) as a NOW_PLAYING notification;
 * the watch answers with GET_NOW_PLAYING and reads the state. A notification cannot carry a per-read MAC (no nonce), and a tag
 * over a counter would need counter state on both sides to resist replay, so nothing but the authenticated read is ever trusted
 * and a forged ping can at most make the watch ask once more.
 */
object NowPlayingFrame {
    const val FORMAT = 1
    const val MAX_APP = 32
    const val MAX_TITLE = 120
    const val MAX_ARTIST = 80
    const val MAX_ALBUM = 80
    private const val FIXED = 1 + 1 + 1 + 2 + 4 + 4 + 1 + 1 + 4 + 2
    const val MAX_SIZE = FIXED + 4 + MAX_APP + MAX_TITLE + MAX_ARTIST + MAX_ALBUM + Protocol.TAG_SIZE
    private val PREFIX = "now".toByteArray(Charsets.US_ASCII)

    /** Control characters become spaces and the text is trimmed; cut to at most [maxBytes] of UTF-8 on a code point boundary, ending in "…" when cut. */
    fun clip(text: String, maxBytes: Int): String {
        val clean = buildString { for (ch in text) append(if (ch.isISOControl()) ' ' else ch) }.trim()
        if (utf8Size(clean) <= maxBytes) return clean
        val budget = maxBytes - ELLIPSIS_BYTES
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < clean.length) {
            val cp = clean.codePointAt(i)
            val n = utf8Size(cp)
            if (used + n > budget) break
            sb.appendCodePoint(cp)
            used += n
            i += Character.charCount(cp)
        }
        return sb.toString().trimEnd() + "…"
    }

    private const val ELLIPSIS_BYTES = 3

    private fun utf8Size(s: String) = s.toByteArray(Charsets.UTF_8).size
    private fun utf8Size(cp: Int) = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    fun encode(mac: (ByteArray) -> ByteArray, nonce: ByteArray, np: NowPlaying): ByteArray {
        require(nonce.size == Protocol.NONCE_SIZE) { "bad nonce" }
        val out = java.io.ByteArrayOutputStream(160)
        out.write(FORMAT)
        out.write(
            (if (np.access) 1 else 0) or (if (np.canNext) 2 else 0) or (if (np.canPrevious) 4 else 0) or (if (np.canSeek) 8 else 0),
        )
        out.write(np.state.code)
        val speed = np.speedPercent.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        out.write(speed shr 8); out.write(speed)
        out.write(u32(np.positionMs.coerceAtLeast(0)))
        out.write(u32(if (np.durationMs > 0) np.durationMs else -1))
        out.write(np.volume.coerceIn(0, 255))
        out.write(np.volumeMax.coerceIn(0, 255))
        out.write(u32(np.artHash))
        val artSize = np.artSize.coerceIn(0, 0xFFFF)
        out.write(artSize shr 8); out.write(artSize)
        for ((text, max) in listOf(np.app to MAX_APP, np.title to MAX_TITLE, np.artist to MAX_ARTIST, np.album to MAX_ALBUM)) {
            val b = clip(text, max).toByteArray(Charsets.UTF_8)
            out.write(b.size)
            out.write(b)
        }
        val body = out.toByteArray()
        return body + tag(mac, nonce, body)
    }

    sealed interface Result {
        class Valid(val state: NowPlaying) : Result
        data object Malformed : Result
        data object AuthFailed : Result
    }

    /** Authenticates before parsing anything. */
    fun verify(mac: (ByteArray) -> ByteArray, nonce: ByteArray, payload: ByteArray): Result {
        if (nonce.size != Protocol.NONCE_SIZE || payload.size < FIXED + 4 + Protocol.TAG_SIZE || payload.size > MAX_SIZE) return Result.Malformed
        val body = payload.copyOfRange(0, payload.size - Protocol.TAG_SIZE)
        val given = payload.copyOfRange(payload.size - Protocol.TAG_SIZE, payload.size)
        if (!MessageDigest.isEqual(tag(mac, nonce, body), given)) return Result.AuthFailed
        if ((body[0].toInt() and 0xFF) != FORMAT) return Result.Malformed
        val flags = body[1].toInt() and 0xFF
        val state = PlayState.fromCode(body[2].toInt() and 0xFF) ?: return Result.Malformed
        val speed = ((body[3].toInt() shl 8) or (body[4].toInt() and 0xFF)).toShort().toInt()
        val position = readU32(body, 5)
        val duration = readU32(body, 9)
        val volume = body[13].toInt() and 0xFF
        val volumeMax = body[14].toInt() and 0xFF
        val artHash = readU32(body, 15)
        val artSize = ((body[19].toInt() and 0xFF) shl 8) or (body[20].toInt() and 0xFF)
        if (position < 0 || duration < -1 || (volumeMax != 0 && volume > volumeMax)) return Result.Malformed
        var o = FIXED
        val texts = ArrayList<String>(4)
        val limits = intArrayOf(MAX_APP, MAX_TITLE, MAX_ARTIST, MAX_ALBUM)
        for (max in limits) {
            if (o >= body.size) return Result.Malformed
            val len = body[o++].toInt() and 0xFF
            if (len > max || o + len > body.size) return Result.Malformed
            texts += strictUtf8(body, o, len) ?: return Result.Malformed
            o += len
        }
        if (o != body.size) return Result.Malformed
        return Result.Valid(
            NowPlaying(
                access = flags and 1 != 0, state = state, canNext = flags and 2 != 0, canPrevious = flags and 4 != 0,
                canSeek = flags and 8 != 0, speedPercent = speed, positionMs = position, durationMs = duration,
                volume = volume, volumeMax = volumeMax, artHash = artHash, artSize = artSize,
                app = texts[0], title = texts[1], artist = texts[2], album = texts[3],
            ),
        )
    }

    private fun strictUtf8(b: ByteArray, off: Int, len: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b, off, len)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private fun tag(mac: (ByteArray) -> ByteArray, nonce: ByteArray, body: ByteArray): ByteArray =
        mac(PREFIX + nonce + body).copyOf(Protocol.TAG_SIZE)

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun readU32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}

/** When a change on the tablet is worth a ping to the watch. Pure. */
object NowPlayingPush {
    /** The position may drift this far from the watch's extrapolation before a position-only change is pushed (seeks, stalls). */
    const val DRIFT_MS = 1_500L

    /**
     * [old] was built [ageMs] ago. Everything but the position is compared exactly; the position only when it differs from where
     * the watch would be by now. Volume counts (the watch shows an arc).
     */
    fun needed(old: NowPlaying?, ageMs: Long, new: NowPlaying): Boolean {
        if (old == null) return true
        if (old.copy(positionMs = 0) != new.copy(positionMs = 0)) return true
        val expected = Transport.positionAt(old.positionMs.toLong(), old.speedPercent, ageMs, old.durationMs.toLong())
        return kotlin.math.abs(expected - new.positionMs) > DRIFT_MS
    }
}

/**
 * Cover art over NOW_ART: a small JPEG (about 96x96, under 4 KB) read in pages, only when the hash in the now-playing state changed.
 * Page payload (one authenticated long read after GET_ART): `format(1) | hash(4) | total(4) | page(2) | data | tag(16)`,
 * tag = HMAC-SHA256(key, "art" | nonce | everything before the tag), 16 bytes. [PAGE_BYTES] of data per page; a page past the end
 * is valid and empty. hash = first 4 bytes of the JPEG's SHA-256 (never 0); no art is hash 0, total 0.
 * The watch checks that every page repeats the same hash and total and that the reassembled JPEG hashes to it.
 */
object NowArt {
    const val FORMAT = 1
    const val PAGE_BYTES = 400
    /** What the watch accepts as a whole image. */
    const val MAX_TOTAL = 16 * 1024
    const val MAX_PAGES = (MAX_TOTAL + PAGE_BYTES - 1) / PAGE_BYTES
    private const val HEADER = 1 + 4 + 4 + 2
    const val MAX_SIZE = HEADER + PAGE_BYTES + Protocol.TAG_SIZE
    private val PREFIX = "art".toByteArray(Charsets.US_ASCII)

    fun encodePageArgs(page: Int): ByteArray {
        require(page in 0..0xFFFF) { "page out of range" }
        return byteArrayOf((page ushr 8).toByte(), page.toByte())
    }

    /** Null unless exactly 2 bytes. */
    fun decodePageArgs(args: ByteArray): Int? =
        if (args.size != 2) null else ((args[0].toInt() and 0xFF) shl 8) or (args[1].toInt() and 0xFF)

    fun hashOf(jpeg: ByteArray): Int {
        if (jpeg.isEmpty()) return 0
        val d = MessageDigest.getInstance("SHA-256").digest(jpeg)
        val h = ((d[0].toInt() and 0xFF) shl 24) or ((d[1].toInt() and 0xFF) shl 16) or ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
        return if (h == 0) 1 else h
    }

    fun pageCount(total: Int): Int = (total + PAGE_BYTES - 1) / PAGE_BYTES

    fun encodePage(mac: (ByteArray) -> ByteArray, nonce: ByteArray, jpeg: ByteArray, page: Int): ByteArray {
        require(nonce.size == Protocol.NONCE_SIZE && page in 0..0xFFFF) { "bad nonce or page" }
        val from = (page * PAGE_BYTES).coerceAtMost(jpeg.size)
        val to = (from + PAGE_BYTES).coerceAtMost(jpeg.size)
        val body = byteArrayOf(FORMAT.toByte()) + u32(hashOf(jpeg)) + u32(jpeg.size) +
            byteArrayOf((page ushr 8).toByte(), page.toByte()) + jpeg.copyOfRange(from, to)
        return body + tag(mac, nonce, body)
    }

    class Page(val hash: Int, val total: Int, val page: Int, val data: ByteArray)

    /** Null for anything malformed or not authentic; authenticates before parsing. */
    fun verifyPage(mac: (ByteArray) -> ByteArray, nonce: ByteArray, payload: ByteArray): Page? {
        if (nonce.size != Protocol.NONCE_SIZE || payload.size < HEADER + Protocol.TAG_SIZE || payload.size > MAX_SIZE) return null
        val body = payload.copyOfRange(0, payload.size - Protocol.TAG_SIZE)
        val given = payload.copyOfRange(payload.size - Protocol.TAG_SIZE, payload.size)
        if (!MessageDigest.isEqual(tag(mac, nonce, body), given)) return null
        if ((body[0].toInt() and 0xFF) != FORMAT) return null
        val hash = readU32(body, 1)
        val total = readU32(body, 5)
        val page = ((body[9].toInt() and 0xFF) shl 8) or (body[10].toInt() and 0xFF)
        if (total < 0 || total > MAX_TOTAL || (total == 0) != (hash == 0)) return null
        val data = body.copyOfRange(HEADER, body.size)
        // A page holds exactly its slice of the image: full pages before the last, the remainder on the last, nothing past it.
        val expected = (total - page * PAGE_BYTES).coerceIn(0, PAGE_BYTES)
        if (data.size != expected) return null
        return Page(hash, total, page, data)
    }

    private fun tag(mac: (ByteArray) -> ByteArray, nonce: ByteArray, body: ByteArray): ByteArray =
        mac(PREFIX + nonce + body).copyOf(Protocol.TAG_SIZE)

    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun readU32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}

/** Collects the pages of one image on the watch, in order. */
class ArtAssembler(private val wantedHash: Int) {
    enum class Verdict { MORE, DONE, REJECTED }

    private val buf = java.io.ByteArrayOutputStream()
    private var total = -1
    private var nextPage = 0

    val pageIndex: Int get() = nextPage

    /** The finished JPEG, only after [DONE]. */
    var result: ByteArray? = null
        private set

    fun accept(page: NowArt.Page): Verdict {
        if (page.hash != wantedHash || page.page != nextPage || wantedHash == 0) return Verdict.REJECTED
        if (nextPage == 0) total = page.total else if (page.total != total) return Verdict.REJECTED
        if (nextPage >= NowArt.MAX_PAGES) return Verdict.REJECTED
        buf.write(page.data)
        nextPage++
        if (buf.size() < total) return Verdict.MORE
        val jpeg = buf.toByteArray()
        if (jpeg.size != total || NowArt.hashOf(jpeg) != wantedHash) return Verdict.REJECTED
        result = jpeg
        return Verdict.DONE
    }
}
