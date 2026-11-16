package dev.ilgax.utils.speaker

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** What the redirect experiment should be doing right now. */
internal sealed interface RedirectPlan {
    data object None : RedirectPlan
    data object EveryApp : RedirectPlan
    data class OnlyUids(val uids: Set<Int>) : RedirectPlan
}

/** The decisions of the two experiments, free of Android so they are unit tested. */
internal object ExperimentPlan {
    private val INTERNAL = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_FM,
    )

    /** Anything that is not built in. Our own fake wired headphones ([fakeWired]) do not count. */
    fun externalOutput(types: Collection<Int>, fakeWired: Boolean): Boolean {
        var skippedFake = !fakeWired
        for (t in types) {
            if (t in INTERNAL) continue
            if (!skippedFake && t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) { skippedFake = true; continue }
            return true
        }
        return false
    }

    /**
     * The fake headset takes priority over every other output, Bluetooth included, so it is only connected while nothing
     * external is: with the buds in, it would send their audio nowhere.
     */
    fun wiredWanted(switchOn: Boolean, toggleOn: Boolean, externalOutput: Boolean) = switchOn && toggleOn && !externalOutput

    /** Nothing external: capture every app. Something external: only the apps the scan found on the speaker. */
    fun redirect(switchOn: Boolean, toggleOn: Boolean, externalOutput: Boolean, speakerUids: Set<Int>): RedirectPlan = when {
        !switchOn || !toggleOn -> RedirectPlan.None
        !externalOutput -> RedirectPlan.EveryApp
        speakerUids.isNotEmpty() -> RedirectPlan.OnlyUids(speakerUids)
        else -> RedirectPlan.None
    }
}

/**
 * The two experimental hard blocks. Both are off by default, engage only while the kill switch is ON, undo themselves when
 * their toggle or the kill switch goes OFF (or the service stops), and report how it went in [redirectStatus] and
 * [wiredStatus] and in Diagnostics instead of crashing.
 */
internal object SpeakerExperiments {
    const val K_REDIRECT = "exp_redirect"
    const val K_WIRED = "exp_wired"
    /** The fake headset may be connected inside the audio server; cleared once it is disconnected (or the device rebooted). */
    const val K_WIRED_LIVE = "exp_wired_live"
    private val WIRED_TYPE = AudioDeviceInfo.TYPE_WIRED_HEADPHONES

    private val _redirectStatus = MutableStateFlow("Off")
    val redirectStatus: StateFlow<String> = _redirectStatus.asStateFlow()
    private val _wiredStatus = MutableStateFlow("Off")
    val wiredStatus: StateFlow<String> = _wiredStatus.asStateFlow()

    /** The last error of each experiment stays visible until it is toggled again. */
    @Volatile private var wiredFailure: String? = null
    @Volatile private var redirectFailure: String? = null
    @Volatile private var redirectApplied: RedirectPlan = RedirectPlan.None
    @Volatile private var redirectWanted: RedirectPlan = RedirectPlan.None
    private val redirectExec = Executors.newSingleThreadExecutor()

    fun isRedirectOn(ctx: Context) = SpeakerController.prefsOf(ctx).getBoolean(K_REDIRECT, false)
    fun isWiredOn(ctx: Context) = SpeakerController.prefsOf(ctx).getBoolean(K_WIRED, false)
    private fun wiredLive(ctx: Context) = SpeakerController.prefsOf(ctx).getBoolean(K_WIRED_LIVE, false)

    fun primeStatus(ctx: Context) {
        if (!isRedirectOn(ctx)) _redirectStatus.value = "Off" else if (_redirectStatus.value == "Off") _redirectStatus.value = ARMED
        if (!isWiredOn(ctx)) _wiredStatus.value = "Off" else if (_wiredStatus.value == "Off") _wiredStatus.value = ARMED
    }

    fun setRedirect(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        SpeakerController.prefsOf(app).edit().putBoolean(K_REDIRECT, on).apply()
        redirectFailure = null
        SpeakerController.log(app, "experiment redirect -> ${if (on) "ON" else "OFF"}")
        if (on) { _redirectStatus.value = ARMED; SpeakerController.enforce(app, "experiment toggle", true) }
        else { _redirectStatus.value = "Off"; redirectWanted = RedirectPlan.None; SpeakerController.runOnExec { stopRedirect(app) } }
    }

    fun setWired(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        SpeakerController.prefsOf(app).edit().putBoolean(K_WIRED, on).apply()
        wiredFailure = null
        SpeakerController.log(app, "experiment fake wired headset -> ${if (on) "ON" else "OFF"}")
        if (on) { _wiredStatus.value = ARMED; SpeakerController.enforce(app, "experiment toggle", true) }
        else SpeakerController.runOnExec { disconnectWired(app, SpeakerController.hiddenOrNull(app)); _wiredStatus.value = "Off" }
    }

    /** Called from every enforce / scan: brings both experiments in line with the kill switch, the outputs and the apps on the speaker. */
    fun reconcile(ctx: Context, h: Hidden?, am: AudioManager, switchOn: Boolean, speakerUids: Set<Int>) {
        val external = ExperimentPlan.externalOutput(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }, wiredLive(ctx))
        reconcileWired(ctx, h, switchOn, external)
        reconcileRedirect(ctx, switchOn, external, speakerUids)
    }

    private fun reconcileWired(ctx: Context, h: Hidden?, switchOn: Boolean, external: Boolean) {
        val toggle = isWiredOn(ctx)
        val wanted = ExperimentPlan.wiredWanted(switchOn, toggle, external) && wiredFailure == null
        val live = wiredLive(ctx)
        when {
            wanted && !live -> connectWired(ctx, h)
            !wanted && live -> disconnectWired(ctx, h)
        }
        if (!toggle) { _wiredStatus.value = "Off"; return }
        _wiredStatus.value = when {
            wiredFailure != null -> "Not working · $wiredFailure"
            wiredLive(ctx) -> "Engaged · fake wired headphones connected"
            !switchOn -> ARMED
            external -> "Waiting · an external output is connected"
            else -> _wiredStatus.value
        }
    }

    private fun connectWired(ctx: Context, h: Hidden?) {
        if (h == null) { _wiredStatus.value = "Needs Shizuku"; return }
        try {
            // Mark the flag first: if the process dies between the call and the write, the next start still knows to disconnect.
            SpeakerController.prefsOf(ctx).edit().putBoolean(K_WIRED_LIVE, true).apply()
            h.setWiredDeviceConnectionState(WIRED_TYPE, true)
            SpeakerController.log(ctx, "fake wired headphones connected")
        } catch (t: Throwable) {
            wiredFailure = failureText(t)
            SpeakerController.error(ctx, "setWiredDeviceConnectionState(connect)", t)
            disconnectWired(ctx, h)
        }
    }

    /** True when nothing is connected afterwards. With Shizuku missing the flag stays and [retryPending] finishes the job. */
    fun disconnectWired(ctx: Context, h: Hidden?): Boolean {
        if (!wiredLive(ctx)) return true
        if (h == null) {
            SpeakerController.log(ctx, "fake wired headphones stay connected: Shizuku unavailable, will disconnect when it is ready")
            return false
        }
        return try {
            h.setWiredDeviceConnectionState(WIRED_TYPE, false)
            SpeakerController.prefsOf(ctx).edit().putBoolean(K_WIRED_LIVE, false).apply()
            SpeakerController.log(ctx, "fake wired headphones disconnected")
            true
        } catch (t: Throwable) {
            SpeakerController.error(ctx, "setWiredDeviceConnectionState(disconnect)", t)
            false
        }
    }

    private fun reconcileRedirect(ctx: Context, switchOn: Boolean, external: Boolean, uids: Set<Int>) {
        val toggle = isRedirectOn(ctx)
        val plan = if (redirectFailure != null) RedirectPlan.None else ExperimentPlan.redirect(switchOn, toggle, external, uids)
        redirectWanted = plan
        if (plan != redirectApplied) {
            val app = ctx.applicationContext
            redirectExec.execute { applyRedirect(app) }
        }
        if (!toggle) { _redirectStatus.value = "Off"; return }
        if (redirectFailure != null) _redirectStatus.value = "Not working · $redirectFailure"
        else if (!switchOn) _redirectStatus.value = ARMED
        else if (plan == RedirectPlan.None) _redirectStatus.value = "Waiting · an external output is connected and no app is on the speaker"
    }

    private fun applyRedirect(ctx: Context) {
        val plan = redirectWanted
        if (plan == redirectApplied) return
        if (plan == RedirectPlan.None) { stopRedirect(ctx); return }
        if (ShizukuState.current(ctx) != ShizukuStatus.Ready) { _redirectStatus.value = "Needs Shizuku"; return }
        val uids = (plan as? RedirectPlan.OnlyUids)?.uids?.toIntArray() ?: IntArray(0)
        try {
            val answer = SpeakerServiceClient.get(ctx).call { it.startRedirect(uids) }
            if (answer.startsWith("OK")) {
                redirectApplied = plan
                _redirectStatus.value = "Engaged · ${answer.removePrefix("OK ").trim()}"
                SpeakerController.log(ctx, "redirect: $answer")
            } else {
                redirectFailure = answer.removePrefix("ERR ").trim()
                _redirectStatus.value = "Not working · $redirectFailure"
                redirectApplied = RedirectPlan.None
                SpeakerController.log(ctx, "ERR redirect: $answer")
                stopRedirect(ctx)
            }
        } catch (t: Throwable) {
            redirectFailure = failureText(t)
            _redirectStatus.value = "Not working · $redirectFailure"
            SpeakerController.error(ctx, "redirect start", t)
            stopRedirect(ctx)
        }
    }

    /** Drops the audio policy: unregisters it and ends the shell process (which also takes it down if Shizuku dies). */
    private fun stopRedirect(ctx: Context) {
        val was = redirectApplied != RedirectPlan.None
        redirectApplied = RedirectPlan.None
        val client = SpeakerServiceClient.get(ctx)
        if (client.isBound()) {
            try { client.call { it.stopRedirect() } } catch (t: Throwable) { SpeakerController.error(ctx, "redirect stop", t) }
        }
        client.unbind()
        if (was) SpeakerController.log(ctx, "redirect stopped")
    }

    /** Service destroyed: everything the experiments hold is let go, whatever the toggles say. */
    fun disengageAll(ctx: Context, h: Hidden?) {
        redirectWanted = RedirectPlan.None
        stopRedirect(ctx)
        disconnectWired(ctx, h)
        primeStatus(ctx)
        if (isWiredOn(ctx)) _wiredStatus.value = if (wiredLive(ctx)) "Pending disconnect · waiting for Shizuku" else ARMED
        if (isRedirectOn(ctx)) _redirectStatus.value = ARMED
    }

    /** A stale "connected" flag with no switch to justify it: disconnect once Shizuku is ready. True while something is still pending. */
    fun retryPending(ctx: Context, h: Hidden?): Boolean {
        if (!wiredLive(ctx)) return false
        if (SpeakerController.isEnabled(ctx) && isWiredOn(ctx)) return false
        return !disconnectWired(ctx, h)
    }

    fun hasPending(ctx: Context) = wiredLive(ctx) && !(SpeakerController.isEnabled(ctx) && isWiredOn(ctx))

    /** After a reboot the audio server starts clean, so a flag left from before it is simply stale. */
    fun clearStaleAtBoot(ctx: Context) {
        if (wiredLive(ctx)) {
            SpeakerController.prefsOf(ctx).edit().putBoolean(K_WIRED_LIVE, false).apply()
            SpeakerController.log(ctx, "boot: cleared stale fake wired headset flag")
        }
    }

    fun diagnostics(ctx: Context): String {
        val sb = StringBuilder("\nExperiments (off by default):\n")
        sb.append("  redirect audio into Utils: ${if (isRedirectOn(ctx)) "ON" else "off"} · ${_redirectStatus.value}\n")
        sb.append("  fake wired headset: ${if (isWiredOn(ctx)) "ON" else "off"} · ${_wiredStatus.value} · connected flag=${wiredLive(ctx)}\n")
        return sb.toString()
    }

    private fun failureText(t: Throwable): String {
        var r = t
        while ((r is java.lang.reflect.InvocationTargetException) && r.cause != null) r = r.cause!!
        val msg = r.message ?: ""
        val hint = if (r is SecurityException || msg.contains("permission", ignoreCase = true)) " (permission denied)" else ""
        return "${r.javaClass.simpleName}${if (msg.isEmpty()) "" else ": $msg"}$hint".take(160)
    }

    private const val ARMED = "Armed · engages while the kill switch is ON"
}
