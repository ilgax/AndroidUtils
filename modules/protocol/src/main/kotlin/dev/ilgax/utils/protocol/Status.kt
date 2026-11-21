package dev.ilgax.utils.protocol

/**
 * Status returned as the GATT write response status for COMMAND / PAIR writes.
 * App codes 0x90..0x9E stay in the ATT application-error range but clear Android's local
 * stack statuses (0x80..0x8F, e.g. 133 = GATT_ERROR).
 */
enum class Status(val code: Int) {
    SUCCESS(0x00),
    AUTH_FAILED(0x05),
    UNKNOWN_COMMAND(0x06),
    FAILED(0x90),
    ACCESSIBILITY_DISABLED(0x91),
    PAIRING_REJECTED(0x92),
    RATE_LIMITED(0x93),
    NOTHING_TO_CLOSE(0x94),
    SLOT_EMPTY(0x95),
    APP_START_FAILED(0x96),
    /** RUN_MACRO: another macro is still running; nothing was started. */
    MACRO_BUSY(0x97),
    /** RUN_MACRO: unknown macro id, or the macro has no steps. */
    MACRO_NOT_FOUND(0x98),
    /** SPEAKER replies: the kill switch is off / on, Shizuku ready. See [SpeakerCodec]. Never sent for other commands. */
    SPEAKER_OFF(0x99),
    SPEAKER_ON(0x9A),
    /** SPEAKER replies when Shizuku is not ready on the tablet: the state is what the tablet has, a requested change was not made. */
    SPEAKER_OFF_NO_SHIZUKU(0x9B),
    SPEAKER_ON_NO_SHIZUKU(0x9C),
    /** UPDATE_DATA: the token is not the current offer's (stale, expired, cancelled, or Bluetooth transfer switched off). */
    UPDATE_GONE(0x9D),
    /** UPDATE_DATA: another watch is being served right now. */
    UPDATE_BUSY(0x9E),
    /** SEEK: nothing seekable is playing (no media session, or the session does not allow seeking). */
    NOT_AVAILABLE(0x9F),
    UNKNOWN(-1);

    companion object {
        fun fromStatus(code: Int): Status = entries.firstOrNull { it != UNKNOWN && it.code == code } ?: UNKNOWN
    }
}
