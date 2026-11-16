package dev.ilgax.utils.remote

import dev.ilgax.utils.protocol.SpeakerCodec
import dev.ilgax.utils.protocol.SpeakerOp
import dev.ilgax.utils.protocol.Status

/** Decision logic of the SPEAKER command, free of Android so it can be unit tested with fakes. */
internal object SpeakerRemote {
    /**
     * [run] performs a registry action ("speaker.on" / "speaker.off" / "speaker.toggle") and says whether it succeeded.
     * Nothing is changed unless Shizuku is ready (the macro steps refuse too); the reply then carries the state as it is,
     * flagged "no Shizuku". After a change the state is read back, so the reply is what the tablet really ended up with.
     */
    fun handle(op: SpeakerOp, isOn: () -> Boolean, shizukuReady: () -> Boolean, run: (String) -> Boolean): Status {
        val ready = shizukuReady()
        if (op != SpeakerOp.QUERY && ready) {
            val id = when (op) {
                SpeakerOp.ON -> "speaker.on"
                SpeakerOp.OFF -> "speaker.off"
                else -> "speaker.toggle"
            }
            val current = isOn()
            val wanted = when (op) { SpeakerOp.ON -> true; SpeakerOp.OFF -> false; else -> !current }
            if (wanted != current && !run(id)) return Status.FAILED
        }
        return SpeakerCodec.reply(isOn(), ready)
    }
}
