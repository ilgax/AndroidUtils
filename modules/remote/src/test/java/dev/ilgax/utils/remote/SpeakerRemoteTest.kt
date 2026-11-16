package dev.ilgax.utils.remote

import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.CommandFrame
import dev.ilgax.tabremote.protocol.Hmac
import dev.ilgax.tabremote.protocol.SpeakerCodec
import dev.ilgax.tabremote.protocol.SpeakerOp
import dev.ilgax.tabremote.protocol.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRemoteTest {
    private class Fake(var on: Boolean, var ready: Boolean = true, var fail: Boolean = false) {
        val ran = ArrayList<String>()
        fun handle(op: SpeakerOp) = SpeakerRemote.handle(op, { on }, { ready }) { id ->
            ran += id
            if (fail) return@handle false
            on = when (id) { "speaker.on" -> true; "speaker.off" -> false; else -> !on }
            true
        }
    }

    @Test fun queryReportsStateWithoutRunningAnything() {
        val f = Fake(on = true)
        assertEquals(Status.SPEAKER_ON, f.handle(SpeakerOp.QUERY))
        f.on = false
        assertEquals(Status.SPEAKER_OFF, f.handle(SpeakerOp.QUERY))
        assertTrue(f.ran.isEmpty())
    }

    @Test fun onOffToggleReplyWithTheResultingState() {
        val f = Fake(on = false)
        assertEquals(Status.SPEAKER_ON, f.handle(SpeakerOp.ON))
        assertEquals(Status.SPEAKER_OFF, f.handle(SpeakerOp.OFF))
        assertEquals(Status.SPEAKER_ON, f.handle(SpeakerOp.TOGGLE))
        assertEquals(Status.SPEAKER_OFF, f.handle(SpeakerOp.TOGGLE))
        assertEquals(listOf("speaker.on", "speaker.off", "speaker.toggle", "speaker.toggle"), f.ran)
    }

    @Test fun alreadyInTheRequestedStateIsANoOp() {
        val f = Fake(on = true)
        assertEquals(Status.SPEAKER_ON, f.handle(SpeakerOp.ON))
        assertTrue(f.ran.isEmpty())
    }

    @Test fun withoutShizukuNothingChangesAndTheReplySaysSo() {
        val f = Fake(on = false, ready = false)
        assertEquals(Status.SPEAKER_OFF_NO_SHIZUKU, f.handle(SpeakerOp.ON))
        assertEquals(Status.SPEAKER_OFF_NO_SHIZUKU, f.handle(SpeakerOp.QUERY))
        assertTrue(f.ran.isEmpty())
        f.on = true
        assertEquals(Status.SPEAKER_ON_NO_SHIZUKU, f.handle(SpeakerOp.OFF))
        assertTrue(f.on)
    }

    @Test fun aFailingActionIsAFailedStatus() {
        assertEquals(Status.FAILED, Fake(on = false, fail = true).handle(SpeakerOp.ON))
    }

    /** What the GATT server does with a frame: verified commands reach the handler, unknown ids stop at the codec. */
    @Test fun unknownCommandIdIsRejectedBeforeAnythingRuns() {
        val key = ByteArray(32) { 7 }
        val nonce = ByteArray(16) { 9 }
        val mac: (ByteArray) -> ByteArray = { Hmac.sha256(key, it) }
        val body = byteArrayOf(0x42, 1)
        val frame = body + mac("cmd".toByteArray() + nonce + body).copyOf(16)
        val r = CommandFrame.verify(mac, nonce, frame) as CommandFrame.Result.Invalid
        assertEquals(Status.UNKNOWN_COMMAND, r.status)
        val good = CommandFrame.verify(mac, nonce, CommandFrame.encode(mac, nonce, Command.SPEAKER, SpeakerCodec.encodeArgs(SpeakerOp.TOGGLE)))
        assertEquals(Command.SPEAKER, (good as CommandFrame.Result.Valid).command)
    }
}
