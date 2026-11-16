package dev.ilgax.utils.protocol

import java.security.MessageDigest

/** What the watch reports about the app binary it is running: its versionCode, APK hash and signing certificate hash. */
class IntegrityReport(val versionCode: Int, val apkSha256: ByteArray, val certSha256: ByteArray) {
    override fun equals(other: Any?): Boolean = other is IntegrityReport && versionCode == other.versionCode &&
        apkSha256.contentEquals(other.apkSha256) && certSha256.contentEquals(other.certSha256)

    override fun hashCode(): Int = versionCode * 31 + apkSha256.contentHashCode()
}

/**
 * VERIFY characteristic payload: `format(1) | versionCode(4) | apkSha256(32) | certSha256(32) | tag(16)`.
 * tag = HMAC-SHA256(key, "vfy" | nonce | everything before the tag), truncated to 16 bytes, where nonce is the one the watch
 * read from the NONCE characteristic just before writing (same one-nonce-per-attempt scheme as [CommandFrame]).
 * It does not fit the 3-byte argument limit of a command frame, hence its own characteristic; it needs the negotiated MTU.
 */
object IntegrityFrame {
    const val FORMAT = 1
    const val DIGEST = 32
    private const val BODY = 1 + 4 + DIGEST + DIGEST
    const val SIZE = BODY + Protocol.TAG_SIZE
    private val PREFIX = "vfy".toByteArray(Charsets.US_ASCII)

    fun encode(mac: (ByteArray) -> ByteArray, nonce: ByteArray, report: IntegrityReport): ByteArray {
        require(nonce.size == Protocol.NONCE_SIZE) { "bad nonce" }
        require(report.apkSha256.size == DIGEST && report.certSha256.size == DIGEST && report.versionCode >= 0) { "bad report" }
        val v = report.versionCode
        val body = byteArrayOf(FORMAT.toByte(), (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()) +
            report.apkSha256 + report.certSha256
        return body + tag(mac, nonce, body)
    }

    /** Authenticates before parsing; null for anything malformed or not authentic. */
    fun verify(mac: (ByteArray) -> ByteArray, nonce: ByteArray, frame: ByteArray): IntegrityReport? {
        if (nonce.size != Protocol.NONCE_SIZE || frame.size != SIZE) return null
        val body = frame.copyOfRange(0, BODY)
        val given = frame.copyOfRange(BODY, SIZE)
        if (!MessageDigest.isEqual(tag(mac, nonce, body), given)) return null
        if (body[0].toInt() != FORMAT) return null
        val v = ((body[1].toInt() and 0xFF) shl 24) or ((body[2].toInt() and 0xFF) shl 16) or
            ((body[3].toInt() and 0xFF) shl 8) or (body[4].toInt() and 0xFF)
        if (v < 0) return null
        return IntegrityReport(v, body.copyOfRange(5, 5 + DIGEST), body.copyOfRange(5 + DIGEST, BODY))
    }

    private fun tag(mac: (ByteArray) -> ByteArray, nonce: ByteArray, body: ByteArray): ByteArray =
        mac(PREFIX + nonce + body).copyOf(Protocol.TAG_SIZE)
}

/** The build the tablet embeds; [certSha256] is null when the bundle carries no certificate digest (then the key is not checked). */
class ExpectedBuild(val versionCode: Int, val apkSha256: ByteArray, val certSha256: ByteArray?)

/** Outcome of comparing a watch's [IntegrityReport] with the embedded [ExpectedBuild]. */
enum class IntegrityVerdict {
    VERIFIED,
    /** Same version and key, but the APK bytes differ: damaged on disk, or a different build of the same version. */
    CORRUPTED,
    /** The watch runs another version than the embedded one. */
    DIFFERENT_BUILD,
    /** The watch app is signed with another key than the embedded build. */
    WRONG_KEY,
}

object WatchIntegrity {
    /** Key first (a foreign key says nothing about the rest), then version, then the bytes. */
    fun assess(expected: ExpectedBuild, reported: IntegrityReport): IntegrityVerdict = when {
        expected.certSha256 != null && !MessageDigest.isEqual(expected.certSha256, reported.certSha256) -> IntegrityVerdict.WRONG_KEY
        expected.versionCode != reported.versionCode -> IntegrityVerdict.DIFFERENT_BUILD
        !MessageDigest.isEqual(expected.apkSha256, reported.apkSha256) -> IntegrityVerdict.CORRUPTED
        else -> IntegrityVerdict.VERIFIED
    }

    /** One digest for a signer list: the hash of the lone certificate, or of the sorted per-certificate hashes. */
    fun certDigest(certs: List<ByteArray>): ByteArray? {
        if (certs.isEmpty()) return null
        val sha = { b: ByteArray -> MessageDigest.getInstance("SHA-256").digest(b) }
        if (certs.size == 1) return sha(certs[0])
        val digests = certs.map(sha).sortedBy { UpdateOffer.hex(it) }
        return sha(digests.fold(ByteArray(0)) { a, b -> a + b })
    }

    /** True only when both are known and equal: an unknown key never counts as a match. */
    fun sameKey(a: ByteArray?, b: ByteArray?): Boolean = a != null && b != null && MessageDigest.isEqual(a, b)

    fun fromHex(s: String?): ByteArray? {
        if (s == null || s.length % 2 != 0) return null
        return try {
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
