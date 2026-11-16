package dev.ilgax.utils.speaker

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.InvocationTargetException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** All audio logic. Hidden APIs are reached by reflection through a Shizuku-wrapped IAudioService binder. */
object SpeakerController {
    private const val TAG = "SpeakerKill"
    private const val PREFS = "speaker_kill_switch"
    const val K_ENABLED = "enabled"
    const val K_PAUSE = "pause_on_disconnect"
    private const val K_LOG = "event_log"
    private const val K_PENDING = "pending_release"
    private const val STREAM_MUSIC = AudioManager.STREAM_MUSIC
    private const val TYPE_SPEAKER = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    private const val TYPE_SPEAKER_SAFE = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE

    private val exec = Executors.newSingleThreadExecutor()
    private val logLines = ArrayDeque<String>()
    private val errors = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _statusFlow = MutableStateFlow("idle")
    val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()
    private val _enabledFlow = MutableStateFlow(false)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()
    var statusLine: String
        get() = _statusFlow.value
        private set(v) { _statusFlow.value = v }
    @Volatile private var lastPauseMs = 0L
    @Volatile private var blockStatus: Map<Int, String> = emptyMap()

    private var prefsRef: SharedPreferences? = null
    internal fun prefsOf(ctx: Context): SharedPreferences = prefs(ctx)
    private fun prefs(ctx: Context): SharedPreferences =
        prefsRef ?: ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also {
            prefsRef = it
            _enabledFlow.value = it.getBoolean(K_ENABLED, false)
            synchronized(logLines) {
                it.getString(K_LOG, "")?.split('\n')?.filter { l -> l.isNotEmpty() }?.forEach { l -> logLines.add(l) }
            }
        }

    // ---------- logging ----------

    fun log(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        val p = prefs(ctx)
        synchronized(logLines) {
            logLines.add("${timeFmt.format(Date())} $msg")
            while (logLines.size > 80) logLines.removeFirst()
            p.edit().putString(K_LOG, logLines.joinToString("\n")).apply()
        }
    }

    fun error(ctx: Context, what: String, t: Throwable?) {
        val root = if (t is InvocationTargetException) t.targetException ?: t else t
        val msg = "$what: ${root?.javaClass?.simpleName ?: ""} ${root?.message ?: ""}".trim()
        Log.e(TAG, msg, root)
        synchronized(logLines) {
            errors.add("${timeFmt.format(Date())} $msg")
            while (errors.size > 20) errors.removeFirst()
        }
        log(ctx, "ERR $msg")
    }

    // ---------- settings ----------

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(K_ENABLED, false)
    fun pauseOnDisconnect(ctx: Context) = prefs(ctx).getBoolean(K_PAUSE, true)
    fun setPauseOnDisconnect(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(K_PAUSE, v).apply()

    fun setEnabled(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        prefs(app).edit().putBoolean(K_ENABLED, on).apply()
        _enabledFlow.value = on
        if (!on) statusLine = "OFF"
        log(app, "switch -> ${if (on) "ON" else "OFF"}")
        if (on) {
            try {
                ContextCompat.startForegroundService(app, Intent(app, SpeakerService::class.java))
            } catch (t: Throwable) {
                error(app, "startForegroundService", t)
                enforce(app, "switch on (no service)", true)
            }
        } else {
            app.stopService(Intent(app, SpeakerService::class.java))
            exec.execute { doRelease(app) }
        }
    }

    // ---------- Shizuku ----------

    fun shizukuState(ctx: Context): ShizukuStatus = ShizukuState.current(ctx)

    @Volatile private var hidden: Hidden? = null
    @Volatile private var hiddenFailure: String? = null

    /** Called when the Shizuku binder dies so the wrapped service is rebuilt next time. */
    fun invalidateBinder() { hidden = null }

    internal fun hiddenOrNull(ctx: Context): Hidden? = if (shizukuState(ctx) == ShizukuStatus.Ready) hidden(ctx) else null

    internal fun runOnExec(block: () -> Unit) { exec.execute { try { block() } catch (t: Throwable) { Log.e(TAG, "exec task", t) } } }

    private fun hidden(ctx: Context): Hidden? {
        hidden?.let { return it }
        return try {
            val h = Hidden()
            hidden = h
            hiddenFailure = null
            h
        } catch (t: Throwable) {
            val m = "bind hidden APIs: ${(t as? InvocationTargetException)?.targetException?.message ?: t.message}"
            if (m != hiddenFailure) { hiddenFailure = m; error(ctx, "bind hidden APIs", t) }
            null
        }
    }

    // ---------- device helpers ----------

    private fun speakerDevices(am: AudioManager): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type == TYPE_SPEAKER || it.type == TYPE_SPEAKER_SAFE }

    /** (type, address) pairs to block; the plain speaker is always included even if not enumerated. */
    private fun speakerTargets(am: AudioManager): List<Pair<Int, String>> {
        val l = speakerDevices(am).map { it.type to (it.address ?: "") }.toMutableList()
        if (l.none { it.first == TYPE_SPEAKER }) l.add(TYPE_SPEAKER to "")
        return l.distinct()
    }

    private fun mediaDevices(am: AudioManager): List<AudioDeviceInfo> = try {
        am.getAudioDevicesForAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        )
    } catch (t: Throwable) { emptyList() }

    private fun speakerActive(am: AudioManager) =
        mediaDevices(am).any { it.type == TYPE_SPEAKER || it.type == TYPE_SPEAKER_SAFE }

    private fun isBluetooth(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_BLE_SPEAKER || type == AudioDeviceInfo.TYPE_BLE_BROADCAST ||
        type == AudioDeviceInfo.TYPE_HEARING_AID

    fun isBluetoothDevice(d: AudioDeviceInfo) = isBluetooth(d.type)

    internal fun isSpeakerType(type: Int) = type == TYPE_SPEAKER || type == TYPE_SPEAKER_SAFE

    private fun externalOutputConnected(ctx: Context, am: AudioManager) = ExperimentPlan.externalOutput(
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type },
        prefs(ctx).getBoolean(SpeakerExperiments.K_WIRED_LIVE, false),
    )

    // ---------- backends over the real system ----------

    /** Volumes of the built-in speaker through the hidden audio service when there is one, otherwise the public API. */
    private class SystemVolumes(val ctx: Context, val am: AudioManager, val h: Hidden?) : VolumeBackend {
        private fun primary(): Any =
            h!!.ada(TYPE_SPEAKER, speakerDevices(am).firstOrNull { it.type == TYPE_SPEAKER }?.address ?: "")

        override fun minIndex(stream: Int) = am.getStreamMinVolume(stream)
        override fun speakerIndex(stream: Int) = h!!.getDeviceVolume(stream, primary())
        override fun setSpeakerIndex(stream: Int, index: Int) {
            h!!.setDeviceVolume(stream, primary(), index)
            // SPEAKER_SAFE, where the device has one, is best effort: the plain speaker is the one that counts.
            for ((type, addr) in speakerTargets(am)) {
                if (type == TYPE_SPEAKER) continue
                try { h.setDeviceVolume(stream, h.ada(type, addr), index) } catch (t: Throwable) {
                    error(ctx, "setDeviceVolume(type=$type,${SpeakerStreams.name(stream)})", t)
                }
            }
        }

        override fun routeIndex(stream: Int) = am.getStreamVolume(stream)
        override fun setRouteIndex(stream: Int, index: Int) = am.setStreamVolume(stream, index, 0)
        override fun settingsIndex(stream: Int): Int? {
            val key = when (stream) {
                AudioManager.STREAM_MUSIC -> "volume_music"
                AudioManager.STREAM_NOTIFICATION -> "volume_notification"
                AudioManager.STREAM_RING -> "volume_ring"
                AudioManager.STREAM_SYSTEM -> "volume_system"
                AudioManager.STREAM_ALARM -> "volume_alarm"
                AudioManager.STREAM_DTMF -> "volume_dtmf"
                AudioManager.STREAM_ACCESSIBILITY -> "volume_accessibility"
                else -> return null
            }
            return Settings.System.getInt(ctx.contentResolver, "${key}_speaker", -1).takeIf { it >= 0 }
        }

        override var ringerMode: Int
            get() = am.ringerMode
            set(v) {
                if (h != null) { try { h.setRingerMode(v); return } catch (t: Throwable) { error(ctx, "setRingerModeExternal", t) } }
                am.ringerMode = v
            }
    }

    private class SystemAppOps(val h: Hidden, val pm: PackageManager) : AppOpsBackend {
        override fun packagesForUid(uid: Int): List<String> = h.packagesForUid(uid) ?: pm.getPackagesForUid(uid)?.toList() ?: emptyList()
        override fun mode(uid: Int, pkg: String) = h.opMode(uid, pkg)
        override fun setMode(uid: Int, pkg: String, mode: Int) = h.setOpMode(uid, pkg, mode)
    }

    private fun keeper(ctx: Context, am: AudioManager, h: Hidden?) =
        SpeakerVolumeKeeper(PrefsStore(prefs(ctx)), SystemVolumes(ctx, am, h), { log(ctx, it) }, { what, t -> error(ctx, what, t) })

    private fun muter(ctx: Context) = AppMuter(PrefsStore(prefs(ctx)), Process.myUid(), (hidden?.opCode) ?: AppMuter.OP_PLAY_AUDIO)

    @Volatile private var lastRoutes: List<PlayerRoute> = emptyList()
    @Volatile private var lastScan: SpeakerScan? = null
    @Volatile private var lastStreams: List<SpeakerVolumeKeeper.StreamState> = emptyList()

    // ---------- enforce / release ----------

    /** Idempotent. [full] also (re)applies the routing block; volume-only events pass false. */
    fun enforce(ctx: Context, reason: String, full: Boolean) {
        val app = ctx.applicationContext
        exec.execute {
            try { doEnforce(app, reason, full) } catch (t: Throwable) { error(app, "enforce($reason)", t) }
        }
    }

    /** The audio system reported a change in who is playing: mute or release apps, without touching volumes. */
    fun rescanPlayers(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        exec.execute {
            try { doRescan(app, reason) } catch (t: Throwable) { error(app, "rescan($reason)", t) }
        }
    }

    /** The service is going away (switch OFF, or killed): let go of the experiments' hard blocks. */
    fun onServiceDestroyed(ctx: Context) {
        val app = ctx.applicationContext
        runOnExec { SpeakerExperiments.disengageAll(app, hiddenOrNull(app)) }
    }

    private fun doEnforce(ctx: Context, reason: String, full: Boolean) {
        if (!isEnabled(ctx)) return
        val am = ctx.getSystemService(AudioManager::class.java)
        val state = shizukuState(ctx)
        val h = if (state == ShizukuStatus.Ready) hidden(ctx) else null
        val notes = ArrayList<String>()
        if (state != ShizukuStatus.Ready) notes.add("Shizuku: ${state.diagName} (volume-only mode)")
        else if (h == null) notes.add("hidden API bind failed")

        if (h != null && (full || blockStatus.isEmpty())) {
            val res = LinkedHashMap<Int, String>()
            try {
                val targets = speakerTargets(am)
                for (s in h.strategyIds()) {
                    res[s] = targets.joinToString(" ") { (type, addr) ->
                        val code = try { h.setNonDefault(s, h.ada(type, addr)).toString() } catch (t: Throwable) {
                            error(ctx, "setDeviceAsNonDefaultForStrategy($s,type=$type)", t); "EXC"
                        }
                        "t$type=$code"
                    }
                }
            } catch (t: Throwable) { error(ctx, "getAudioProductStrategies", t) }
            blockStatus = res
            log(ctx, "block applied ($reason): ${res.size} strategies, " +
                "failures=${res.values.count { v -> v.split(' ').any { !it.endsWith("=0") } }}")
        }

        val active = speakerActive(am)
        val outcome = keeper(ctx, am, h).enforce(active, h != null, reason)
        lastStreams = outcome.states
        notes.addAll(outcome.notes)

        val scan = scanPlayers(ctx, am, h, active)
        reconcileExperiments(ctx, h, am, true, scan)
        val muted = muter(ctx).entries().size
        if (muted > 0) notes.add("$muted app(s) muted")

        statusLine = "ON | Shizuku ${state.diagName} | speaker " + (if (active) "ACTIVE" else "blocked") +
            (if (notes.isEmpty()) "" else " | " + notes.joinToString("; "))
        SpeakerService.refreshNotification(ctx)
    }

    private fun doRescan(ctx: Context, reason: String) {
        if (!isEnabled(ctx)) return
        val am = ctx.getSystemService(AudioManager::class.java)
        val h = hiddenOrNull(ctx)
        val scan = scanPlayers(ctx, am, h, speakerActive(am))
        reconcileExperiments(ctx, h, am, true, scan)
    }

    private fun reconcileExperiments(ctx: Context, h: Hidden?, am: AudioManager, switchOn: Boolean, scan: SpeakerScan?) {
        try { SpeakerExperiments.reconcile(ctx, h, am, switchOn, scan?.uids ?: emptySet()) } catch (t: Throwable) { error(ctx, "experiments", t) }
    }

    /**
     * Finds the apps that have a started player on a built-in speaker (the full configurations need the shell's
     * MODIFY_AUDIO_ROUTING; without Shizuku the public ones are anonymised) and mutes them, and un-mutes the ones that left.
     */
    private fun scanPlayers(ctx: Context, am: AudioManager, h: Hidden?, speakerActive: Boolean): SpeakerScan {
        val routes = try {
            if (h != null) h.playbackRoutes(am) else publicRoutes(am)
        } catch (t: Throwable) {
            error(ctx, "getActivePlaybackConfigurations", t)
            publicRoutes(am)
        }
        lastRoutes = routes
        val scan = SpeakerScans.scan(routes, speakerActive)
        lastScan = scan
        val ops = h?.let { SystemAppOps(it, ctx.packageManager) }
        val res = muter(ctx).reconcile(scan.uids, ops)
        res.muted.forEach { log(ctx, "muted uid ${it.uid} ${it.pkg} (PLAY_AUDIO was mode ${it.previous})") }
        res.restored.forEach { log(ctx, "restored uid ${it.uid} ${it.pkg} to mode ${it.previous}: no longer on the speaker") }
        res.failures.forEach { log(ctx, "ERR $it") }
        if (scan.unknownOwner) {
            // Without Shizuku the owner is always hidden, and with the speaker as the only output the zeroed volume already
            // covers it; pausing is only right when Shizuku shows hidden owners, or an app plays on the speaker next to an output.
            if (h != null || externalOutputConnected(ctx, am)) {
                log(ctx, "a player on the speaker has a hidden owner; cannot mute it, pausing media instead")
                pauseMedia(ctx, "unknown app on speaker")
            }
        }
        return scan
    }

    private fun publicRoutes(am: AudioManager): List<PlayerRoute> = am.activePlaybackConfigurations.map { Hidden.routeOf(it, am) }

    private fun doRelease(ctx: Context) {
        var pending = false
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            val state = shizukuState(ctx)
            val h = if (state == ShizukuStatus.Ready) hidden(ctx) else null
            if (h != null) {
                try {
                    val targets = speakerTargets(am)
                    val ids = h.strategyIds()
                    for (s in ids) for ((type, addr) in targets) {
                        try { h.removeNonDefault(s, h.ada(type, addr)) } catch (t: Throwable) {
                            error(ctx, "removeDeviceAsNonDefaultForStrategy($s,type=$type)", t)
                        }
                    }
                    log(ctx, "block removed for ${ids.size} strategies")
                } catch (t: Throwable) { pending = true; error(ctx, "release block", t) }
            } else {
                pending = true
                error(ctx, "release: routing block NOT removed, Shizuku unavailable (${state.diagName}); will retry when Shizuku is ready", null)
            }
            blockStatus = emptyMap()

            // Apps muted through their PLAY_AUDIO op get their previous mode back.
            val muter = muter(ctx)
            val rel = muter.releaseAll(h?.let { SystemAppOps(it, ctx.packageManager) })
            rel.restored.forEach { log(ctx, "restored uid ${it.uid} ${it.pkg} to mode ${it.previous}") }
            rel.failures.forEach { log(ctx, "ERR $it") }
            if (muter.hasEntries()) {
                pending = true
                log(ctx, "ERR ${muter.entries().size} muted app(s) NOT restored; will retry when Shizuku is ready")
            }
            lastScan = null

            // Experiments let go of their hard blocks.
            SpeakerExperiments.disengageAll(ctx, h)
            if (SpeakerExperiments.hasPending(ctx)) pending = true

            if (!keeper(ctx, am, h).restore(speakerActive(am), h != null)) pending = true
            lastStreams = emptyList()
            statusLine = "OFF"
        } catch (t: Throwable) { pending = true; error(ctx, "release", t) }
        prefs(ctx).edit().putBoolean(K_PENDING, pending).apply()
        SpeakerService.refreshNotification(ctx)
    }

    /** If an OFF could not finish (routing block, muted apps, volumes, fake headset), redo the release once Shizuku is ready. */
    fun retryPendingRelease(ctx: Context) {
        val app = ctx.applicationContext
        if (isEnabled(app)) return
        if (!prefs(app).getBoolean(K_PENDING, false) && !muter(app).hasEntries() && !SpeakerExperiments.hasPending(app)) return
        if (shizukuState(app) != ShizukuStatus.Ready) return
        log(app, "retrying pending release")
        exec.execute { doRelease(app) }
    }

    /** Boot: the audio server starts clean, so flags describing its previous life are dropped. */
    fun onBoot(ctx: Context) {
        SpeakerExperiments.clearStaleAtBoot(ctx.applicationContext)
    }

    fun pauseMedia(ctx: Context, why: String) {
        val now = System.currentTimeMillis()
        if (now - lastPauseMs < 1500) return
        lastPauseMs = now
        try {
            val am = ctx.getSystemService(AudioManager::class.java)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            log(ctx, "pause dispatched ($why)")
        } catch (t: Throwable) { error(ctx, "dispatchMediaKeyEvent", t) }
    }

    // ---------- diagnostics ----------

    /** Blocking (binder calls); call off the main thread. */
    fun diagnostics(ctx: Context): String {
        val app = ctx.applicationContext
        val am = app.getSystemService(AudioManager::class.java)
        val sb = StringBuilder()
        val state = shizukuState(app)
        sb.append("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Switch: ${if (isEnabled(app)) "ON" else "OFF"}  Shizuku: ${state.diagName}  status: $statusLine\n")
        sb.append("\nOutput devices:\n")
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach {
            sb.append("  [${it.id}] ${typeName(it.type)}(${it.type}) '${it.productName}' addr='${it.address}'\n")
        }
        sb.append("\nMedia route: ")
        val md = mediaDevices(am)
        sb.append(if (md.isEmpty()) "(none/unknown)" else md.joinToString { "${typeName(it.type)}(${it.type}) '${it.productName}'" }).append('\n')
        sb.append("\nStrategy block status (code 0 = ok; tN = device type N):\n")
        if (blockStatus.isEmpty()) sb.append("  (not applied yet)\n")
        blockStatus.forEach { (s, v) -> sb.append("  strategy $s: $v\n") }
        val h = if (state == ShizukuStatus.Ready) hidden(app) else null
        val active = speakerActive(am)
        sb.append("\nSpeaker volumes (speakerActive=$active; target = what is enforced, a minimum above 0 is covered by the app mute):\n")
        val streams = try { keeper(app, am, h).snapshot(active, h != null) } catch (t: Throwable) { emptyList() }
        if (streams.isEmpty()) sb.append("  (unavailable)\n")
        streams.forEach {
            sb.append("  %-13s speaker=%s saved=%s target=%d\n".format(SpeakerStreams.name(it.stream), it.current?.toString() ?: "?", it.saved?.toString() ?: "-", it.target))
        }
        sb.append("  ringer mode now=${am.ringerMode} saved=${prefs(app).getInt(SpeakerVolumeKeeper.K_RINGER, -1).let { if (it < 0) "-" else it.toString() }}\n")
        sb.append("Stream music now: ${am.getStreamVolume(STREAM_MUSIC)}/${am.getStreamMaxVolume(STREAM_MUSIC)}  " +
            "Settings volume_music_speaker=${Settings.System.getInt(app.contentResolver, "volume_music_speaker", -1)}\n")

        sb.append("\nPlayers (started ones routed to a speaker get muted):\n")
        val routes = lastRoutes
        if (routes.isEmpty()) sb.append("  (none seen)\n")
        routes.forEach {
            sb.append("  uid=${if (it.uid < 0) "hidden" else it.uid.toString()} ${if (it.started) "started" else "idle"} on=${when (it.onSpeaker) { true -> "speaker"; false -> "other output"; null -> "unknown" }}\n")
        }
        val muted = muter(app).entries()
        sb.append("Muted apps (PLAY_AUDIO ignored, previous mode restored when they leave the speaker or on OFF): ${if (muted.isEmpty()) "none" else ""}\n")
        muted.forEach { sb.append("  uid ${it.uid} ${it.pkg} previous mode ${it.previous}\n") }
        lastScan?.let { if (it.unknownOwner) sb.append("  a player on the speaker has a hidden owner (media is paused instead)\n") }
        sb.append(SpeakerExperiments.diagnostics(app))
        h?.let { hh ->
            sb.append("\nHidden APIs bound (app op code ${hh.opCode}):\n")
            hh.signatures.forEach { (k, v) -> sb.append("  $k: $v\n") }
        }
        sb.append("\nLast errors:\n")
        synchronized(logLines) {
            if (errors.isEmpty()) sb.append("  (none)\n")
            errors.reversed().forEach { sb.append("  $it\n") }
            sb.append("\nEvent log (newest first):\n")
            logLines.reversed().take(60).forEach { sb.append("  $it\n") }
        }
        return sb.toString()
    }

    private fun typeName(t: Int) = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "BUILTIN_SPEAKER_SAFE"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "BUILTIN_EARPIECE"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BLUETOOTH_A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE_SPEAKER"
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "BLE_BROADCAST"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB_DEVICE"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        else -> "TYPE_$t"
    }
}
