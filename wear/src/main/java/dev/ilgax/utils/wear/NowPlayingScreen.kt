package dev.ilgax.utils.wear

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.ilgax.utils.protocol.Command
import dev.ilgax.utils.protocol.NowPlaying
import dev.ilgax.utils.protocol.PlayState
import dev.ilgax.utils.protocol.RotaryAccumulator
import dev.ilgax.utils.protocol.Transport
import dev.ilgax.utils.protocol.VolumeCoalescer

/**
 * The "Now playing" screen, hosted by [MainActivity] (so the link policy and the screen-off handling stay in one place).
 * Plain views: cover as a dimmed circular backdrop, a progress ring around the edge, a volume arc on the right that shows
 * while the bezel turns, large previous / play-pause / next and smaller -10 s / +30 s.
 *
 * The state arrives by ping + authenticated read ([RemoteClient.syncNowPlaying]); between updates the progress is extrapolated
 * locally from position, speed and the time it was received ([Transport.positionAt]).
 */
class NowPlayingScreen(private val activity: Activity, private val client: RemoteClient) {
    private val ui = Handler(Looper.getMainLooper())
    private val density = activity.resources.displayMetrics.density

    private var np: NowPlaying? = null
    /** elapsedRealtime when [np] was received (or last rebased by a local action). */
    private var receivedAt = 0L
    private var message: String? = null
    private var cover: Bitmap? = null
    private var coverHash = 0
    private var active = false
    private var paused = false
    private var busy = false
    private var dirty = false
    private var volumeShownUntil = 0L
    private var lastRotaryAt = 0L

    private val volume = VolumeCoalescer()
    private val rotary = RotaryAccumulator(ROTARY_STEP_UNITS)

    private val face = Face(activity)
    private val appText = text(11f, 0xFF90A4AE.toInt())
    private val titleText = text(17f, Color.WHITE, bold = true).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true // marquee only runs on a selected (or focused) view
    }
    private val artistText = text(13f, 0xFFCFD8DC.toInt()).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val prev = Glyph(activity, Glyph.Kind.PREVIOUS)
    private val next = Glyph(activity, Glyph.Kind.NEXT)
    private val play = Glyph(activity, Glyph.Kind.PLAY)
    private val back = pill(activity.getString(R.string.np_seek_back))
    private val forward = pill(activity.getString(R.string.np_seek_forward))
    private val player = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }
    private val messageText = text(14f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
        setOnClickListener { sync() } // tap to retry
    }

    val root: View

    init {
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(prev, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(play, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginStart = dp(8); marginEnd = dp(8) })
            addView(next, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val seek = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(back, LinearLayout.LayoutParams(dp(58), dp(32)))
            addView(forward, LinearLayout.LayoutParams(dp(58), dp(32)).apply { marginStart = dp(8) })
        }
        player.addView(appText, wrap())
        player.addView(titleText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        player.addView(artistText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        player.addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        player.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val round = activity.resources.configuration.isScreenRound
        val sideInset = if (round) (activity.resources.displayMetrics.widthPixels * 0.14f).toInt() else dp(12)
        val vertInset = if (round) (activity.resources.displayMetrics.heightPixels * 0.10f).toInt() else dp(10)
        val frame = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            addView(face, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                player,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                    .apply { setMargins(sideInset, vertInset, sideInset, vertInset) },
            )
            addView(
                messageText,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                    .apply { setMargins(sideInset, vertInset, sideInset, vertInset) },
            )
            // Bezel or crown: volume.
            setOnGenericMotionListener { v, e ->
                if (e.action == MotionEvent.ACTION_SCROLL && e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                    onRotary(v, -e.getAxisValue(MotionEvent.AXIS_SCROLL))
                    true
                } else false
            }
        }
        root = frame
        prev.setOnClickListener { transport(Command.PREVIOUS) }
        next.setOnClickListener { transport(Command.NEXT) }
        play.setOnClickListener { togglePlay() }
        back.setOnClickListener { seekBy(-SEEK_BACK_S) }
        forward.setOnClickListener { seekBy(SEEK_FORWARD_S) }
        render()
    }

    // region lifecycle

    /** The screen is shown: keep the link (the host holds the foreground flag), subscribe, read. */
    fun start() {
        active = true
        paused = false
        client.onNowPlayingHint = { sync() }
        root.requestFocus()
        sync()
        ticker.run()
    }

    /** Screen left or app stopped: drop the subscription so the tablet stops watching its sessions. */
    fun stop() {
        if (!active) return
        active = false
        client.onNowPlayingHint = null
        ui.removeCallbacksAndMessages(null)
        volume.clear()
        client.stopNowPlaying()
    }

    /** Screen off / ambient: no animation, nothing to refresh. The link follows the host's screen-off handling. */
    fun pause() {
        paused = true
        ui.removeCallbacks(ticker)
    }

    /** Screen on again: the link may have dropped meanwhile, so read (and re-subscribe) now. */
    fun resume() {
        if (!active) return
        paused = false
        sync()
        ticker.run()
    }

    // endregion

    // region state

    private fun sync() {
        if (!active) return
        if (busy) {
            dirty = true // a ping during a read: read again afterwards
            return
        }
        busy = true
        dirty = false
        client.syncNowPlaying(coverHash) { ev ->
            when (ev) {
                is NowPlayingSync.State -> {
                    message = null
                    // While the bezel is turning the local volume is the truth: the tablet's value lags by a round trip.
                    val keepVolume = SystemClock.elapsedRealtime() - lastRotaryAt < ROTARY_QUIET_MS
                    val local = np
                    np = if (keepVolume && local != null) ev.state.copy(volume = local.volume) else ev.state
                    receivedAt = SystemClock.elapsedRealtime()
                    if (ev.state.artHash == 0) setCover(null, 0)
                    else if (ev.state.artHash != coverHash) setCover(null, 0) // the old one is not this track's
                    render()
                }
                is NowPlayingSync.Art -> {
                    if (np?.artHash == ev.hash) setCover(BitmapFactory.decodeByteArray(ev.jpeg, 0, ev.jpeg.size), ev.hash)
                    render()
                }
                NowPlayingSync.Unsupported -> {
                    np = null
                    setCover(null, 0)
                    message = activity.getString(R.string.np_update_utils)
                    render()
                }
                is NowPlayingSync.Failed -> if (np == null) {
                    message = ResultText.describe(activity, ev.result, Command.GET_NOW_PLAYING) + "\n" + activity.getString(R.string.np_tap_retry)
                    render()
                }
                NowPlayingSync.Finished -> {
                    busy = false
                    if (dirty) sync()
                }
            }
        }
    }

    private fun setCover(bitmap: Bitmap?, hash: Int) {
        cover = bitmap
        coverHash = hash
        face.cover = bitmap
    }

    private fun positionNow(): Long {
        val s = np ?: return 0
        return Transport.positionAt(s.positionMs.toLong(), s.speedPercent, SystemClock.elapsedRealtime() - receivedAt, s.durationMs.toLong())
    }

    private fun render() {
        val s = np
        val msg = message
        val text = when {
            msg != null && s == null -> msg
            s != null && !s.access -> activity.getString(R.string.np_grant_access)
            s != null && s.state == PlayState.NONE -> activity.getString(R.string.np_nothing)
            s == null -> activity.getString(R.string.np_loading)
            else -> null
        }
        messageText.visibility = if (text != null) View.VISIBLE else View.GONE
        player.visibility = if (text == null) View.VISIBLE else View.INVISIBLE
        if (text != null) {
            messageText.text = text
            face.progress = null
            face.volume = null
            face.invalidate()
            return
        }
        s!!
        if (appText.text.toString() != s.app) appText.text = s.app
        if (titleText.text.toString() != s.title) titleText.text = s.title.ifEmpty { s.app }
        if (artistText.text.toString() != s.artist) artistText.text = s.artist
        artistText.visibility = if (s.artist.isEmpty()) View.GONE else View.VISIBLE
        play.kind = if (s.state == PlayState.PLAYING) Glyph.Kind.PAUSE else Glyph.Kind.PLAY
        prev.isEnabled = s.canPrevious
        next.isEnabled = s.canNext
        back.isEnabled = s.canSeek
        forward.isEnabled = s.canSeek
        back.alpha = if (s.canSeek) 1f else 0.35f
        forward.alpha = if (s.canSeek) 1f else 0.35f
        updateProgress()
    }

    /** Redraws the ring and the volume arc from the extrapolated position; cheap enough to run a few times a second. */
    private fun updateProgress() {
        val s = np ?: return
        face.progress = Transport.fraction(positionNow(), s.durationMs.toLong())
        val showVolume = s.volumeMax > 0 && SystemClock.elapsedRealtime() < volumeShownUntil
        face.volume = if (showVolume) s.volume.toFloat() / s.volumeMax else null
        face.invalidate()
    }

    private val ticker = object : Runnable {
        override fun run() {
            ui.removeCallbacks(this)
            if (!active || paused) return
            updateProgress()
            // Only animate while something moves (or the volume arc is fading out).
            val s = np
            val moving = s != null && s.state == PlayState.PLAYING && s.speedPercent != 0
            if (moving || SystemClock.elapsedRealtime() < volumeShownUntil + 100) ui.postDelayed(this, TICK_MS)
        }
    }

    // endregion

    // region actions

    private fun transport(command: Command) {
        Haptics.tick(activity)
        client.send(command) { r ->
            if (!r.isOk) {
                ResultText.announce(activity, r, command).let { Toast.makeText(activity, it, Toast.LENGTH_SHORT).show() }
            }
            // The tablet pings about the new track by itself; a failure leaves the screen as it was.
        }
    }

    private fun togglePlay() {
        val s = np ?: return
        Haptics.tick(activity)
        val playing = s.state == PlayState.PLAYING
        // Optimistic: flip now, the tablet's ping confirms (or corrects) it.
        np = s.copy(state = if (playing) PlayState.PAUSED else PlayState.PLAYING, positionMs = positionNow().toInt(), speedPercent = if (playing) 0 else 100)
        receivedAt = SystemClock.elapsedRealtime()
        render()
        ticker.run()
        client.send(Command.PLAY_PAUSE) { r ->
            if (!r.isOk) Toast.makeText(activity, ResultText.announce(activity, r, Command.PLAY_PAUSE), Toast.LENGTH_SHORT).show()
            sync()
        }
    }

    private fun seekBy(seconds: Int) {
        val s = np ?: return
        if (!s.canSeek) return
        Haptics.tick(activity)
        np = s.copy(positionMs = Transport.seekTarget(positionNow(), seconds, s.durationMs.toLong()).toInt())
        receivedAt = SystemClock.elapsedRealtime()
        updateProgress()
        client.send(Command.SEEK, Transport.encodeSeek(seconds)) { r ->
            if (!r.isOk) {
                Toast.makeText(activity, ResultText.announce(activity, r, Command.SEEK), Toast.LENGTH_SHORT).show()
                sync()
            }
        }
    }

    private fun onRotary(v: View, units: Float) {
        val steps = rotary.feed(units)
        if (steps == 0) return
        val s = np
        lastRotaryAt = SystemClock.elapsedRealtime()
        val atLimit = s != null && s.volumeMax > 0 && ((steps > 0 && s.volume >= s.volumeMax) || (steps < 0 && s.volume <= 0))
        v.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) {
                if (atLimit) ROTARY_SCROLL_LIMIT else ROTARY_SCROLL_TICK
            } else HapticFeedbackConstants.CLOCK_TICK,
        )
        if (s != null && s.volumeMax > 0) {
            np = s.copy(volume = (s.volume + steps).coerceIn(0, s.volumeMax))
            volumeShownUntil = lastRotaryAt + VOLUME_ARC_MS
            updateProgress()
            ticker.run()
        }
        if (!atLimit) {
            volume.add(steps)
            pumpVolume()
        }
    }

    private val volumePump = Runnable { pumpVolume() }

    private fun pumpVolume() {
        ui.removeCallbacks(volumePump)
        val now = SystemClock.elapsedRealtime()
        val step = volume.take(now)
        if (step != 0) {
            client.send(if (step > 0) Command.VOLUME_UP else Command.VOLUME_DOWN) { r ->
                volume.done()
                if (!r.isOk) {
                    volume.clear()
                    Toast.makeText(activity, ResultText.announce(activity, r, null), Toast.LENGTH_SHORT).show()
                }
                pumpVolume()
            }
        } else if (volume.hasPending) {
            ui.postDelayed(volumePump, volume.waitMs(now).coerceAtLeast(30))
        }
    }

    // endregion

    // region views

    private fun dp(v: Int) = (v * density).toInt()

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun text(size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setShadowLayer(4f, 0f, 1f, Color.BLACK) // stays readable over the cover
    }

    private fun pill(label: String) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 0)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        isFocusable = false // keep focus on the root so the rotary input reaches it
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(activity.getColor(R.color.button_bg)) }
    }

    /** The cover as a dimmed disc, the progress ring and the volume arc. */
    private class Face(context: Context) : View(context) {
        var cover: Bitmap? = null
            set(v) { field = v; invalidate() }
        /** 0..1, or null for no ring. */
        var progress: Float? = null
        /** 0..1, or null for no arc. */
        var volume: Float? = null

        private val d = resources.displayMetrics.density
        private val stroke = 4f * d
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = 0x33FFFFFF; strokeCap = Paint.Cap.ROUND }
        private val bar = Paint(track).apply { color = 0xFF64B5F6.toInt() }
        private val vol = Paint(track).apply { color = 0xFFFFFFFF.toInt() }
        private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.PorterDuffColorFilter(0xFF555555.toInt(), android.graphics.PorterDuff.Mode.MULTIPLY)
        }
        private val clip = Path()
        private val oval = RectF()
        private val src = android.graphics.Rect()
        private val dst = RectF()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val size = minOf(w, h)
            val cx = w / 2
            val cy = h / 2
            cover?.let { bmp ->
                val r = size / 2 - stroke * 1.5f
                clip.reset()
                clip.addCircle(cx, cy, r, Path.Direction.CW)
                c.save()
                c.clipPath(clip)
                src.set(0, 0, bmp.width, bmp.height)
                dst.set(cx - r, cy - r, cx + r, cy + r)
                c.drawBitmap(bmp, src, dst, image)
                c.restore()
            }
            val inset = stroke
            oval.set(cx - size / 2 + inset, cy - size / 2 + inset, cx + size / 2 - inset, cy + size / 2 - inset)
            progress?.let { p ->
                c.drawArc(oval, -90f, 360f, false, track)
                if (p > 0f) c.drawArc(oval, -90f, 360f * p, false, bar)
            }
            volume?.let { v ->
                // A short arc on the right edge, centred on 3 o'clock, inside the ring.
                val inner = RectF(oval).apply { inset(stroke * 2.2f, stroke * 2.2f) }
                c.drawArc(inner, -35f, 70f, false, track)
                if (v > 0f) c.drawArc(inner, 35f - 70f * v, 70f * v, false, vol)
            }
        }
    }

    /** A drawn transport glyph on a round background; no icon assets, so the APK stays small. */
    private class Glyph(context: Context, kind: Kind) : View(context) {
        enum class Kind { PREVIOUS, NEXT, PLAY, PAUSE }

        var kind: Kind = kind
            set(v) { if (field != v) { field = v; invalidate() } }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val path = Path()

        init {
            isFocusable = false
            isClickable = true
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val primary = kind == Kind.PLAY || kind == Kind.PAUSE
            fill.color = if (primary) 0xFF3D6FA8.toInt() else 0xFF2B3A4C.toInt()
            c.drawCircle(w / 2, h / 2, minOf(w, h) / 2, fill)
            ink.alpha = if (isEnabled) 255 else 90
            val u = minOf(w, h) / 10f // grid unit
            path.reset()
            when (kind) {
                Kind.PLAY -> { path.moveTo(3.8f * u, 2.8f * u); path.lineTo(7.8f * u, 5f * u); path.lineTo(3.8f * u, 7.2f * u); path.close() }
                Kind.PAUSE -> {
                    path.addRect(3.2f * u, 2.9f * u, 4.6f * u, 7.1f * u, Path.Direction.CW)
                    path.addRect(5.4f * u, 2.9f * u, 6.8f * u, 7.1f * u, Path.Direction.CW)
                }
                Kind.NEXT -> {
                    path.moveTo(2.8f * u, 3f * u); path.lineTo(6.2f * u, 5f * u); path.lineTo(2.8f * u, 7f * u); path.close()
                    path.addRect(6.6f * u, 3f * u, 7.6f * u, 7f * u, Path.Direction.CW)
                }
                Kind.PREVIOUS -> {
                    path.moveTo(7.2f * u, 3f * u); path.lineTo(3.8f * u, 5f * u); path.lineTo(7.2f * u, 7f * u); path.close()
                    path.addRect(2.4f * u, 3f * u, 3.4f * u, 7f * u, Path.Direction.CW)
                }
            }
            c.drawPath(path, ink)
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            invalidate()
        }
    }

    // endregion

    private companion object {
        // Not in the compile SDK's public HapticFeedbackConstants; the platform values of ROTARY_SCROLL_TICK / ROTARY_SCROLL_LIMIT.
        const val ROTARY_SCROLL_TICK = 18
        const val ROTARY_SCROLL_LIMIT = 20
        const val SEEK_BACK_S = 10
        const val SEEK_FORWARD_S = 30
        const val TICK_MS = 250L
        const val VOLUME_ARC_MS = 1_800L
        /** Rotary scroll units (MotionEvent.AXIS_SCROLL) per volume step. */
        const val ROTARY_STEP_UNITS = 1f
        /** After a bezel turn, the tablet's own volume value is ignored for this long. */
        const val ROTARY_QUIET_MS = 1_200L
    }
}
