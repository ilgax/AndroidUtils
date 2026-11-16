package dev.ilgax.utils.speaker

import kotlin.system.exitProcess

/** Shizuku UserService entry point (shell uid, own process). Shizuku instantiates it through the no-arg constructor. */
class SpeakerUserService : ISpeakerService.Stub() {
    private val engine = PolicyEngine()

    override fun startRedirect(uids: IntArray): String = engine.start(uids)
    override fun stopRedirect(): String = engine.stop()
    override fun status(): String = engine.status()

    override fun destroy() {
        engine.stop()
        exitProcess(0)
    }
}
