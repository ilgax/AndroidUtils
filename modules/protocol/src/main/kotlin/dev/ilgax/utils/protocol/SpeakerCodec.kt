package dev.ilgax.utils.protocol

/** What a [Command.SPEAKER] frame asks for; [arg] is its single argument byte. */
enum class SpeakerOp(val arg: Int) {
    QUERY(0), ON(1), OFF(2), TOGGLE(3);

    companion object {
        fun fromArg(arg: Int): SpeakerOp? = entries.firstOrNull { it.arg == arg }
    }
}

/** The tablet's answer to [Command.SPEAKER]: the resulting kill-switch state, and whether Shizuku was ready to act on it. */
data class SpeakerReply(val on: Boolean, val shizukuReady: Boolean)

/** Wire format of [Command.SPEAKER]: one arg byte out, the state packed into the response status back. */
object SpeakerCodec {
    fun encodeArgs(op: SpeakerOp): ByteArray = byteArrayOf(op.arg.toByte())

    /** Exactly one byte holding a known op; anything else is malformed (the tablet answers [Status.FAILED]). */
    fun decodeArgs(args: ByteArray): SpeakerOp? =
        if (args.size == 1) SpeakerOp.fromArg(args[0].toInt() and 0xFF) else null

    fun reply(on: Boolean, shizukuReady: Boolean): Status = when {
        on && shizukuReady -> Status.SPEAKER_ON
        on -> Status.SPEAKER_ON_NO_SHIZUKU
        shizukuReady -> Status.SPEAKER_OFF
        else -> Status.SPEAKER_OFF_NO_SHIZUKU
    }

    /** Null for any status that is not a SPEAKER reply (errors such as [Status.UNKNOWN_COMMAND] or [Status.FAILED]). */
    fun decodeReply(status: Status): SpeakerReply? = when (status) {
        Status.SPEAKER_ON -> SpeakerReply(on = true, shizukuReady = true)
        Status.SPEAKER_ON_NO_SHIZUKU -> SpeakerReply(on = true, shizukuReady = false)
        Status.SPEAKER_OFF -> SpeakerReply(on = false, shizukuReady = true)
        Status.SPEAKER_OFF_NO_SHIZUKU -> SpeakerReply(on = false, shizukuReady = false)
        else -> null
    }
}
