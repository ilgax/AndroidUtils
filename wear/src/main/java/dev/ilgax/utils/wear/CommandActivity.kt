package dev.ilgax.utils.wear

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.ilgax.utils.protocol.Command
import dev.ilgax.utils.protocol.HostFeatures
import dev.ilgax.utils.protocol.Protocol
import dev.ilgax.utils.protocol.SpeakerCodec
import dev.ilgax.utils.protocol.SpeakerOp

/** Sends one command immediately and shows a tiny status. Launched by the tile. */
class CommandActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchUpdater.get(this) // registers the "noticed an offer while the app is closed" hook for this process
        RemoteClient.get(this)
        // Exported for the tile host: only a fixed set of commands is accepted, with validated args.
        val cmd = Command.fromId(intent.getIntExtra(EXTRA_CMD, -1))
        if (cmd == null || cmd !in ALLOWED) return finish()
        val slotCommand = cmd == Command.OPEN_APP || cmd == Command.SWITCH_APP
        val arg = intent.getIntExtra(EXTRA_ARG, -1)
        if (slotCommand && arg !in 0 until Protocol.APP_SLOTS) return finish()
        // SPEAKER takes a SpeakerOp; the tile asks for a toggle. Anything else from outside is dropped.
        val speakerCommand = cmd == Command.SPEAKER
        if (speakerCommand && SpeakerOp.fromArg(arg) == null) return finish()
        val args = when {
            slotCommand -> byteArrayOf(arg.toByte())
            speakerCommand -> SpeakerCodec.encodeArgs(SpeakerOp.fromArg(arg)!!)
            else -> ByteArray(0)
        }
        val prefs = WatchPrefs.get(this)
        // The tile names the host it showed; a layout from before the update has none, then the tile rule decides.
        val named = intent.getStringExtra(EXTRA_HOST)
        if (named != null && named !in prefs.hosts.ids) return finish()

        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        val text = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xCC000000.toInt())
            addView(spinner, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(text)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            },
        )

        val client = RemoteClient.get(this)
        val deliver = { host: String? ->
            val label = if (slotCommand && host != null) prefs.slotLabels(host).getOrNull(arg)?.ifEmpty { null } else null
            val entry = host?.let { prefs.hosts.get(it) }
            if (entry != null && !HostFeatures.available(cmd, entry.isNative)) {
                finish() // the built-in watch-phone features cover this one
            } else {
                client.send(cmd, args, host = host) { result ->
                    if (!isDestroyed) {
                        spinner.visibility = View.GONE
                        text.text = ResultText.announce(this, if (host == null) Result.NOT_FOUND else result, cmd, label)
                        main.postDelayed({ finish() }, 1000)
                    }
                }
            }
        }
        if (named != null) deliver(named) else Presence.get(this).burstOnce { if (!isDestroyed) deliver(Presence.get(this).tileState().targetId) }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_ARG = "arg"
        const val EXTRA_HOST = "host"
        private val ALLOWED = Command.entries.toSet() - setOf(Command.PING, Command.SYNC_APPS, Command.HELLO, Command.GET_OFFER, Command.UPDATE_STATE, Command.RUN_MACRO, Command.SYNC_MACROS, Command.SEEK, Command.GET_NOW_PLAYING, Command.GET_ART, Command.GET_INFO)
    }
}
