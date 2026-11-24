package dev.ilgax.utils.wear

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import dev.ilgax.utils.protocol.Advertisement
import dev.ilgax.utils.protocol.BondedDevice
import dev.ilgax.utils.protocol.HostBook
import dev.ilgax.utils.protocol.LinkPolicy
import dev.ilgax.utils.protocol.Protocol
import dev.ilgax.utils.protocol.Reach
import dev.ilgax.utils.protocol.Reachability
import dev.ilgax.utils.protocol.Resolution
import dev.ilgax.utils.protocol.ScanPolicy
import dev.ilgax.utils.protocol.TargetSelection
import dev.ilgax.utils.protocol.TargetState
import java.util.concurrent.ConcurrentHashMap

/**
 * Which paired hosts can be heard right now. Listens only while the app is visible or the tile is in view, never in the background:
 * a 1.5 s low-latency burst when the app opens or the tile comes into view (skipped while the last result is a few seconds old), then,
 * only while the app stays visible and the screen is on, a sustained BALANCED scan (SCAN_MODE_LOW_POWER would listen 0.5 s of every
 * 5 s and could miss a once-a-second beacon for longer than the 5 s age-out). The scan is hardware-filtered to the paired hosts'
 * beacons, so no other advertiser wakes the watch. The decisions are in :protocol ([Reachability], [TargetSelection]).
 */
class Presence private constructor(context: Context) {
    private val context = context.applicationContext
    private val prefs = WatchPrefs.get(this.context)
    private val thread = HandlerThread("tabremote-presence").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val reach = Reachability()
    private val bluetooth by lazy { this.context.getSystemService(BluetoothManager::class.java) }

    /** The device behind a recently heard beacon: the next connect to that host can skip scanning while its address is valid. */
    class Seen(val device: BluetoothDevice, val at: Long)
    private val seen = ConcurrentHashMap<String, Seen>()

    /** The host with a live GATT link counts as heard whatever its beacon does (a connected peripheral may stop advertising). */
    @Volatile private var pinned: String? = null

    fun pin(id: String?) {
        if (pinned == id) return
        pinned = id
        handler.post { publish() }
    }

    @Volatile private var lastBurstEnd: Long? = null
    @Volatile private var bursting = false
    @Volatile private var published: List<String> = emptyList()

    /** Called on the main thread when the heard set changed or a burst finished. */
    @Volatile var onChange: (() -> Unit)? = null

    // Client thread only (handler).
    private var callback: ScanCallback? = null
    private var sustainedWanted = false
    private var paused = false
    private val afterBurst = ArrayList<() -> Unit>()

    /** True from the first open until the first burst has finished: the UI says "Searching" instead of "No device in range". */
    val searching: Boolean get() = bursting || lastBurstEnd == null

    fun heard(): List<String> {
        val ids = prefs.hosts.ids
        return reach.heard(SystemClock.elapsedRealtime(), pinned).filter { it in ids }
    }

    fun resolution(): Resolution = TargetSelection.resolve(prefs.selectedHost, heard(), prefs.hosts.ids)

    fun tileState(): TargetState = TargetSelection.tile(prefs.selectedHost, heard(), prefs.hosts.ids)

    /** The host commands go to, or null (nothing heard, or the picked host is silent). */
    fun targetId(): String? = resolution().state.targetId

    fun device(id: String): BluetoothDevice? {
        val s = seen[id] ?: return null
        return if (LinkPolicy.cachedDeviceUsable(SystemClock.elapsedRealtime() - s.at)) s.device else null
    }

    fun note(id: String, device: BluetoothDevice) {
        seen[id] = Seen(device, SystemClock.elapsedRealtime())
    }

    fun forget(id: String) {
        seen.remove(id)
    }

    /** The app became visible ([sustained]) or the tile came into view (burst only). */
    fun open(sustained: Boolean) = handler.post {
        if (sustained) sustainedWanted = true
        paused = false
        val now = SystemClock.elapsedRealtime()
        when {
            bursting -> Unit
            Reach.needsBurst(lastBurstEnd, now) -> startBurst()
            sustainedWanted && callback == null -> startSustained()
        }
        publish() // the cached result is shown at once
    }

    /** Runs [done] on the main thread once a burst has produced a fresh result (at once while the cache is fresh). */
    fun burstOnce(done: () -> Unit) {
        handler.post {
            val now = SystemClock.elapsedRealtime()
            if (!bursting && !Reach.needsBurst(lastBurstEnd, now)) {
                main.post(done)
                return@post
            }
            afterBurst += done
            if (!bursting) startBurst()
        }
    }

    /** "Scan again": a burst now, whatever the cache says. */
    fun rescan() = handler.post {
        if (bursting) return@post
        lastBurstEnd = null
        paused = false
        startBurst()
        publish()
    }

    /** The app left the screen: stop listening. A burst in flight finishes first (the tile may be waiting for it). */
    fun close() = handler.post {
        sustainedWanted = false
        if (!bursting) stopScan()
    }

    /** Screen off / ambient while visible: let go of the radio. */
    fun pause() = handler.post {
        paused = true
        if (!bursting) stopScan()
    }

    fun resume() = open(sustained = true)

    /** Bluetooth went off, or the paired set changed: forget what was heard / rebuild the scan filters. */
    fun reset() = handler.post {
        reach.clear(); seen.clear(); published = emptyList(); lastBurstEnd = null
        stopScan(); bursting = false
        main.post { onChange?.invoke() }
    }

    fun filtersChanged() = handler.post {
        if (callback != null) {
            val wasBurst = bursting
            stopScan()
            if (wasBurst) startBurst() else if (sustainedWanted && !paused) startSustained()
        }
    }

    /** The watch's bonded phone-class devices come from the classic side; read on demand (a binder call). */
    fun bonded(): List<BondedDevice> = try {
        bluetooth?.adapter?.bondedDevices.orEmpty().map { BondedDevice(it.name, it.bluetoothClass?.majorDeviceClass ?: -1) }
    } catch (_: SecurityException) {
        emptyList()
    }

    // region scanning (handler thread)

    private fun startBurst() {
        bursting = true
        if (!startScan(ScanSettings.SCAN_MODE_LOW_LATENCY)) return endBurst()
        handler.postDelayed(burstEnd, Reach.BURST_MS)
    }

    private fun endBurst() {
        if (!bursting) return
        handler.removeCallbacks(burstEnd)
        stopScan()
        bursting = false
        lastBurstEnd = SystemClock.elapsedRealtime()
        if (sustainedWanted && !paused) startSustained()
        val pending = afterBurst.toList().also { afterBurst.clear() }
        main.post { pending.forEach { it() } }
        publish(force = true)
    }

    private val burstEnd = Runnable { endBurst() }

    private fun startSustained() {
        if (!startScan(ScanSettings.SCAN_MODE_BALANCED)) return
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
    }

    /** While scanning, hosts age out even though nothing arrives to say so: look at the set once a second. */
    private val tick = object : Runnable {
        override fun run() {
            if (callback == null || bursting) return
            publish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan(mode: Int): Boolean {
        stopScan()
        if (!Permissions.bluetoothGranted(context)) return false
        val scanner = try {
            bluetooth?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        } catch (_: SecurityException) {
            null
        } ?: return false
        val ids = prefs.hosts.entries.mapNotNull { HostBook.idBytes(it.id) }
        if (ids.isEmpty()) return false
        val paired = prefs.hosts.ids
        val filters = ScanPolicy.filters(ids).map { mf ->
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID))
                .setManufacturerData(Protocol.COMPANY_ID, mf.data, mf.mask)
                .build()
        }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post {
                    if (callback !== this) return@post
                    val adv = Advertisement.decode(result.scanRecord?.getManufacturerSpecificData(Protocol.COMPANY_ID)) ?: return@post
                    val id = ScanPolicy.match(adv, paired) ?: return@post
                    val now = SystemClock.elapsedRealtime()
                    seen[id] = Seen(result.device, now)
                    if (reach.hear(id, now)) publish()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post {
                    if (callback !== this) return@post
                    callback = null
                    // Too many scan starts in a short time, or a controller hiccup: keep what we have, end a burst now.
                    if (bursting) endBurst()
                }
            }
        }
        callback = cb
        return try {
            scanner.startScan(filters, ScanSettings.Builder().setScanMode(mode).build(), cb)
            true
        } catch (_: SecurityException) {
            callback = null
            false
        } catch (_: IllegalStateException) {
            callback = null
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler.removeCallbacks(tick)
        val cb = callback ?: return
        callback = null
        try {
            bluetooth?.adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun publish(force: Boolean = false) {
        val now = heard()
        if (!force && now == published) return
        published = now
        main.post { onChange?.invoke() }
    }

    // endregion

    companion object {
        private const val TICK_MS = 1_000L

        @SuppressLint("StaticFieldLeak") // application context only
        @Volatile private var instance: Presence? = null

        fun get(context: Context): Presence =
            instance ?: synchronized(this) { instance ?: Presence(context).also { instance = it } }
    }
}
