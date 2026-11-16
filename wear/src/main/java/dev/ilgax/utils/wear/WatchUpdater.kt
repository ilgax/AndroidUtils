package dev.ilgax.utils.wear

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.ilgax.utils.protocol.Command
import dev.ilgax.utils.protocol.OfferPolicy
import dev.ilgax.utils.protocol.RetryPolicy
import dev.ilgax.utils.protocol.StaleSessions
import dev.ilgax.utils.protocol.TransferPolicy
import dev.ilgax.utils.protocol.UpdateDataFrame
import dev.ilgax.utils.protocol.ResumePlan
import dev.ilgax.utils.protocol.WindowAssembler
import dev.ilgax.utils.protocol.UpdateFetch
import dev.ilgax.utils.protocol.UpdateFlow
import dev.ilgax.utils.protocol.UpdateOffer
import dev.ilgax.utils.protocol.UpdateReport
import dev.ilgax.utils.protocol.WatchIntegrity
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** What the app shows about a watch app update. */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    /** An authenticated offer is waiting for the user's tap. */
    class Available(val offer: UpdateOffer) : UpdateUi
    data class Downloading(
        val version: Int,
        val percent: Int,
        /** True while the APK comes over the Bluetooth link. */
        val bluetooth: Boolean = false,
        /** Set when Bluetooth was skipped or given up on and Wi-Fi is used instead; the UI says why. */
        val fallback: TransferPolicy.FallbackReason? = null,
    ) : UpdateUi
    /** A download attempt failed; [attempt] is the one that just failed, the next one starts after a short backoff. */
    class Retrying(val attempt: Int) : UpdateUi
    class Installing(val version: Int) : UpdateUi
    /** The system wants a tap on its install dialog; it is launched from the visible app, or from a notification. */
    class Confirm(val version: Int) : UpdateUi
    /** "Install unknown apps" is not allowed for this app; show the exact adb fix. */
    data object NeedsInstallPermission : UpdateUi
    class Failed(val failure: UpdateReport.Failure, val detail: String? = null) : UpdateUi
}

/**
 * Downloads an offered APK and installs it with PackageInstaller. Bluetooth first when the tablet offers it (windows over the
 * authenticated GATT link, resumed from the last good offset after a drop), Wi-Fi when it does not or when Bluetooth fails or stalls.
 *
 * An offer is only ever created by tapping "Update watch app" (or "Reinstall") on the tablet, so it is the user's request.
 * What to do with one is decided by [OfferPolicy]: a fresh offer starts by itself while Tab Remote is visible, an offer that
 * arrives while the app is closed becomes the "Tab Remote update ready" notification, an older one waits for a tap. Nothing is
 * installed unless the APK's SHA-256 equals the hash in the HMAC-authenticated offer and its signing certificate equals the
 * running app's.
 */
class WatchUpdater private constructor(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = WatchPrefs.get(this.context)

    @Volatile var state: UpdateUi = UpdateUi.Idle
        private set
    @Volatile var listener: ((UpdateUi) -> Unit)? = null
    /** True between MainActivity's onStart and onStop. */
    @Volatile var visible = false
    @Volatile private var pendingConfirm: Intent? = null
    private var lastReportedPercent = -1

    // Guarded by this.
    private var flow = UpdateFlow.State.IDLE
    private var flowSince = 0L
    private var lastTriedKey: String? = null
    private var lastNotifiedKey: String? = null
    private var userRequestedAt = 0L
    private var lastBackgroundCheck = 0L
    private val verifying = AtomicBoolean()

    init {
        // Any successful command while the app is closed (tile, quick launch) is a chance to notice an offer.
        RemoteClient.get(this.context).onContact = { backgroundCheck() }
    }

    val isBusy: Boolean get() = synchronized(this) { UpdateFlow.isBusy(flow, flowSince, SystemClock.elapsedRealtime()) }

    private fun transition(e: UpdateFlow.Event) = synchronized(this) {
        flow = UpdateFlow.next(flow, e)
        flowSince = SystemClock.elapsedRealtime()
    }

    /** The user tapped the "update ready" notification: the next offer starts whatever its age. */
    fun requestStart() = synchronized(this) { userRequestedAt = SystemClock.elapsedRealtime() }

    /** An authenticated offer was read from the tablet. */
    fun onOffer(offer: UpdateOffer) {
        val installed = versionCode(context)
        val action = synchronized(this) {
            OfferPolicy.decide(
                busy = UpdateFlow.isBusy(flow, flowSince, SystemClock.elapsedRealtime()),
                installedVersion = installed,
                offer = offer,
                visible = visible,
                userRequested = userRequestedAt != 0L && SystemClock.elapsedRealtime() - userRequestedAt < USER_REQUEST_MS,
                alreadyTried = offer.key() == lastTriedKey,
            )
        }
        when (action) {
            OfferPolicy.Action.START -> start(offer)
            OfferPolicy.Action.SHOW -> show(offer)
            OfferPolicy.Action.NOTIFY -> {
                val first = synchronized(this) { (offer.key() != lastNotifiedKey).also { lastNotifiedKey = offer.key() } }
                show(offer)
                if (first) UpdateNotifier.ready(context)
            }
            else -> Unit
        }
    }

    /** The tablet answered "no offer": drop a waiting "Update to vX" and the notification. */
    fun onNoOffer() {
        if (state is UpdateUi.Available) {
            UpdateNotifier.clear(context)
            dismiss()
        }
    }

    private fun show(offer: UpdateOffer) {
        transition(UpdateFlow.Event.OFFER)
        set(UpdateUi.Available(offer))
    }

    fun start(offer: UpdateOffer) {
        synchronized(this) {
            if (UpdateFlow.isBusy(flow, flowSince, SystemClock.elapsedRealtime())) return
            flow = UpdateFlow.next(flow, UpdateFlow.Event.START)
            flowSince = SystemClock.elapsedRealtime()
            lastTriedKey = offer.key()
            userRequestedAt = 0L
        }
        UpdateNotifier.clear(context)
        lastReportedPercent = -1
        set(UpdateUi.Downloading(offer.versionCode, 0))
        Thread({ run(offer) }, "tabremote-update").start()
    }

    fun dismiss() {
        if (!isBusy) {
            transition(UpdateFlow.Event.DISMISS)
            set(UpdateUi.Idle)
        }
    }

    /** The system's install confirmation, if one is waiting. Launch it from a visible activity. */
    fun takePendingConfirm(): Intent? = synchronized(this) { pendingConfirm.also { pendingConfirm = null } }

    /** Puts a confirmation back after launching it failed, and falls back to the notification. */
    fun launchFailed(intent: Intent) {
        synchronized(this) { pendingConfirm = intent }
        UpdateNotifier.confirm(context, intent)
    }

    // region flow

    private fun run(first: UpdateOffer) {
        val file = File(context.cacheDir, "update.apk")
        try {
            if (!context.packageManager.canRequestPackageInstalls()) return fail(UpdateReport.Failure.INSTALL_BLOCKED)
            report(UpdateReport(UpdateReport.Phase.DOWNLOADING, 0))
            var offer = first
            var attempt = 0
            var viaWifi = true
            val start = TransferPolicy.initial(first)
            if (start is TransferPolicy.Start.Bluetooth) {
                when (val bt = downloadBluetooth(first, file)) {
                    BtOutcome.Ok -> viaWifi = false
                    is BtOutcome.Fallback -> useWifi(first.versionCode, bt.reason)
                }
            } else if (start is TransferPolicy.Start.Wifi) {
                useWifi(first.versionCode, start.reason)
            }
            while (viaWifi) {
                attempt++
                val failure = downloadOnce(offer, file) ?: break
                when (val decision = RetryPolicy.decide(attempt, failure)) {
                    is RetryPolicy.Decision.GiveUp -> return fail(decision.failure)
                    is RetryPolicy.Decision.Retry -> {
                        report(UpdateReport(UpdateReport.Phase.RETRYING, attempt))
                        set(UpdateUi.Retrying(attempt))
                        Thread.sleep(decision.delayMs)
                        // A spent token cannot be reused: ask the tablet for a fresh offer (new token) first.
                        if (decision.freshOffer) freshOffer(offer)?.let { offer = it }
                        lastReportedPercent = -1
                        set((state as? UpdateUi.Downloading)?.copy(percent = 0) ?: UpdateUi.Downloading(offer.versionCode, 0))
                    }
                }
            }
            // Either transport only gets here with the hash matched. Now the signer: only our own key may replace us.
            report(UpdateReport(UpdateReport.Phase.VERIFYING, 100))
            val own = InstalledBinary.ownCert(context)
            val downloaded = InstalledBinary.archiveCert(context, file, context.packageName)
            if (!WatchIntegrity.sameKey(own, downloaded)) return fail(UpdateReport.Failure.CERT_MISMATCH)
            install(offer.versionCode, file)
        } catch (e: Exception) {
            fail(UpdateReport.Failure.UNKNOWN, e.message)
        } finally {
            file.delete()
        }
    }

    private sealed interface BtOutcome {
        data object Ok : BtOutcome
        class Fallback(val reason: TransferPolicy.FallbackReason) : BtOutcome
    }

    /** Wi-Fi is used from here on; the UI says why (when Bluetooth was wanted), and progress starts again. */
    private fun useWifi(version: Int, reason: TransferPolicy.FallbackReason) {
        lastReportedPercent = -1
        set(UpdateUi.Downloading(version, 0, bluetooth = false, fallback = reason))
    }

    /**
     * Pulls the APK in windows over the GATT link into [file]. Each window is appended as it arrives, so after any failure the file
     * length is the offset to resume from; reconnecting is the connection layer's job and the next window just asks again. The
     * [TransferPolicy] decides retry or fallback. Returns Ok only for a complete file whose SHA-256 equals the offer's.
     */
    private fun downloadBluetooth(offer: UpdateOffer, file: File): BtOutcome {
        val client = RemoteClient.get(context)
        val tracker = TransferPolicy.Tracker(SystemClock.elapsedRealtime())
        file.delete()
        set(UpdateUi.Downloading(offer.versionCode, 0, bluetooth = true))
        client.setHighThroughput(true)
        try {
            var total = -1
            while (true) {
                val length = file.length()
                val have = ResumePlan.resumeFrom(length, total)
                if (have.toLong() != length) RandomAccessFile(file, "rw").use { it.setLength(have.toLong()) }
                if (total in 0..have) break
                val assembler = WindowAssembler(have, UpdateDataFrame.WINDOW_MAX, total)
                val cancelled = AtomicBoolean()
                val latch = CountDownLatch(1)
                val result = AtomicReference<Result>()
                FileOutputStream(file, true).use { out ->
                    client.pullUpdate(
                        offer.token, have, UpdateDataFrame.WINDOW_MAX, cancelled::get,
                        onFrame = { frame ->
                            try {
                                when (assembler.accept(frame) { b, from, n -> out.write(b, from, n) }) {
                                    WindowAssembler.Verdict.ACCEPTED, WindowAssembler.Verdict.DUPLICATE -> {
                                        if (assembler.total > 0) progress((assembler.next * 100L / assembler.total).toInt())
                                        if (assembler.complete) RemoteClient.FrameAction.DONE else RemoteClient.FrameAction.CONTINUE
                                    }
                                    else -> RemoteClient.FrameAction.ABORT
                                }
                            } catch (e: IOException) {
                                RemoteClient.FrameAction.ABORT // the file was closed under a window that timed out
                            }
                        },
                    ) { r -> result.set(r); latch.countDown() }
                    if (!latch.await(WINDOW_WAIT_MS, TimeUnit.MILLISECONDS)) {
                        cancelled.set(true)
                        result.set(Result.TIMEOUT)
                    }
                    out.flush()
                }
                if (assembler.total >= 0) total = assembler.total
                val now = SystemClock.elapsedRealtime()
                if (assembler.next > have) tracker.onProgress(now)
                val r = result.get()
                if (r == Result.SUCCESS && assembler.complete) continue
                val failure = when {
                    r == Result.UNKNOWN_COMMAND -> TransferPolicy.WindowFailure.NO_CHANNEL
                    r == Result.MTU_TOO_SMALL -> TransferPolicy.WindowFailure.MTU_TOO_SMALL
                    r == Result.UPDATE_BUSY -> TransferPolicy.WindowFailure.BUSY
                    r == Result.AUTH_FAILED || r == Result.UPDATE_GONE || r == Result.RATE_LIMITED -> TransferPolicy.WindowFailure.REJECTED
                    assembler.failure == WindowAssembler.Verdict.GAP -> TransferPolicy.WindowFailure.GAP
                    assembler.failure == WindowAssembler.Verdict.INVALID -> TransferPolicy.WindowFailure.BAD_FRAME
                    r == Result.FAILED -> TransferPolicy.WindowFailure.REJECTED // the tablet answered FAILED to the request itself
                    r == Result.TIMEOUT -> TransferPolicy.WindowFailure.TIMEOUT
                    else -> TransferPolicy.WindowFailure.LINK_LOST
                }
                when (val d = tracker.onWindowFailed(failure, now)) {
                    is TransferPolicy.Decision.Fallback -> { file.delete(); return BtOutcome.Fallback(d.reason) }
                    is TransferPolicy.Decision.Retry -> Thread.sleep(d.delayMs)
                }
            }
            report(UpdateReport(UpdateReport.Phase.DOWNLOADING, 100))
            if (!java.security.MessageDigest.isEqual(UpdateFetch.sha256(file), offer.sha256)) {
                file.delete()
                return BtOutcome.Fallback(TransferPolicy.FallbackReason.CORRUPT)
            }
            return BtOutcome.Ok
        } finally {
            client.setHighThroughput(false)
        }
    }

    /** One download attempt over its own Wi-Fi network request, released on every path. Null = verified APK in [file]. */
    private fun downloadOnce(offer: UpdateOffer, file: File): RetryPolicy.AttemptFailure? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val latch = CountDownLatch(1)
        val wifi = AtomicReference<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { wifi.set(network); latch.countDown() }
            override fun onUnavailable() { latch.countDown() }
        }
        var gone = false
        try {
            // Wear routes the default network through the phone/Bluetooth proxy; ask for the Wi-Fi radio itself.
            try {
                cm.requestNetwork(request, callback, WIFI_TIMEOUT_MS.toInt())
            } catch (e: SecurityException) {
                return RetryPolicy.AttemptFailure.NO_WIFI
            }
            latch.await(WIFI_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS)
            val network = wifi.get() ?: return RetryPolicy.AttemptFailure.NO_WIFI
            return when (UpdateFetch.fetch(offer, { url -> open(network, url) { gone = true } }, file, onProgress = ::progress)) {
                is UpdateFetch.Outcome.Ok -> null
                UpdateFetch.Outcome.HashMismatch -> RetryPolicy.AttemptFailure.HASH_MISMATCH
                is UpdateFetch.Outcome.Unreachable -> if (gone) RetryPolicy.AttemptFailure.GONE else RetryPolicy.AttemptFailure.UNREACHABLE
            }
        } finally {
            try { cm.unregisterNetworkCallback(callback) } catch (_: IllegalArgumentException) {}
        }
    }

    /** Asks the tablet for its current offer again; null if there is none (or it did not answer in time). */
    private fun freshOffer(old: UpdateOffer): UpdateOffer? {
        val found = AtomicReference<UpdateOffer?>()
        val latch = CountDownLatch(1)
        RemoteClient.get(context).checkForUpdate(versionCode(context)) { check ->
            if (check is UpdateCheck.Offer && check.offer.versionCode == old.versionCode) found.set(check.offer)
            latch.countDown()
        }
        latch.await(FRESH_WAIT_MS, TimeUnit.MILLISECONDS)
        return found.get()
    }

    private fun open(network: Network, url: String, onGone: () -> Unit): UpdateFetch.Body {
        val c = network.openConnection(URL(url)) as HttpURLConnection
        try {
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) onGone()
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val stream = object : FilterInputStream(c.inputStream) {
                override fun close() {
                    try { super.close() } finally { c.disconnect() }
                }
            }
            return UpdateFetch.Body(stream, c.contentLengthLong)
        } catch (e: IOException) {
            c.disconnect()
            throw e
        }
    }

    private fun progress(percent: Int) {
        val d = state as? UpdateUi.Downloading ?: return
        if (percent / PROGRESS_STEP == lastReportedPercent / PROGRESS_STEP && percent < 100) return
        lastReportedPercent = percent
        set(d.copy(percent = percent))
        if (percent % 25 == 0 || percent >= 100) report(UpdateReport(UpdateReport.Phase.DOWNLOADING, percent))
    }

    private fun install(version: Int, apk: File) {
        transition(UpdateFlow.Event.INSTALL_BEGIN)
        set(UpdateUi.Installing(version))
        // The new APK replaces this process, so give the INSTALLING report a moment to reach the tablet first.
        val sent = CountDownLatch(1)
        RemoteClient.get(context).send(Command.UPDATE_STATE, UpdateReport(UpdateReport.Phase.INSTALLING, 0).encode()) { sent.countDown() }
        sent.await(SEND_WAIT_MS, TimeUnit.MILLISECONDS)

        val installer = context.packageManager.packageInstaller
        abandonStale(installer)
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName) // can only ever replace this app
            // Silent only when this app is already the installer of record and holds UPDATE_PACKAGES_WITHOUT_USER_ACTION.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            // Becomes the update owner, so later updates from this app stay silent (API 34+).
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        var id = -1
        try {
            id = installer.createSession(params)
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("tabremote.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val result = PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the system fills in the status extras
                )
                session.commit(result.intentSender)
            }
        } catch (e: Exception) {
            if (id >= 0) try { installer.abandonSession(id) } catch (_: Exception) {}
            fail(UpdateReport.Failure.INSTALL_FAILED, e.message)
        }
    }

    /** Sessions left behind by earlier failed or abandoned attempts hold storage and can confuse the result broadcast. */
    private fun abandonStale(installer: PackageInstaller) {
        val stale = try {
            StaleSessions.abandonable(
                installer.mySessions.map { StaleSessions.Ref(it.sessionId, it.appPackageName) }, context.packageName,
            )
        } catch (e: Exception) {
            emptyList()
        }
        for (id in stale) try { installer.abandonSession(id) } catch (_: Exception) {}
    }

    // endregion

    // region results (from InstallResultReceiver)

    fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) return fail(UpdateReport.Failure.INSTALL_FAILED, message)
                transition(UpdateFlow.Event.NEEDS_CONFIRM)
                synchronized(this) { pendingConfirm = confirm }
                set(UpdateUi.Confirm((state as? UpdateUi.Installing)?.version ?: 0))
                // Visible: MainActivity launches it from the state change. Not visible, or not taken up in time: a notification.
                if (!visible) UpdateNotifier.confirm(context, confirm)
                else main.postDelayed({
                    val waiting = synchronized(this) { pendingConfirm }
                    if (waiting != null) UpdateNotifier.confirm(context, waiting)
                }, CONFIRM_FALLBACK_MS)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                transition(UpdateFlow.Event.SUCCESS)
                synchronized(this) { pendingConfirm = null }
                UpdateNotifier.clear(context)
                set(UpdateUi.Idle) // normally unreachable: the update restarts this process
            }
            PackageInstaller.STATUS_FAILURE_BLOCKED -> fail(UpdateReport.Failure.INSTALL_BLOCKED, message)
            else -> fail(UpdateReport.Failure.INSTALL_FAILED, message)
        }
    }

    private fun fail(reason: UpdateReport.Failure, detail: String? = null) {
        transition(UpdateFlow.Event.FAIL)
        synchronized(this) { pendingConfirm = null }
        UpdateNotifier.clear(context)
        report(UpdateReport.failed(reason))
        set(if (reason == UpdateReport.Failure.INSTALL_BLOCKED) UpdateUi.NeedsInstallPermission else UpdateUi.Failed(reason, detail))
    }

    // endregion

    // region background and verification

    /** After a successful command while the app is closed: read the offer, and answer a verification request. */
    private fun backgroundCheck() {
        val client = RemoteClient.get(context)
        val now = SystemClock.elapsedRealtime()
        val go = synchronized(this) {
            val ok = !visible && client.isPaired &&
                !UpdateFlow.isBusy(flow, flowSince, now) && (lastBackgroundCheck == 0L || now - lastBackgroundCheck > BACKGROUND_MIN_MS)
            if (ok) lastBackgroundCheck = now
            ok
        }
        if (!go) return
        client.checkForUpdate(versionCode(context)) { check -> handleCheck(check) }
    }

    /** Shared by the visible app and the background check. */
    fun handleCheck(check: UpdateCheck) {
        when (check) {
            is UpdateCheck.Offer -> onOffer(check.offer)
            is UpdateCheck.None -> onNoOffer()
            else -> Unit
        }
        if (check.verifyRequested) verifyAndReport()
    }

    /** Hashes the installed APK(s) and the signing certificate off the main thread and sends them to the tablet. */
    fun verifyAndReport(done: (VerifyOutcome) -> Unit = {}) {
        if (!verifying.compareAndSet(false, true)) return
        Thread({
            val report = InstalledBinary.report(context)
            if (report == null) {
                verifying.set(false)
                main.post { done(VerifyOutcome.UNREADABLE) }
                return@Thread
            }
            RemoteClient.get(context).reportIntegrity(report) { r ->
                verifying.set(false)
                val outcome = when (r) {
                    Result.SUCCESS -> VerifyOutcome.SENT.also { prefs.verifiedVersion = report.versionCode }
                    Result.UNKNOWN_COMMAND -> VerifyOutcome.TABLET_TOO_OLD
                    else -> VerifyOutcome.NOT_SENT
                }
                done(outcome)
            }
        }, "tabremote-verify").start()
    }

    /** First start after an update (or install): tell the tablet what is actually running. */
    fun verifyIfNewVersion() {
        if (prefs.verifiedVersion != versionCode(context)) verifyAndReport()
    }

    enum class VerifyOutcome { SENT, NOT_SENT, TABLET_TOO_OLD, UNREADABLE }

    /** "v0.4.2" and the installer line, shown at the end of the list. Resolved once (it costs binder calls). */
    val footer: String by lazy {
        val name = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { null }
        (if (name != null) context.getString(R.string.app_version, name) + "\n" else "") + installerInfo()
    }

    /** "installer: x · owner: y": who installed this app and who may update it silently. For the diagnostics line. */
    private fun installerInfo(): String = try {
        val si = context.packageManager.getInstallSourceInfo(context.packageName)
        val owner = if (Build.VERSION.SDK_INT >= 34) si.updateOwnerPackageName else null
        "installer: ${si.installingPackageName ?: "none"} · owner: ${owner ?: "none"}"
    } catch (e: Exception) {
        "installer: unknown"
    }

    // endregion

    private fun report(r: UpdateReport) = RemoteClient.get(context).reportUpdate(r)

    private fun set(s: UpdateUi) {
        state = s
        main.post { listener?.invoke(s) }
    }

    companion object {
        /** The adb command that allows this app to install packages; shown verbatim on the watch. */
        const val ADB_FIX = "adb shell appops set dev.ilgax.utils.wear REQUEST_INSTALL_PACKAGES allow"
        private const val WIFI_TIMEOUT_MS = 20_000L
        /** Upper bound for one window including queueing behind other jobs; the link's own stall timers fire much earlier. */
        private const val WINDOW_WAIT_MS = 90_000L
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val SEND_WAIT_MS = 4_000L
        private const val FRESH_WAIT_MS = 25_000L
        private const val PROGRESS_STEP = 5
        private const val USER_REQUEST_MS = 2 * 60_000L
        private const val CONFIRM_FALLBACK_MS = 2_000L
        private const val BACKGROUND_MIN_MS = 60_000L

        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: WatchUpdater? = null

        fun get(context: Context): WatchUpdater =
            instance ?: synchronized(this) { instance ?: WatchUpdater(context).also { instance = it } }

        @Volatile private var cachedVersion = 0

        /** The installed versionCode; it cannot change while the process lives (an update restarts it). */
        fun versionCode(context: Context): Int {
            if (cachedVersion == 0) {
                cachedVersion = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
            }
            return cachedVersion
        }
    }
}
