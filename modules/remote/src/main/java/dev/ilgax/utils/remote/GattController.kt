package dev.ilgax.utils.remote

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import dev.ilgax.utils.protocol.AppSlots
import dev.ilgax.utils.protocol.Command
import dev.ilgax.utils.protocol.CommandFrame
import dev.ilgax.utils.protocol.MacroList
import dev.ilgax.utils.protocol.NowArt
import dev.ilgax.utils.protocol.NowPlayingFrame
import dev.ilgax.utils.remote.macros.MacroEngine
import dev.ilgax.utils.protocol.IntegrityFrame
import dev.ilgax.utils.protocol.InfoFrame
import dev.ilgax.utils.protocol.OfferFrame
import dev.ilgax.utils.protocol.Protocol
import dev.ilgax.utils.protocol.SpeakerCodec
import dev.ilgax.utils.protocol.Status
import dev.ilgax.utils.protocol.UpdateDataFrame
import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * GATT server. Every callback is re-posted onto a single worker thread, so all state below is
 * confined to that thread.
 */
class GattController(
    private val context: Context,
    private val prefs: RemotePrefs,
    private val vault: KeyVault,
    private val executor: CommandExecutor,
    private val pairing: PairingSession,
    private val onReady: () -> Unit,
) {
    private val thread = HandlerThread("tabremote-gatt").apply { start() }
    private val handler = Handler(thread.looper)
    private val random = SecureRandom()
    private var server: BluetoothGattServer? = null

    private val devices = HashMap<String, BluetoothDevice>()
    private val nonces = HashMap<String, ByteArray>()
    private val mtus = HashMap<String, Int>()
    private val pairBuffers = HashMap<String, ByteArrayOutputStream>()
    private val appsGrants = HashSet<String>()
    private val appsSnapshots = HashMap<String, ByteArray>()
    /** GET_OFFER grants: one authenticated read of OFFER, bound to the nonce of the granting frame. */
    private val offerGrants = HashMap<String, ByteArray>()
    private val offerSnapshots = HashMap<String, ByteArray>()
    private val offerSubscribers = HashSet<String>()
    /** SYNC_MACROS grants: one authenticated read of MACROS, bound to the granting frame's nonce and the page it asked for. */
    private class MacroGrant(val nonce: ByteArray, val page: Int)
    private val macroGrants = HashMap<String, MacroGrant>()
    private val macroSnapshots = HashMap<String, ByteArray>()
    /** GET_NOW_PLAYING grants: one authenticated read of NOW_PLAYING, bound to the granting frame's nonce. */
    private val nowGrants = HashMap<String, ByteArray>()
    private val nowSnapshots = HashMap<String, ByteArray>()
    /** GET_ART grants: one authenticated read of NOW_ART for the page asked, bound to the granting frame's nonce. */
    private val artGrants = HashMap<String, MacroGrant>()
    private val artSnapshots = HashMap<String, ByteArray>()
    /** GET_INFO grants: one authenticated read of INFO, bound to the granting frame's nonce. */
    private val infoGrants = HashMap<String, ByteArray>()
    private val infoSnapshots = HashMap<String, ByteArray>()
    private var nowChar: BluetoothGattCharacteristic? = null
    /** Watches subscribed to NOW_PLAYING pings; the media sessions are only watched while there is one. */
    private val nowSubscribers = HashSet<String>()
    private var offerChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    /** Watches subscribed to UPDATE_DATA notifications; a window is only served to one of them. */
    private val dataSubscribers = HashSet<String>()
    /** The frames of the window being sent to a device, one notification at a time (the stack takes the next after onNotificationSent). */
    private class DataWindow(val token: ByteArray, val frames: ArrayDeque<ByteArray>, val end: Int, var retries: Int = 0)
    private val dataWindows = HashMap<String, DataWindow>()
    private val dataInFlight = HashSet<String>()
    private val failures = HashMap<String, Int>()
    private val lockedUntil = HashMap<String, Long>()

    fun open(manager: BluetoothManager): Boolean {
        close()
        return try {
            val s = manager.openGattServer(context, callback) ?: return false
            server = s
            s.addService(buildService())
        } catch (e: SecurityException) {
            false
        }
    }

    fun close() {
        val s = server ?: return
        server = null
        try {
            s.close()
        } catch (_: SecurityException) {
        }
        handler.post {
            devices.clear(); nonces.clear(); mtus.clear(); pairBuffers.clear()
            appsGrants.clear(); appsSnapshots.clear()
            offerGrants.clear(); offerSnapshots.clear(); offerSubscribers.clear()
            macroGrants.clear(); macroSnapshots.clear()
            nowGrants.clear(); nowSnapshots.clear(); artGrants.clear(); artSnapshots.clear(); nowSubscribers.clear()
            infoGrants.clear(); infoSnapshots.clear()
            updateNowListening()
            dataSubscribers.clear(); dataWindows.clear(); dataInFlight.clear()
        }
    }

    /** Content-free ping to watches subscribed to NOW_PLAYING: "the state changed, ask for it". The state itself is never notified. */
    fun notifyNowPlaying() {
        handler.post {
            val s = server ?: return@post
            val ch = nowChar ?: return@post
            for (addr in nowSubscribers.toList()) {
                val device = devices[addr] ?: continue
                try {
                    s.notifyCharacteristicChanged(device, ch, false, byteArrayOf(Protocol.PING_NOW_PLAYING))
                } catch (_: SecurityException) {
                }
            }
        }
    }

    /** The media sessions are watched exactly while some watch is subscribed. */
    private fun updateNowListening() {
        val monitor = NowPlayingMonitor.get(context)
        monitor.onChange = ::notifyNowPlaying
        monitor.setListening(nowSubscribers.isNotEmpty())
    }

    private fun subscribersFor(uuid: java.util.UUID?): HashSet<String> = when (uuid) {
        Protocol.UPDATE_DATA_UUID -> dataSubscribers
        Protocol.NOW_PLAYING_UUID -> nowSubscribers
        else -> offerSubscribers
    }

    /** Content-free ping to subscribed watches: "ask for the offer". The offer itself is never notified. */
    fun notifyOffer() {
        handler.post {
            val s = server ?: return@post
            val ch = offerChar ?: return@post
            for (addr in offerSubscribers.toList()) {
                val device = devices[addr] ?: continue
                try {
                    s.notifyCharacteristicChanged(device, ch, false, byteArrayOf(Protocol.PING_OFFER))
                } catch (_: SecurityException) {
                }
            }
        }
    }

    /** Content-free ping on the same channel: "the macro list changed". Old watches just treat it as an offer check. */
    fun notifyMacros() {
        handler.post {
            val s = server ?: return@post
            val ch = offerChar ?: return@post
            for (addr in offerSubscribers.toList()) {
                val device = devices[addr] ?: continue
                try {
                    s.notifyCharacteristicChanged(device, ch, false, byteArrayOf(Protocol.PING_MACROS))
                } catch (_: SecurityException) {
                }
            }
        }
    }

    fun shutdown() {
        close()
        thread.quitSafely()
    }

    private fun buildService(): BluetoothGattService {
        val svc = BluetoothGattService(Protocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val read = BluetoothGattCharacteristic.PERMISSION_READ
        val write = BluetoothGattCharacteristic.PERMISSION_WRITE
        svc.addCharacteristic(
            BluetoothGattCharacteristic(Protocol.NONCE_UUID, BluetoothGattCharacteristic.PROPERTY_READ, read),
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(Protocol.COMMAND_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, write),
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(
                Protocol.PAIR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                read or write,
            ),
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(Protocol.APPS_UUID, BluetoothGattCharacteristic.PROPERTY_READ, read),
        )
        val offer = BluetoothGattCharacteristic(
            Protocol.OFFER_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            read,
        )
        offer.addDescriptor(
            BluetoothGattDescriptor(Protocol.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        svc.addCharacteristic(offer)
        offerChar = offer
        svc.addCharacteristic(
            BluetoothGattCharacteristic(Protocol.MACROS_UUID, BluetoothGattCharacteristic.PROPERTY_READ, read),
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(Protocol.VERIFY_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, write),
        )
        val data = BluetoothGattCharacteristic(
            Protocol.UPDATE_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            write,
        )
        data.addDescriptor(
            BluetoothGattDescriptor(Protocol.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        svc.addCharacteristic(data)
        dataChar = data
        val now = BluetoothGattCharacteristic(
            Protocol.NOW_PLAYING_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            read,
        )
        now.addDescriptor(
            BluetoothGattDescriptor(Protocol.CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE),
        )
        svc.addCharacteristic(now)
        nowChar = now
        svc.addCharacteristic(
            BluetoothGattCharacteristic(Protocol.NOW_ART_UUID, BluetoothGattCharacteristic.PROPERTY_READ, read),
        )
        svc.addCharacteristic(
            BluetoothGattCharacteristic(Protocol.INFO_UUID, BluetoothGattCharacteristic.PROPERTY_READ, read),
        )
        return svc
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status == BluetoothGatt.GATT_SUCCESS) onReady()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                val addr = device.address
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    devices[addr] = device
                    nonces[addr] = newNonce()
                } else {
                    devices.remove(addr); nonces.remove(addr); mtus.remove(addr)
                    pairBuffers.remove(addr); appsGrants.remove(addr); appsSnapshots.remove(addr)
                    offerGrants.remove(addr); offerSnapshots.remove(addr); offerSubscribers.remove(addr)
                    macroGrants.remove(addr); macroSnapshots.remove(addr)
                    nowGrants.remove(addr); nowSnapshots.remove(addr); artGrants.remove(addr); artSnapshots.remove(addr)
                    infoGrants.remove(addr); infoSnapshots.remove(addr)
                    if (nowSubscribers.remove(addr)) updateNowListening()
                    dataSubscribers.remove(addr); dataWindows.remove(addr); dataInFlight.remove(addr)
                    UpdateHost.get(context).data.release(addr)
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) onReady() // re-assert advertising
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post { mtus[device.address] = mtu }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            handler.post { handleRead(device, requestId, offset, characteristic.uuid) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val data = value?.copyOf() ?: ByteArray(0)
            handler.post { handleWrite(device, requestId, characteristic.uuid, preparedWrite, responseNeeded, offset, data) }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            handler.post {
                val subscribers = subscribersFor(descriptor.characteristic?.uuid)
                val on = descriptor.uuid == Protocol.CCCD_UUID && device.address in subscribers
                if (descriptor.uuid != Protocol.CCCD_UUID) {
                    respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                } else {
                    val v = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    respondSlice(device, requestId, offset, v)
                }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val data = value?.copyOf() ?: ByteArray(0)
            handler.post {
                val ok = descriptor.uuid == Protocol.CCCD_UUID && !preparedWrite && offset == 0 && data.size == 2
                if (ok) {
                    val subscribers = subscribersFor(descriptor.characteristic?.uuid)
                    if (data.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribers.add(device.address)
                    else subscribers.remove(device.address)
                    if (subscribers === nowSubscribers) updateNowListening()
                }
                if (responseNeeded) {
                    respond(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post { onDataSent(device.address, status) }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            handler.post {
                val buf = pairBuffers.remove(device.address)
                val status = if (execute && buf != null) {
                    pairing.onWatchKey(device.address, buf.toByteArray(), ::isConnected)
                } else {
                    Status.SUCCESS
                }
                respond(device, requestId, status.code, 0, null)
            }
        }
    }

    private fun isConnected(address: String) = devices.containsKey(address)

    private fun handleRead(device: BluetoothDevice, requestId: Int, offset: Int, uuid: java.util.UUID) {
        val addr = device.address
        when (uuid) {
            Protocol.NONCE_UUID -> {
                val nonce = nonces.getOrPut(addr) { newNonce() }
                respondSlice(device, requestId, offset, nonce)
            }
            Protocol.PAIR_UUID -> {
                val pub = pairing.tabletPublicKey()
                if (pub == null) respond(device, requestId, Status.PAIRING_REJECTED.code, offset, null)
                else respondSlice(device, requestId, offset, pub)
            }
            Protocol.APPS_UUID -> {
                val payload: ByteArray? = if (offset == 0) {
                    if (appsGrants.remove(addr)) {
                        AppSlots.encode(AppCatalog.slotLabels(context, prefs)).also { appsSnapshots[addr] = it }
                    } else null
                } else appsSnapshots[addr]
                if (payload == null) {
                    respond(device, requestId, Status.AUTH_FAILED.code, offset, null)
                    return
                }
                respondSlice(device, requestId, offset, payload)
                // The stack truncates each response to MTU-1 bytes; drop the snapshot once the end is reached.
                val chunk = (mtus[addr] ?: DEFAULT_MTU) - 1
                if (offset + chunk >= payload.size) appsSnapshots.remove(addr)
            }
            Protocol.OFFER_UUID -> {
                // Only after an authenticated GET_OFFER; the tag binds the payload to that frame's nonce.
                val payload: ByteArray? = if (offset == 0) {
                    offerGrants.remove(addr)?.let { nonce ->
                        val mac: (ByteArray) -> ByteArray = { vault.mac(it) ?: throw IllegalStateException("no key") }
                        try {
                            UpdateHost.get(context).let { OfferFrame.encode(mac, nonce, it.currentOffer(), it.verifyRequested) }
                        } catch (e: Exception) {
                            null
                        }
                    }?.also { offerSnapshots[addr] = it }
                } else offerSnapshots[addr]
                if (payload == null) {
                    respond(device, requestId, Status.AUTH_FAILED.code, offset, null)
                    return
                }
                respondSlice(device, requestId, offset, payload)
                val chunk = (mtus[addr] ?: DEFAULT_MTU) - 1
                if (offset + chunk >= payload.size) offerSnapshots.remove(addr)
            }
            Protocol.MACROS_UUID -> {
                // Only after an authenticated SYNC_MACROS; the tag binds the page to that frame's nonce.
                val payload: ByteArray? = if (offset == 0) {
                    macroGrants.remove(addr)?.let { grant ->
                        val mac: (ByteArray) -> ByteArray = { vault.mac(it) ?: throw IllegalStateException("no key") }
                        try {
                            val (version, items) = MacroEngine.get(context).store.watchView()
                            MacroList.encode(mac, grant.nonce, version, items, grant.page)
                        } catch (e: Exception) {
                            null
                        }
                    }?.also { macroSnapshots[addr] = it }
                } else macroSnapshots[addr]
                if (payload == null) {
                    respond(device, requestId, Status.AUTH_FAILED.code, offset, null)
                    return
                }
                respondSlice(device, requestId, offset, payload)
                val chunk = (mtus[addr] ?: DEFAULT_MTU) - 1
                if (offset + chunk >= payload.size) macroSnapshots.remove(addr)
            }
            Protocol.NOW_PLAYING_UUID -> readGranted(device, requestId, offset, nowSnapshots) {
                nowGrants.remove(addr)?.let { nonce ->
                    try {
                        NowPlayingFrame.encode(mac(), nonce, NowPlayingMonitor.get(context).readForWatch())
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            Protocol.NOW_ART_UUID -> readGranted(device, requestId, offset, artSnapshots) {
                artGrants.remove(addr)?.let { grant ->
                    try {
                        NowArt.encodePage(mac(), grant.nonce, NowPlayingMonitor.get(context).art(), grant.page)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            Protocol.INFO_UUID -> readGranted(device, requestId, offset, infoSnapshots) {
                infoGrants.remove(addr)?.let { nonce ->
                    try {
                        InfoFrame.encode(mac(), nonce, HostIdentity.read(context))
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            else -> respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
        }
    }

    private fun mac(): (ByteArray) -> ByteArray = { vault.mac(it) ?: throw IllegalStateException("no key") }

    /**
     * Serves a long read of a payload that exists only after an authenticated grant: the first read ([build] consumes the grant)
     * builds it and keeps a snapshot for the follow-up reads of the same payload; without a grant the read fails.
     */
    private fun readGranted(
        device: BluetoothDevice, requestId: Int, offset: Int, snapshots: HashMap<String, ByteArray>, build: () -> ByteArray?,
    ) {
        val addr = device.address
        val payload: ByteArray? = if (offset == 0) build()?.also { snapshots[addr] = it } else snapshots[addr]
        if (payload == null) {
            respond(device, requestId, Status.AUTH_FAILED.code, offset, null)
            return
        }
        respondSlice(device, requestId, offset, payload)
        val chunk = (mtus[addr] ?: DEFAULT_MTU) - 1
        if (offset + chunk >= payload.size) snapshots.remove(addr)
    }

    private fun respondSlice(device: BluetoothDevice, requestId: Int, offset: Int, full: ByteArray) {
        if (offset > full.size) {
            respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
        } else {
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, full.copyOfRange(offset, full.size))
        }
    }

    private fun handleWrite(
        device: BluetoothDevice, requestId: Int, uuid: java.util.UUID,
        prepared: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
    ) {
        val addr = device.address
        val reply = { status: Int, echo: ByteArray? -> if (responseNeeded) respond(device, requestId, status, offset, echo) }
        when (uuid) {
            Protocol.COMMAND_UUID -> {
                if (prepared || offset != 0) {
                    reply(Status.FAILED.code, null)
                    return
                }
                reply(processCommand(addr, value).code, null)
            }
            Protocol.VERIFY_UUID -> {
                // One authenticated write, bound to the nonce like a command; it has to fit one ATT write (needs the MTU).
                if (prepared || offset != 0) {
                    reply(Status.FAILED.code, null)
                    return
                }
                reply(processIntegrity(addr, value).code, null)
            }
            Protocol.UPDATE_DATA_UUID -> {
                // One authenticated window request per write, bound to the nonce; the window follows as notifications.
                if (prepared || offset != 0) {
                    reply(Status.FAILED.code, null)
                    return
                }
                val status = processUpdateData(device, value)
                reply(status.code, null)
                if (status == Status.SUCCESS) handler.post { pumpData(addr) } // after the write response
            }
            Protocol.PAIR_UUID -> {
                if (prepared) {
                    val buf = pairBuffers.getOrPut(addr) { ByteArrayOutputStream() }
                    // Prepared writes arrive in order; reject gaps rather than guess.
                    if (offset != buf.size() || buf.size() + value.size > PAIR_MAX) {
                        pairBuffers.remove(addr)
                        reply(BluetoothGatt.GATT_INVALID_OFFSET, null)
                    } else {
                        buf.write(value)
                        reply(BluetoothGatt.GATT_SUCCESS, value)
                    }
                } else {
                    reply(if (offset != 0) BluetoothGatt.GATT_INVALID_OFFSET
                    else pairing.onWatchKey(addr, value, ::isConnected).code, null)
                }
            }
            else -> reply(BluetoothGatt.GATT_WRITE_NOT_PERMITTED, null)
        }
    }

    private fun processCommand(addr: String, frame: ByteArray): Status {
        val now = System.currentTimeMillis()
        if ((lockedUntil[addr] ?: 0L) > now) return Status.RATE_LIMITED
        val nonce = nonces.getOrPut(addr) { newNonce() }
        val status = try {
            verifyAndRun(addr, nonce, frame)
        } catch (e: Exception) {
            Status.FAILED
        }
        // One nonce per attempt, whatever the outcome.
        nonces[addr] = newNonce()
        if (status == Status.AUTH_FAILED) {
            val n = (failures[addr] ?: 0) + 1
            if (n >= MAX_FAILURES) {
                failures.remove(addr)
                lockedUntil[addr] = now + LOCKOUT_MS
            } else failures[addr] = n
        } else if (status == Status.SUCCESS || SpeakerCodec.decodeReply(status) != null) {
            failures.remove(addr)
        }
        return status
    }

    private fun processIntegrity(addr: String, frame: ByteArray): Status {
        val now = System.currentTimeMillis()
        if ((lockedUntil[addr] ?: 0L) > now) return Status.RATE_LIMITED
        val nonce = nonces.getOrPut(addr) { newNonce() }
        val status = try {
            if (!vault.has()) {
                Status.AUTH_FAILED
            } else {
                val mac: (ByteArray) -> ByteArray = { vault.mac(it) ?: throw IllegalStateException("no key") }
                val report = IntegrityFrame.verify(mac, nonce, frame)
                if (report == null) Status.AUTH_FAILED else { UpdateHost.get(context).onIntegrity(report); Status.SUCCESS }
            }
        } catch (e: Exception) {
            Status.FAILED
        }
        nonces[addr] = newNonce() // one nonce per attempt, whatever the outcome
        if (status == Status.AUTH_FAILED) {
            val n = (failures[addr] ?: 0) + 1
            if (n >= MAX_FAILURES) {
                failures.remove(addr)
                lockedUntil[addr] = now + LOCKOUT_MS
            } else failures[addr] = n
        } else if (status == Status.SUCCESS) {
            failures.remove(addr)
        }
        return status
    }

    private fun processUpdateData(device: BluetoothDevice, frame: ByteArray): Status {
        val addr = device.address
        val now = System.currentTimeMillis()
        if ((lockedUntil[addr] ?: 0L) > now) return Status.RATE_LIMITED
        val nonce = nonces.getOrPut(addr) { newNonce() }
        val status = try {
            if (!vault.has()) {
                Status.AUTH_FAILED
            } else if (addr !in dataSubscribers) {
                Status.FAILED
            } else {
                val mac: (ByteArray) -> ByteArray = { vault.mac(it) ?: throw IllegalStateException("no key") }
                val host = UpdateHost.get(context)
                when (val r = host.data.handle(addr, mac, nonce, mtus[addr] ?: DEFAULT_MTU, frame)) {
                    is UpdateDataServer.Result.Rejected -> r.status
                    is UpdateDataServer.Result.Window -> {
                        // A new request replaces whatever was still queued for this device (the watch gave up on it).
                        dataInFlight.remove(addr)
                        dataWindows[addr] = DataWindow(frame.copyOfRange(0, UpdateDataFrame.TOKEN_SIZE), ArrayDeque(r.frames), r.end)
                        Status.SUCCESS
                    }
                }
            }
        } catch (e: Exception) {
            Status.FAILED
        }
        nonces[addr] = newNonce() // one nonce per attempt, whatever the outcome
        if (status == Status.AUTH_FAILED) {
            val n = (failures[addr] ?: 0) + 1
            if (n >= MAX_FAILURES) {
                failures.remove(addr)
                lockedUntil[addr] = now + LOCKOUT_MS
            } else failures[addr] = n
        } else if (status == Status.SUCCESS) {
            failures.remove(addr)
        }
        return status
    }

    /** Sends the next frame of [addr]'s window, or finishes it. Runs on the worker thread between other callbacks, so commands never wait for a whole window. */
    private fun pumpData(addr: String) {
        val w = dataWindows[addr] ?: return
        if (addr in dataInFlight) return
        val frame = w.frames.firstOrNull()
        if (frame == null) {
            dataWindows.remove(addr)
            UpdateHost.get(context).data.release(addr)
            UpdateHost.get(context).onDataServed(w.token, w.end)
            return
        }
        val s = server
        val ch = dataChar
        val device = devices[addr]
        if (s == null || ch == null || device == null) return abortData(addr)
        val sent = try {
            s.notifyCharacteristicChanged(device, ch, false, frame) == BluetoothStatusCodes.SUCCESS
        } catch (e: SecurityException) {
            false
        }
        if (sent) {
            w.frames.removeFirst()
            w.retries = 0
            dataInFlight.add(addr)
        } else if (++w.retries > DATA_BUSY_RETRIES) {
            abortData(addr)
        } else {
            handler.postDelayed({ pumpData(addr) }, DATA_BUSY_DELAY_MS) // the stack is busy with another notification
        }
    }

    private fun onDataSent(addr: String, status: Int) {
        if (!dataInFlight.remove(addr)) return
        if (status != BluetoothGatt.GATT_SUCCESS) abortData(addr) else pumpData(addr)
    }

    /** Drops the rest of the window; the watch times out and asks again from its last good offset. */
    private fun abortData(addr: String) {
        dataWindows.remove(addr)
        dataInFlight.remove(addr)
        UpdateHost.get(context).data.release(addr)
    }

    private fun verifyAndRun(addr: String, nonce: ByteArray, frame: ByteArray): Status {
        pairing.tryCommit(nonce, frame) { key ->
            vault.save(key)
            prefs.paired = true
            // A new pairing replaces the old one and may be a different watch (or app): drop its update state.
            UpdateHost.get(context).forgetWatch()
        }?.let { return it }

        if (!vault.has()) return Status.AUTH_FAILED
        val mac: (ByteArray) -> ByteArray = { vault.mac(it) ?: throw IllegalStateException("no key") }
        return when (val r = CommandFrame.verify(mac, nonce, frame)) {
            is CommandFrame.Result.Invalid -> r.status
            is CommandFrame.Result.Valid -> {
                val status = executor.execute(r.command, r.args)
                if (r.command == Command.SYNC_APPS && status == Status.SUCCESS) appsGrants.add(addr)
                if (r.command == Command.GET_OFFER && status == Status.SUCCESS) offerGrants[addr] = nonce
                if (r.command == Command.SYNC_MACROS && status == Status.SUCCESS) {
                    MacroList.decodePageArgs(r.args)?.let { macroGrants[addr] = MacroGrant(nonce, it) }
                }
                if (r.command == Command.GET_NOW_PLAYING && status == Status.SUCCESS) nowGrants[addr] = nonce
                if (r.command == Command.GET_INFO && status == Status.SUCCESS) infoGrants[addr] = nonce
                if (r.command == Command.GET_ART && status == Status.SUCCESS) {
                    NowArt.decodePageArgs(r.args)?.let { artGrants[addr] = MacroGrant(nonce, it) }
                }
                status
            }
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try {
            server?.sendResponse(device, requestId, status, offset, value)
        } catch (_: SecurityException) {
        }
    }

    private fun newNonce() = ByteArray(Protocol.NONCE_SIZE).also { random.nextBytes(it) }

    private companion object {
        const val DEFAULT_MTU = 23
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 30_000L
        const val PAIR_MAX = 128
        const val DATA_BUSY_RETRIES = 50
        const val DATA_BUSY_DELAY_MS = 10L
    }
}
