package dev.ilgax.utils.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import dev.ilgax.utils.protocol.NowArt
import dev.ilgax.utils.protocol.NowPlaying
import dev.ilgax.utils.protocol.NowPlayingFrame
import dev.ilgax.utils.protocol.NowPlayingPush
import dev.ilgax.utils.protocol.PlayState
import dev.ilgax.utils.protocol.Status
import dev.ilgax.utils.protocol.Transport
import java.io.ByteArrayOutputStream

/**
 * The tablet's view of "what is playing": reads the active media sessions (needs the notification-listener grant, see
 * [NotificationAccess]), turns the current one into a [NowPlaying] and a cover JPEG, acts on it for NEXT / PREVIOUS / SEEK, and,
 * while a watch is subscribed, tells [onChange] (debounced to about 300 ms) when the state is worth a ping.
 *
 * Reads are always computed fresh from the sessions; the callbacks below only decide when to ping.
 */
internal class NowPlayingMonitor private constructor(context: Context) {
    private val app = context.applicationContext
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val component = NotificationAccess.component(app)
    private val lock = Any()

    // region reading

    /** Active sessions in the system's priority order; empty without the grant. */
    private fun controllers(): List<MediaController> = try {
        if (NotificationAccess.isGranted(app)) sessions.getActiveSessions(component) else emptyList()
    } catch (e: SecurityException) {
        emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun current(): MediaController? {
        val list = controllers()
        val i = NowPlayingLogic.pick(list.map { it.playbackState?.state ?: PlaybackState.STATE_NONE }) ?: return null
        return list[i]
    }

    private fun durationOf(md: MediaMetadata?): Long = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L).let { if (it > 0) it else -1L }

    private fun positionNow(ps: PlaybackState, durationMs: Long): Long {
        if (ps.position == PlaybackState.PLAYBACK_POSITION_UNKNOWN) return 0L
        val age = if (ps.lastPositionUpdateTime > 0) SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime else 0L
        return Transport.positionAt(ps.position, NowPlayingLogic.speedPercent(ps.state, ps.playbackSpeed), age, durationMs)
    }

    private fun appLabel(pkg: String): String = try {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.')
    }

    @Volatile private var artKey: String? = null
    @Volatile private var artJpeg = ByteArray(0)

    /** The state as it is right now (and, as a side effect, the cover that goes with it, see [art]). */
    fun snapshot(): NowPlaying = synchronized(lock) {
        val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (!NotificationAccess.isGranted(app)) return NowPlaying(access = false, volume = vol, volumeMax = max)
        val c = current() ?: return NowPlaying(volume = vol, volumeMax = max).also { setArt(null, null) }
        val ps = c.playbackState
        val md = c.metadata
        val duration = durationOf(md)
        val state = ps?.state ?: PlaybackState.STATE_NONE
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val cover = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        setArt("${c.packageName}|$title|$artist|$album|${cover?.width}x${cover?.height}", cover)
        val actions = ps?.actions ?: 0L
        NowPlaying(
            access = true,
            state = if (NowPlayingLogic.isPlaying(state)) PlayState.PLAYING else PlayState.PAUSED,
            canNext = NowPlayingLogic.canNext(actions),
            canPrevious = NowPlayingLogic.canPrevious(actions),
            canSeek = NowPlayingLogic.canSeek(actions) && duration > 0,
            speedPercent = NowPlayingLogic.speedPercent(state, ps?.playbackSpeed ?: 0f),
            positionMs = if (ps == null) 0 else positionNow(ps, duration).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            durationMs = if (duration > 0) duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else -1,
            volume = vol, volumeMax = max,
            artHash = NowArt.hashOf(artJpeg), artSize = artJpeg.size,
            app = appLabel(c.packageName), title = title, artist = artist, album = album,
        )
    }

    /** What the watch gets for a GET_NOW_PLAYING; remembered as "already delivered" so it is not pinged about it again. */
    fun readForWatch(): NowPlaying = snapshot().also {
        synchronized(lock) { lastPushed = it; lastPushedAt = SystemClock.elapsedRealtime() }
    }

    /** The cover belonging to the latest [snapshot]; empty when there is none. */
    fun art(): ByteArray = synchronized(lock) {
        if (artKey == null) snapshot()
        artJpeg
    }

    private fun setArt(key: String?, cover: Bitmap?) {
        if (key == artKey && key != null) return
        artKey = key ?: ""
        artJpeg = if (cover == null) ByteArray(0) else encodeCover(cover) ?: ByteArray(0)
    }

    private fun encodeCover(src: Bitmap): ByteArray? = try {
        NowPlayingLogic.chooseArt { edge, quality ->
            val side = minOf(src.width, src.height)
            if (side <= 0) return@chooseArt null
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val scaled = Bitmap.createScaledBitmap(square, edge, edge, true)
            val out = ByteArrayOutputStream(5000)
            val ok = scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (scaled !== square) scaled.recycle()
            if (square !== src) square.recycle()
            if (ok) out.toByteArray() else null
        }
    } catch (t: Throwable) {
        null
    }

    // endregion

    // region transport

    /** NEXT / PREVIOUS through the current session; false when there is none (the caller then sends a media key). */
    fun skip(next: Boolean): Boolean {
        val c = current() ?: return false
        if (next) c.transportControls.skipToNext() else c.transportControls.skipToPrevious()
        return true
    }

    /** SEEK by [deltaSeconds] from where the session is now, clamped to the track. NOT_AVAILABLE without a seekable session. */
    fun seek(deltaSeconds: Int): Status {
        val c = current() ?: return Status.NOT_AVAILABLE
        val ps = c.playbackState ?: return Status.NOT_AVAILABLE
        if (!NowPlayingLogic.canSeek(ps.actions)) return Status.NOT_AVAILABLE
        val duration = durationOf(c.metadata)
        c.transportControls.seekTo(Transport.seekTarget(positionNow(ps, duration), deltaSeconds, duration))
        return Status.SUCCESS
    }

    // endregion

    // region change pings

    @Volatile var onChange: (() -> Unit)? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listening = false
    private var pending = false
    private var watched: List<MediaController> = emptyList()
    private var lastPushed: NowPlaying? = null
    private var lastPushedAt = 0L

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = schedule()
        override fun onMetadataChanged(metadata: MediaMetadata?) = schedule()
        override fun onSessionDestroyed() = schedule()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
        handler?.post { watchControllers() }
        schedule()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = schedule()
    }

    /** Starts or stops watching the sessions; called with the watch's subscription to NOW_PLAYING. */
    fun setListening(on: Boolean) {
        synchronized(lock) {
            if (on == listening) return
            listening = on
            if (on) {
                val t = HandlerThread("tabremote-media").apply { start() }
                thread = t
                handler = Handler(t.looper).also { h -> h.post { startWatching(h) } }
            } else {
                val t = thread
                val h = handler
                thread = null
                handler = null
                h?.post { stopWatching() }
                t?.quitSafely()
            }
        }
    }

    /** The listener (re)connected: access may have just been granted while a watch was already subscribed. */
    fun refresh() {
        handler?.post { watchControllers() }
        schedule()
    }

    private fun startWatching(h: Handler) {
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, component, h)
        } catch (e: SecurityException) {
            // No grant yet: refresh() runs when the listener connects.
        }
        app.registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), null, h, Context.RECEIVER_NOT_EXPORTED)
        watchControllers()
    }

    private fun stopWatching() {
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        try {
            app.unregisterReceiver(volumeReceiver)
        } catch (_: Exception) {
        }
        watched.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        watched = emptyList()
        pending = false
    }

    private fun watchControllers() {
        val h = handler ?: return
        watched.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        watched = controllers()
        watched.forEach { runCatching { it.registerCallback(controllerCallback, h) } }
    }

    private fun schedule() {
        val h = handler ?: return
        synchronized(lock) {
            if (pending) return
            pending = true
        }
        h.postDelayed({ push() }, DEBOUNCE_MS)
    }

    private fun push() {
        val np = snapshot()
        val fire = synchronized(lock) {
            pending = false
            if (!listening) return
            val needed = NowPlayingPush.needed(lastPushed, SystemClock.elapsedRealtime() - lastPushedAt, np)
            if (needed) { lastPushed = np; lastPushedAt = SystemClock.elapsedRealtime() }
            needed
        }
        if (fire) onChange?.invoke()
    }

    // endregion

    companion object {
        const val DEBOUNCE_MS = 300L

        @Volatile private var instance: NowPlayingMonitor? = null

        fun get(context: Context): NowPlayingMonitor =
            instance ?: synchronized(this) { instance ?: NowPlayingMonitor(context).also { instance = it } }
    }
}
