package dev.ilgax.utils.remote

import android.annotation.SuppressLint
import android.content.Context
import dev.ilgax.utils.protocol.ExpectedBuild
import dev.ilgax.utils.protocol.IntegrityReport
import dev.ilgax.utils.protocol.IntegrityVerdict
import dev.ilgax.utils.protocol.UpdateOffer
import dev.ilgax.utils.protocol.UpdateReport
import dev.ilgax.utils.protocol.WatchIntegrity
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties

/** Progress of a watch app update as the detail screen shows it. */
sealed interface WatchUpdateState {
    data object Idle : WatchUpdateState
    /** Server is up and the offer is available; the watch has not started downloading. */
    data class Waiting(val secondsLeft: Int) : WatchUpdateState
    data class Downloading(val percent: Int) : WatchUpdateState
    /** A download attempt failed and the watch is trying again ([attempt] failed so far). */
    data class Retrying(val attempt: Int) : WatchUpdateState
    /** Fully downloaded (or the watch says so); the watch installs, possibly asking for a tap. */
    data object Installing : WatchUpdateState
    data class Failed(val failure: UpdateReport.Failure) : WatchUpdateState
    data object TimedOut : WatchUpdateState
}

/** What the last installed-binary report said about the watch app. */
class IntegrityResult(val verdict: IntegrityVerdict, val reportedCode: Int)

/** Plain data behind [WatchUpdateState]. */
class UpdateSnapshot(
    val ended: UpdateServer.Reason?,
    val bytesServed: Long,
    val total: Long,
    val deadline: Long,
    val report: UpdateReport?,
)

/**
 * Tablet side of the watch app updater: the bundled APK, the one-shot HTTP server, the current offer and what the
 * watch last told us. The offer is only ever handed out through the authenticated GET_OFFER/OFFER exchange.
 */
class UpdateHost private constructor(context: Context) {
    private val context = context.applicationContext
    private val prefs = RemotePrefs(this.context)
    private val random = SecureRandom()

    /** [certSha256] is the SHA-256 of the signing certificate the build embedded (hex in the properties), if it did. */
    class Bundled(val versionCode: Int, val versionName: String, val certSha256: ByteArray? = null)
    private class Payload(val size: Long, val sha256: ByteArray)

    private class Session(
        val server: UpdateServer,
        val port: Int,
        val token: ByteArray,
        val sha256: ByteArray,
        val size: Long,
        val versionCode: Int,
        val addresses: List<String>,
        val issuedAt: Long,
        val deadline: Long,
        /** Install even at the same version (Reinstall). */
        val force: Boolean = false,
        /** When the user first asked; a refreshed token keeps it so retries do not extend the 10 minute window forever. */
        val intentAt: Long = issuedAt,
    ) {
        @Volatile var ended: UpdateServer.Reason? = null
        @Volatile var report: UpdateReport? = null
        /** Highest APK offset the watch has been sent over Bluetooth (end of a fully sent window). */
        @Volatile var btServed = 0L
    }

    enum class StartResult { STARTED, NO_APK, NO_ADDRESS, FAILED }

    private var bundledInfo: Bundled? = null
    private var bundledRead = false
    private var payload: Payload? = null
    @Volatile private var session: Session? = null

    /** Serves UPDATE_DATA windows of the live offer's APK; one window at a time. */
    val data = UpdateDataServer(source = ::dataSource)

    /** The last installed-binary verdict; null = the watch has not reported one since Utils started. */
    @Volatile var integrity: IntegrityResult? = null
        private set
    /** When the tablet asked the watch for a report that has not arrived yet; 0 = not asking. */
    @Volatile var verifyAskedAt = 0L
        private set

    /** versionCode of the last update the watch reported finishing (HELLO with a higher version than before); -1 = none. */
    val updatedTo: Int? get() = prefs.watchUpdatedTo.takeIf { it >= 0 }

    /** Version of the watch APK inside this app, or null if the build did not bundle one. */
    @Synchronized
    fun bundled(): Bundled? {
        if (!bundledRead) {
            bundledRead = true
            bundledInfo = try {
                val p = Properties().apply { context.assets.open(PROPS_ASSET).use { load(it) } }
                Bundled(
                    p.getProperty("versionCode").toInt(), p.getProperty("versionName"),
                    WatchIntegrity.fromHex(p.getProperty("certSha256")),
                )
            } catch (e: Exception) {
                null
            }
        }
        return bundledInfo
    }

    /** Last version the watch reported with HELLO, or null (never, or an old watch build). */
    val watchVersion: Int? get() = prefs.watchVersion.takeIf { it >= 0 }

    fun clearWatchVersion() {
        prefs.watchVersion = -1
        RemoteState.poke()
    }

    /** Forgets everything known about the previous watch (a new pairing, or an unpair): offer, report, versions. */
    @Synchronized
    fun forgetWatch() {
        stopLocked()
        prefs.watchVersion = -1
        prefs.watchUpdatedTo = -1
        integrity = null
        verifyAskedAt = 0L
        RemoteState.poke()
    }

    /**
     * Starts the server and makes the offer available. [force] = install even if the watch already runs this version
     * (Reinstall). Blocking (hashes the APK once); call off the main thread.
     */
    @Synchronized
    fun start(force: Boolean = false): StartResult = startLocked(force, intentAt = null)

    private fun startLocked(force: Boolean, intentAt: Long?): StartResult {
        stopLocked()
        if (intentAt == null) { prefs.watchUpdatedTo = -1; integrity = null }
        val info = bundled() ?: return StartResult.NO_APK
        val data = try {
            payload ?: hashBundled().also { payload = it }
        } catch (e: IOException) {
            return StartResult.NO_APK
        }
        var addresses = UpdateAddresses.collect()
        // Without a network the update can still go over Bluetooth. The offer format needs an address, so it carries one that
        // fails fast; the Wi-Fi fallback then ends in the watch's usual "couldn't reach the tablet".
        if (addresses.isEmpty() && prefs.updateOverBluetooth) addresses = listOf(NO_NETWORK_ADDRESS)
        if (addresses.isEmpty()) return StartResult.NO_ADDRESS
        val token = ByteArray(TOKEN_SIZE).also { random.nextBytes(it) }
        lateinit var server: UpdateServer
        server = UpdateServer(
            path = UpdateOffer.pathFor(token),
            length = data.size,
            open = { context.assets.open(APK_ASSET) },
            listener = object : UpdateServer.Listener {
                override fun onFinished(reason: UpdateServer.Reason) {
                    session?.takeIf { it.server === server }?.ended = reason
                    RemoteState.poke()
                }
            },
        )
        val port = server.start()
        if (port < 0) return StartResult.FAILED
        val now = System.currentTimeMillis()
        session = Session(
            server, port, token, data.sha256, data.size, info.versionCode, addresses, now, now + UpdateServer.DEFAULT_TIMEOUT_MS,
            force, intentAt ?: now,
        )
        RemoteState.poke()
        RemoteService.instance?.notifyOffer()
        return StartResult.STARTED
    }

    @Synchronized
    fun stop() = stopLocked()

    private fun stopLocked() {
        session?.server?.stop()
        session = null
        RemoteState.poke()
    }

    /** The offer to hand to an authenticated watch, or null when no update is being offered right now. */
    fun currentOffer(): UpdateOffer? {
        val s = refreshForRetry(session ?: return null)
        if (s.ended != null || !s.server.isRunning) return null
        val age = ((System.currentTimeMillis() - s.issuedAt) / 1000).toInt().coerceIn(0, 0xFFFF)
        return UpdateOffer(s.versionCode, s.port, s.token, s.sha256, age, s.addresses, s.force, bluetooth = prefs.updateOverBluetooth)
    }

    /**
     * The APK for a Bluetooth window request, only while this exact token is the live offer: the transfer is bound to the offer,
     * ends with it (done, cancelled, timed out, replaced by a new token) and is off when the setting says Wi-Fi only.
     */
    private fun dataSource(token: ByteArray): UpdateDataServer.Source? {
        if (!prefs.updateOverBluetooth) return null
        val s = session ?: return null
        if (s.ended != null || !s.server.isRunning || !MessageDigest.isEqual(s.token, token)) return null
        return UpdateDataServer.Source(s.token, s.size.toInt()) { context.assets.open(APK_ASSET) }
    }

    /** A window up to [end] was fully sent to the watch for the offer with [token]. */
    fun onDataServed(token: ByteArray, end: Int) {
        val s = session?.takeIf { MessageDigest.isEqual(it.token, token) } ?: return
        if (end > s.btServed) s.btServed = end.toLong()
        RemoteState.poke()
    }

    /**
     * The one-shot server is finished after one complete download, so a watch that got the whole file but then had to
     * retry (its hash check failed) would find a spent token. It says so with a RETRYING report; then, inside the 10
     * minute window of the user's request, a fresh server and token are started for it. Any other state is left alone,
     * so a failed update never loops by itself.
     */
    @Synchronized
    private fun refreshForRetry(s: Session): Session {
        if (!needsFreshToken(s.ended, s.report, System.currentTimeMillis() - s.intentAt)) return s
        return if (startLocked(s.force, s.intentAt) == StartResult.STARTED) session ?: s else s
    }

    /** "Verify watch app" on the tablet: asks the watch for an installed-binary report (it answers on its next GET_OFFER). */
    fun requestVerify() {
        verifyAskedAt = System.currentTimeMillis()
        RemoteState.poke()
        RemoteService.instance?.notifyOffer() // a subscribed watch reads GET_OFFER now and sees the flag
    }

    /** Whether the OFFER frame should carry the "send me your installed-binary report" flag. */
    val verifyRequested: Boolean get() = verifyAskedAt != 0L

    /** The watch's installed-binary report, after the VERIFY frame authenticated. Called on the GATT thread. */
    fun onIntegrity(report: IntegrityReport) {
        val expected = expectedBuild()
        integrity = if (expected == null) null else IntegrityResult(WatchIntegrity.assess(expected, report), report.versionCode)
        prefs.watchVersion = report.versionCode
        verifyAskedAt = 0L
        RemoteState.poke()
    }

    /** What the embedded watch APK should look like on the watch; null when this build bundles none. */
    private fun expectedBuild(): ExpectedBuild? {
        val info = bundled() ?: return null
        val data = try {
            synchronized(this) { payload ?: hashBundled().also { payload = it } }
        } catch (e: IOException) {
            return null
        }
        return ExpectedBuild(info.versionCode, data.sha256, info.certSha256)
    }

    /** HELLO. Called on the GATT thread after the frame authenticated. */
    fun onHello(versionCode: Int) {
        // A higher version than the one we knew: the watch app was replaced (by us, or by hand). "Updated to x".
        val before = prefs.watchVersion
        if (before in 0 until versionCode) prefs.watchUpdatedTo = versionCode
        prefs.watchVersion = versionCode
        if (integrity?.reportedCode != versionCode) integrity = null // a report about another build says nothing now
        val s = session
        if (s != null && s.ended == UpdateServer.Reason.COMPLETED && versionCode >= s.versionCode) session = null
        RemoteState.poke()
    }

    /** UPDATE_STATE. Called on the GATT thread after the frame authenticated. */
    fun onReport(report: UpdateReport) {
        val s = session
        s?.report = report
        // Over Bluetooth nothing tells the one-shot HTTP server the download is done: the watch moving on to verifying or installing
        // is the signal. Same end state as an HTTP download that completed (a hash retry then gets a fresh token).
        if (s != null && s.ended == null && s.btServed >= s.size &&
            (report.phase == UpdateReport.Phase.VERIFYING || report.phase == UpdateReport.Phase.INSTALLING)
        ) {
            s.server.stop()
            s.ended = UpdateServer.Reason.COMPLETED
        }
        RemoteState.poke()
    }

    fun snapshot(): UpdateSnapshot? {
        val s = session ?: return null
        return UpdateSnapshot(s.ended, maxOf(s.server.bytesServed, s.btServed), s.size, s.deadline, s.report)
    }

    private fun hashBundled(): Payload {
        val md = MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.assets.open(APK_ASSET).use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
                size += n
            }
        }
        return Payload(size, md.digest())
    }

    companion object {
        const val APK_ASSET = "watch-update.apk"
        const val PROPS_ASSET = "watch-update.properties"
        private const val TOKEN_SIZE = 16
        private const val NO_NETWORK_ADDRESS = "0.0.0.0"

        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: UpdateHost? = null

        fun get(context: Context): UpdateHost =
            instance ?: synchronized(this) { instance ?: UpdateHost(context).also { instance = it } }

        /** Pure: the finished one-shot server must be replaced when the watch reports a retry within the user's window. */
        fun needsFreshToken(ended: UpdateServer.Reason?, report: UpdateReport?, sinceIntentMs: Long): Boolean =
            ended == UpdateServer.Reason.COMPLETED && report?.phase == UpdateReport.Phase.RETRYING &&
                sinceIntentMs < UpdateServer.DEFAULT_TIMEOUT_MS

        /** Pure mapping from the session facts to what the screen shows. */
        fun stateOf(s: UpdateSnapshot?, now: Long): WatchUpdateState {
            if (s == null) return WatchUpdateState.Idle
            val r = s.report
            if (r != null && r.phase == UpdateReport.Phase.FAILED) {
                return WatchUpdateState.Failed(UpdateReport.Failure.fromId(r.value))
            }
            if (r != null && r.phase == UpdateReport.Phase.RETRYING && s.ended != UpdateServer.Reason.TIMEOUT && s.ended != UpdateServer.Reason.STOPPED) {
                return WatchUpdateState.Retrying(r.value)
            }
            return when (s.ended) {
                UpdateServer.Reason.TIMEOUT -> WatchUpdateState.TimedOut
                UpdateServer.Reason.STOPPED -> WatchUpdateState.Idle
                UpdateServer.Reason.COMPLETED -> WatchUpdateState.Installing
                null -> when {
                    r != null && (r.phase == UpdateReport.Phase.INSTALLING || r.phase == UpdateReport.Phase.VERIFYING) ->
                        WatchUpdateState.Installing
                    s.bytesServed > 0 || r?.phase == UpdateReport.Phase.DOWNLOADING -> {
                        val pct = if (s.total > 0) (s.bytesServed * 100 / s.total).toInt().coerceIn(0, 100) else 0
                        WatchUpdateState.Downloading(pct)
                    }
                    else -> WatchUpdateState.Waiting(maxOf(0L, (s.deadline - now) / 1000).toInt())
                }
            }
        }
    }
}
