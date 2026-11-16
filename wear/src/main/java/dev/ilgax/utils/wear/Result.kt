package dev.ilgax.utils.wear

import dev.ilgax.utils.protocol.Status

/** Outcome of a remote operation: local failures plus the tablet's protocol statuses. */
enum class Result {
    SUCCESS, NOT_FOUND, CONNECT_FAILED, TIMEOUT, BLUETOOTH_OFF, NO_PERMISSION, NOT_PAIRED, CANCELLED,
    AUTH_FAILED, UNKNOWN_COMMAND, FAILED, ACCESSIBILITY_DISABLED, PAIRING_REJECTED, RATE_LIMITED,
    NOTHING_TO_CLOSE, SLOT_EMPTY, APP_START_FAILED, MACRO_BUSY, MACRO_NOT_FOUND,
    /** Answers to SPEAKER: the kill switch's resulting state; the NO_SHIZUKU pair means the tablet could not act on a request. */
    SPEAKER_ON, SPEAKER_OFF, SPEAKER_ON_NO_SHIZUKU, SPEAKER_OFF_NO_SHIZUKU,
    UNKNOWN;

    /** Worked as intended: plain success, or a SPEAKER answer from a tablet whose Shizuku is ready. */
    val isOk: Boolean get() = this == SUCCESS || this == SPEAKER_ON || this == SPEAKER_OFF

    /** The kill switch state a SPEAKER answer carries; null for every other result. */
    val speakerOn: Boolean? get() = when (this) {
        SPEAKER_ON, SPEAKER_ON_NO_SHIZUKU -> true
        SPEAKER_OFF, SPEAKER_OFF_NO_SHIZUKU -> false
        else -> null
    }

    companion object {
        fun from(status: Status): Result = when (status) {
            Status.SUCCESS -> SUCCESS
            Status.AUTH_FAILED -> AUTH_FAILED
            Status.UNKNOWN_COMMAND -> UNKNOWN_COMMAND
            Status.FAILED -> FAILED
            Status.ACCESSIBILITY_DISABLED -> ACCESSIBILITY_DISABLED
            Status.PAIRING_REJECTED -> PAIRING_REJECTED
            Status.RATE_LIMITED -> RATE_LIMITED
            Status.NOTHING_TO_CLOSE -> NOTHING_TO_CLOSE
            Status.SLOT_EMPTY -> SLOT_EMPTY
            Status.APP_START_FAILED -> APP_START_FAILED
            Status.MACRO_BUSY -> MACRO_BUSY
            Status.MACRO_NOT_FOUND -> MACRO_NOT_FOUND
            Status.SPEAKER_ON -> SPEAKER_ON
            Status.SPEAKER_OFF -> SPEAKER_OFF
            Status.SPEAKER_ON_NO_SHIZUKU -> SPEAKER_ON_NO_SHIZUKU
            Status.SPEAKER_OFF_NO_SHIZUKU -> SPEAKER_OFF_NO_SHIZUKU
            Status.UNKNOWN -> UNKNOWN
        }
    }
}
