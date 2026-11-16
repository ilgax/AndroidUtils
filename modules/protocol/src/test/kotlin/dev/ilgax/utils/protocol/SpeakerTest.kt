package dev.ilgax.utils.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTest {
    private val key = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(16) { (it * 3).toByte() }
    private val mac: (ByteArray) -> ByteArray = { Hmac.sha256(key, it) }

    @Test fun commandIdIsNextFree() {
        assertEquals(15, Command.SPEAKER.id)
        assertEquals(Command.SPEAKER, Command.fromId(15))
        val older = listOf(Command.HELLO to 10, Command.GET_OFFER to 11, Command.UPDATE_STATE to 12, Command.RUN_MACRO to 13, Command.SYNC_MACROS to 14)
        older.forEach { (c, id) -> assertEquals(id, c.id) }
        assertEquals(Command.entries.size, Command.entries.map { it.id }.toSet().size)
    }

    @Test fun everyOpRoundTripsThroughASignedFrame() {
        for (op in SpeakerOp.entries) {
            val frame = CommandFrame.encode(mac, nonce, Command.SPEAKER, SpeakerCodec.encodeArgs(op))
            val v = CommandFrame.verify(mac, nonce, frame) as CommandFrame.Result.Valid
            assertEquals(Command.SPEAKER, v.command)
            assertEquals(op, SpeakerCodec.decodeArgs(v.args))
        }
        assertArrayEquals(byteArrayOf(0), SpeakerCodec.encodeArgs(SpeakerOp.QUERY))
        assertArrayEquals(byteArrayOf(1), SpeakerCodec.encodeArgs(SpeakerOp.ON))
        assertArrayEquals(byteArrayOf(2), SpeakerCodec.encodeArgs(SpeakerOp.OFF))
        assertArrayEquals(byteArrayOf(3), SpeakerCodec.encodeArgs(SpeakerOp.TOGGLE))
    }

    @Test fun tamperedSpeakerFrameIsAuthFailed() {
        val frame = CommandFrame.encode(mac, nonce, Command.SPEAKER, SpeakerCodec.encodeArgs(SpeakerOp.OFF))
        frame[1] = SpeakerOp.ON.arg.toByte() // flip OFF into ON without re-signing
        val r = CommandFrame.verify(mac, nonce, frame) as CommandFrame.Result.Invalid
        assertEquals(Status.AUTH_FAILED, r.status)
    }

    @Test fun malformedArgsAreRejected() {
        assertNull(SpeakerCodec.decodeArgs(ByteArray(0)))
        assertNull(SpeakerCodec.decodeArgs(byteArrayOf(4)))
        assertNull(SpeakerCodec.decodeArgs(byteArrayOf(-1)))
        assertNull(SpeakerCodec.decodeArgs(byteArrayOf(1, 1)))
    }

    @Test fun replyCarriesStateAndShizukuReadiness() {
        for (on in listOf(true, false)) for (ready in listOf(true, false)) {
            val status = SpeakerCodec.reply(on, ready)
            assertEquals(SpeakerReply(on, ready), SpeakerCodec.decodeReply(status))
            // Round-trips through the integer a GATT write response carries.
            assertEquals(status, Status.fromStatus(status.code))
        }
        assertEquals(0x99, Status.SPEAKER_OFF.code)
        assertEquals(0x9A, Status.SPEAKER_ON.code)
        assertEquals(setOf(Status.SPEAKER_ON, Status.SPEAKER_OFF, Status.SPEAKER_ON_NO_SHIZUKU, Status.SPEAKER_OFF_NO_SHIZUKU).size, 4)
    }

    @Test fun errorStatusesAreNotReplies() {
        for (s in listOf(Status.SUCCESS, Status.FAILED, Status.UNKNOWN_COMMAND, Status.AUTH_FAILED, Status.UNKNOWN)) {
            assertNull(s.name, SpeakerCodec.decodeReply(s))
        }
    }

    /** An old tablet does not know id 15, and a new one gets an id from the future: both end in a clean, specific error. */
    @Test fun unknownCommandsGetACleanError() {
        val body = byteArrayOf(16, 0)
        val tag = mac("cmd".toByteArray() + nonce + body).copyOf(Protocol.TAG_SIZE)
        val r = CommandFrame.verify(mac, nonce, body + tag) as CommandFrame.Result.Invalid
        assertEquals(Status.UNKNOWN_COMMAND, r.status)
        assertFalse(r.status == Status.AUTH_FAILED)
        // The watch side maps the status integer, including one it has never heard of, without throwing.
        assertEquals(Status.UNKNOWN_COMMAND, Status.fromStatus(0x06))
        assertEquals(Status.UNKNOWN, Status.fromStatus(0x9F))
        assertTrue(SpeakerCodec.decodeReply(Status.fromStatus(0x9D)) == null)
    }
}
