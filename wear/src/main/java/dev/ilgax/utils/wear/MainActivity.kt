package dev.ilgax.utils.wear

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.ilgax.utils.protocol.Command
import dev.ilgax.utils.protocol.HostEntry
import dev.ilgax.utils.protocol.HostFeatures
import dev.ilgax.utils.protocol.TargetState
import dev.ilgax.utils.protocol.UpdateSources
import dev.ilgax.utils.protocol.LinkPolicy
import dev.ilgax.utils.protocol.RetryPolicy
import dev.ilgax.utils.protocol.TransferPolicy
import dev.ilgax.utils.protocol.SpeakerOp
import dev.ilgax.utils.protocol.UpdateReport

class MainActivity : Activity() {
    private enum class Mode { LIST, PAIR_SEARCH, PAIR_CODE, NOW_PLAYING, PICKER, DEVICES, DEVICE, RENAME }

    private lateinit var client: RemoteClient
    private lateinit var presence: Presence
    private lateinit var prefs: WatchPrefs
    private lateinit var updater: WatchUpdater
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var statusView: TextView
    /** Built on first use; hosted here so the link policy and the screen-off handling stay in one place. */
    private var nowPlaying: NowPlayingScreen? = null
    private val ui = Handler(Looper.getMainLooper())
    private var checkStartedAt = 0L
    /** Consecutive failed offer checks; drives the poll back-off. */
    private var pollFailures = 0
    private var lastPollAt = 0L
    private var mode = Mode.LIST
    private var code = ""
    private var statusText = ""
    private var pendingAction: (() -> Unit)? = null
    /** Kill switch state last reported by each host (hex id); missing until its first answer (or when it could not tell). */
    private val speakerStates = HashMap<String, Boolean>()
    /** The host the screen is showing and commands go to; null = none in range / not in range. Changes only through [onPresence]. */
    private var lastTarget: String? = null
    /** When (wall clock) an update offer was last asked of a host that was not the selected one. */
    private val otherChecked = HashMap<String, Long>()
    /** The host whose Now Playing is open. */
    private var nowHost: String? = null
    private var detailHost: String? = null
    private var renameText = ""
    private var confirmRemove = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = RemoteClient.get(this)
        presence = Presence.get(this)
        prefs = WatchPrefs.get(this)
        updater = WatchUpdater.get(this)
        statusText = getString(R.string.status_ready)
        handleIntent(intent)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Rotary crown / bezel scrolling.
            setOnGenericMotionListener { v, e ->
                if (e.action == MotionEvent.ACTION_SCROLL && e.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)) {
                    val delta = -e.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this@MainActivity).scaledVerticalScrollFactor
                    v.scrollBy(0, delta.toInt())
                    true
                } else false
            }
        }
        // Extra top/bottom padding lets every item reach the middle of a round screen.
        val round = resources.configuration.isScreenRound
        val side = if (round) (resources.displayMetrics.widthPixels * 0.12f).toInt() else dp(8)
        content.setPadding(side, dp(if (round) 44 else 16), side, dp(if (round) 64 else 24))
        setContentView(scroll)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        checkForUpdate()
    }

    /** The "update ready" notification starts the update as soon as the offer is read. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateNotifier.EXTRA_START_UPDATE, false) == true) {
            intent.removeExtra(UpdateNotifier.EXTRA_START_UPDATE)
            updater.requestStart()
        }
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is the moment the watch listens for its hosts and tells the target its version.
        updater.visible = true
        client.setForeground(true)
        client.onUpdateHint = { checkForUpdate() }
        client.onMacrosHint = { syncMacros() }
        client.onHostsChanged = { if (!isDestroyed) render() }
        updater.listener = { render(); launchPendingConfirm() }
        presence.onChange = { onPresence() }
        registerReceiver(screenState, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }, RECEIVER_NOT_EXPORTED)
        lastTarget = null // a fresh start of the visible app: do the per-target work once for whatever is heard
        if (mode == Mode.NOW_PLAYING) nowPlaying?.start()
        if (client.isPaired && hasPermissions()) presence.open(sustained = true)
        onPresence()
        schedulePoll()
    }

    override fun onStop() {
        nowPlaying?.stop()
        ui.removeCallbacks(poll)
        unregisterReceiver(screenState)
        updater.visible = false
        updater.listener = null
        presence.onChange = null
        presence.close()
        client.onUpdateHint = null
        client.onMacrosHint = null
        client.onHostsChanged = null
        client.setForeground(false)
        super.onStop()
    }

    /**
     * The heard set or a selection changed. The target only ever changes here, from what is heard and the user's pick; when it does,
     * the per-target housekeeping runs against the new one (the link, info, update offer, macros, kill switch state).
     */
    private fun onPresence() {
        if (isDestroyed) return
        if (!client.isPaired) {
            lastTarget = null
            render()
            return
        }
        val res = presence.resolution()
        // "Several heard, nothing picked": the first one heard becomes the pick, and stays it.
        res.remember?.let { if (prefs.selectedHost == null) prefs.selectedHost = it }
        val target = res.state.targetId
        if (target != lastTarget) {
            lastTarget = target
            if (target != null) startTargetWork(target)
            if (mode == Mode.NOW_PLAYING && (target == null || target != nowHost)) closeNowPlaying()
            if (mode == Mode.PICKER && target == null && res.state is TargetState.NoneInRange) mode = Mode.LIST
        }
        render()
    }

    private fun startTargetWork(target: String) {
        if (!hasPermissions()) return
        pollFailures = 0
        client.warmUp(target) // connect while the user is still looking for a button
        client.refreshInfo(target)
        client.recomputeNative()
        checkForUpdate()
        syncMacros()
        querySpeaker()
        updater.verifyIfNewVersion(target)
        askNotifications()
    }

    /** Screen off (or on) without the activity being stopped, e.g. ambient: let go of the radio, resume when it is back. */
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                ui.removeCallbacks(poll)
                nowPlaying?.pause()
                presence.pause()
                client.setForeground(false)
            } else {
                client.setForeground(true)
                if (client.isPaired && hasPermissions()) presence.resume()
                nowPlaying?.resume()
                checkForUpdate()
                schedulePoll()
            }
        }
    }

    /**
     * While visible, look for an offer now and then: the tablet's ping only reaches a live, subscribed connection, which
     * is gone after any link drop, and nothing else would re-establish it. With the link up the ping does the work, so
     * the tick is rare; without it, or after failures, see [LinkPolicy.pollDelayMs]. Never while the screen is off.
     */
    private val poll = object : Runnable {
        override fun run() {
            val interactive = getSystemService(PowerManager::class.java).isInteractive
            val now = SystemClock.elapsedRealtime()
            if (mode == Mode.LIST && lastTarget != null && LinkPolicy.shouldPoll(interactive, client.isLinked, now - lastPollAt)) {
                lastPollAt = now
                checkForUpdate()
            }
            schedulePoll()
        }
    }

    private fun schedulePoll() {
        ui.removeCallbacks(poll)
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        ui.postDelayed(poll, LinkPolicy.pollDelayMs(interactive, client.isLinked, pollFailures))
    }

    override fun onResume() {
        super.onResume()
        render()
        if (mode == Mode.NOW_PLAYING) nowPlaying?.root?.requestFocus() else scroll.requestFocus()
        launchPendingConfirm()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (mode) {
            Mode.NOW_PLAYING -> closeNowPlaying()
            Mode.PICKER, Mode.DEVICES -> goTo(Mode.LIST)
            Mode.DEVICE -> goTo(Mode.DEVICES)
            Mode.RENAME -> goTo(Mode.DEVICE)
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private fun goTo(m: Mode) {
        mode = m
        confirmRemove = false
        render()
        scroll.scrollTo(0, 0)
    }

    private fun openNowPlaying() = withPermissions {
        nowHost = lastTarget ?: return@withPermissions
        val screen = nowPlaying ?: NowPlayingScreen(this, client).also { nowPlaying = it }
        mode = Mode.NOW_PLAYING
        setContentView(screen.root)
        screen.start()
    }

    private fun closeNowPlaying() {
        nowPlaying?.stop()
        mode = Mode.LIST
        setContentView(scroll)
        render()
        scroll.requestFocus()
    }

    override fun onDestroy() {
        if (mode != Mode.LIST) client.cancelPairing()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        val action = pendingAction
        pendingAction = null
        if (hasPermissions()) {
            action?.invoke()
            if (client.isPaired) { presence.open(sustained = true); onPresence() }
        } else setStatus(getString(R.string.res_no_permission))
    }

    // region rendering

    /** One line of the screen; [render] builds the list, [reconcile] makes the views match it without recreating them. */
    private class Row(
        val kind: Kind,
        val text: String = "",
        val primary: Boolean = false,
        val size: Float = 13f,
        val checked: Boolean = false,
        val onClick: (() -> Unit)? = null,
    )

    private enum class Kind { STATUS, INFO, HEADING, CODE, PILL, TOGGLE, FOOTER, NAME, DROPDOWN, EDIT }

    private fun render() {
        reconcile(rows())
    }

    private fun rows(): List<Row> {
        val out = ArrayList<Row>(24)
        out += Row(Kind.STATUS, statusText)
        when {
            mode == Mode.PAIR_SEARCH -> {
                out += info(getString(R.string.pair_search_info))
                out += pill(getString(R.string.btn_cancel)) { cancelPairing() }
            }
            mode == Mode.PAIR_CODE -> {
                out += info(getString(R.string.pair_compare))
                out += Row(Kind.CODE, getString(R.string.code_display, code.substring(0, 3), code.substring(3)))
                out += pill(getString(R.string.btn_confirm), primary = true) {
                    setStatus(getString(R.string.status_confirming))
                    client.confirmPairing()
                }
                out += pill(getString(R.string.btn_cancel)) { cancelPairing() }
            }
            !client.isPaired -> {
                out += info(getString(R.string.unpaired_info))
                out += pill(getString(R.string.btn_pair), primary = true) { beginPairing() }
            }
            mode == Mode.PICKER -> pickerRows(out)
            mode == Mode.DEVICES -> devicesRows(out)
            mode == Mode.DEVICE -> deviceRows(out)
            mode == Mode.RENAME -> renameRows(out)
            else -> mainRows(out)
        }
        return out
    }

    private fun hostName(id: String): String = prefs.hosts.get(id)?.name ?: ""

    /** The heard hosts the picker offers. */
    private fun pickable(st: TargetState): List<String> = when (st) {
        is TargetState.Choice -> st.heard
        is TargetState.NotInRange -> st.heard
        is TargetState.Single -> listOf(st.id)
        TargetState.NoneInRange -> emptyList()
    }

    /** The screen for the target rules: nothing heard, one host, several (dropdown), or the picked one gone silent. */
    private fun mainRows(out: MutableList<Row>) {
        if (!hasPermissions()) {
            out += info(getString(R.string.res_no_permission))
            out += pill(getString(R.string.btn_allow), primary = true) { withPermissions { } }
            return
        }
        val st = presence.resolution().state
        when (st) {
            TargetState.NoneInRange -> {
                out += info(getString(if (presence.searching) R.string.status_searching else R.string.no_device_in_range))
                if (!presence.searching) out += pill(getString(R.string.btn_retry)) { presence.rescan() }
            }
            is TargetState.Single -> out += Row(Kind.NAME, hostName(st.id))
            is TargetState.Choice -> out += Row(Kind.DROPDOWN, getString(R.string.dropdown, hostName(st.id))) { goTo(Mode.PICKER) }
            is TargetState.NotInRange -> {
                out += Row(Kind.DROPDOWN, getString(R.string.dropdown, getString(R.string.host_not_in_range, hostName(st.id)))) { goTo(Mode.PICKER) }
            }
        }
        updateRows(out)
        st.targetId?.let { listRows(out, it) }
        out += pill(getString(R.string.btn_devices)) { goTo(Mode.DEVICES) }
        out += Row(Kind.FOOTER, updater.footer)
    }

    private fun pickerRows(out: MutableList<Row>) {
        val st = presence.resolution().state
        out += Row(Kind.HEADING, getString(R.string.picker_title))
        val ids = pickable(st)
        if (ids.isEmpty()) out += info(getString(R.string.no_device_in_range))
        for (id in ids) out += pill(hostName(id), primary = id == st.targetId) { pick(id) }
        out += pill(getString(R.string.btn_cancel)) { goTo(Mode.LIST) }
    }

    /** Manual selection; the only way the target changes besides the first-heard default. */
    private fun pick(id: String) {
        prefs.selectedHost = id
        statusText = getString(R.string.status_ready)
        mode = Mode.LIST
        onPresence()
        scroll.scrollTo(0, 0)
    }

    private fun devicesRows(out: MutableList<Row>) {
        out += Row(Kind.HEADING, getString(R.string.devices_title))
        val heard = presence.heard()
        for (e in prefs.hosts.entries) {
            val text = if (e.id in heard) e.name else getString(R.string.device_not_in_range, e.name)
            out += pill(text) { detailHost = e.id; goTo(Mode.DEVICE) }
        }
        out += pill(getString(R.string.btn_add_device), primary = true) { beginPairing() }
        out += pill(getString(R.string.btn_back)) { goTo(Mode.LIST) }
    }

    private fun deviceRows(out: MutableList<Row>) {
        val e = detailHost?.let { prefs.hosts.get(it) } ?: return run { mode = Mode.DEVICES; devicesRows(out) }
        out += Row(Kind.NAME, e.name)
        out += info(getString(if (e.id in presence.heard()) R.string.device_in_range else R.string.not_in_range), size = 11f)
        out += Row(Kind.TOGGLE, getString(R.string.toggle_own_phone), checked = e.isNative) {
            prefs.updateHosts { it.setNativeOverride(e.id, !e.isNative) }
            render()
        }
        if (e.btName != null) out += info(getString(R.string.device_bt_name, e.btName!!, getString(if (e.nativeAuto) R.string.yes else R.string.no)), size = 11f)
        out += pill(getString(R.string.btn_rename)) { renameText = e.name; goTo(Mode.RENAME) }
        out += pill(getString(R.string.btn_repair)) { beginPairing() }
        out += pill(getString(if (confirmRemove) R.string.btn_remove_confirm else R.string.btn_remove)) {
            if (!confirmRemove) {
                confirmRemove = true
                render()
            } else {
                confirmRemove = false
                client.removeHost(e.id)
                mode = Mode.DEVICES
                render()
            }
        }
        out += pill(getString(R.string.btn_back)) { goTo(Mode.DEVICES) }
    }

    private fun renameRows(out: MutableList<Row>) {
        out += Row(Kind.HEADING, getString(R.string.rename_title))
        out += Row(Kind.EDIT, renameText)
        out += pill(getString(R.string.btn_save), primary = true) {
            val id = detailHost ?: return@pill
            prefs.updateHosts { it.rename(id, renameText) }
            goTo(Mode.DEVICE)
        }
        out += pill(getString(R.string.btn_cancel)) { goTo(Mode.DEVICE) }
    }

    private fun listRows(out: MutableList<Row>, host: String) {
        val native = prefs.hosts.get(host)?.isNative == true
        // The built-in watch-phone link covers media, volume and Find My Phone for the watch's own phone.
        fun has(c: Command) = HostFeatures.available(c, native)
        if (HostFeatures.nowPlaying(native)) out += pill(getString(R.string.btn_now_playing), primary = true) { openNowPlaying() }
        out += pill(getString(R.string.btn_lock)) { run(Command.LOCK) }
        if (has(Command.PLAY_PAUSE)) out += pill(getString(R.string.btn_play_pause)) { run(Command.PLAY_PAUSE) }
        if (has(Command.VOLUME_UP)) out += pill(getString(R.string.btn_vol_up)) { run(Command.VOLUME_UP) }
        if (has(Command.VOLUME_DOWN)) out += pill(getString(R.string.btn_vol_down)) { run(Command.VOLUME_DOWN) }
        if (has(Command.RING)) out += pill(getString(R.string.btn_ring)) { run(Command.RING) }
        out += pill(getString(R.string.btn_close_app)) { run(Command.KILL_APP) }
        val speakerOn = speakerStates[host]
        out += pill(
            getString(
                when (speakerOn) {
                    true -> R.string.btn_speaker_on
                    false -> R.string.btn_speaker_off
                    null -> R.string.btn_speaker_unknown
                },
            ),
            primary = speakerOn == true,
        ) { speaker(SpeakerOp.TOGGLE) }

        val labels = prefs.slotLabels(host)
        if (labels.any { it.isNotEmpty() }) {
            val switching = prefs.closeFirst
            out += Row(Kind.TOGGLE, getString(R.string.toggle_close_first), checked = switching) {
                prefs.closeFirst = !switching
                render()
            }
            labels.forEachIndexed { i, label ->
                if (label.isEmpty()) return@forEachIndexed
                val text = getString(if (switching) R.string.btn_switch_to else R.string.btn_open, label)
                out += pill(text) { run(if (switching) Command.SWITCH_APP else Command.OPEN_APP, i, label) }
            }
        }
        // Macros stay hidden entirely unless the host's Utils answered a macro sync.
        if (prefs.macrosSupported(host) == true) {
            out += Row(Kind.HEADING, getString(R.string.macros_title))
            val macros = prefs.macros(host)
            if (macros.isEmpty()) out += info(getString(R.string.macros_empty))
            for (m in macros) out += pill(m.name) { runMacro(m.id, m.name) }
        }
        out += pill(getString(R.string.btn_sync)) { sync() }
        out += pill(getString(R.string.btn_verify)) { verifyApp() }
    }

    private fun updateRows(out: MutableList<Row>) {
        when (val u = updater.state) {
            UpdateUi.Idle -> Unit
            is UpdateUi.Available -> {
                out += info(getString(R.string.update_available))
                out += pill(getString(R.string.btn_update_to, u.offer.versionCode), primary = true) { updater.start(u.offer, u.host) }
                out += pill(getString(R.string.btn_dismiss)) { updater.dismiss() }
            }
            is UpdateUi.Downloading -> {
                u.fallback?.let { out += info(getString(fallbackText(it)), size = 11f) }
                out += info(getString(if (u.bluetooth) R.string.update_downloading_bt else R.string.update_downloading, u.percent))
            }
            is UpdateUi.Retrying -> out += info(getString(R.string.update_retrying, u.attempt, RetryPolicy.MAX_ATTEMPTS))
            is UpdateUi.Installing -> out += info(getString(R.string.update_installing, u.version))
            is UpdateUi.Confirm -> {
                out += info(getString(R.string.update_confirm))
                out += info(getString(R.string.update_confirm_note), size = 11f)
            }
            UpdateUi.NeedsInstallPermission -> {
                out += info(getString(R.string.update_allow_installs))
                out += info(WatchUpdater.ADB_FIX, size = 11f)
                out += pill(getString(R.string.btn_dismiss)) { updater.dismiss() }
            }
            is UpdateUi.Failed -> {
                out += info(
                    getString(
                        when (u.failure) {
                            UpdateReport.Failure.NO_WIFI -> R.string.update_fail_no_wifi
                            UpdateReport.Failure.DOWNLOAD -> R.string.update_fail_download
                            UpdateReport.Failure.HASH_MISMATCH -> R.string.update_fail_hash
                            UpdateReport.Failure.INSTALL_FAILED -> R.string.update_fail_install
                            UpdateReport.Failure.CERT_MISMATCH -> R.string.update_fail_cert
                            else -> R.string.update_fail_unknown
                        },
                    ),
                )
                out += pill(getString(R.string.btn_dismiss)) { updater.dismiss() }
            }
        }
    }

    /** Why the update is coming over Wi-Fi although Bluetooth is preferred. */
    private fun fallbackText(reason: TransferPolicy.FallbackReason): Int = when (reason) {
        TransferPolicy.FallbackReason.NOT_OFFERED, TransferPolicy.FallbackReason.NO_CHANNEL -> R.string.update_fallback_not_offered
        TransferPolicy.FallbackReason.STALLED -> R.string.update_fallback_stalled
        else -> R.string.update_fallback_failed
    }

    private fun info(text: String, size: Float = 13f) = Row(Kind.INFO, text, size = size)

    private fun pill(text: String, primary: Boolean = false, onClick: () -> Unit) = Row(Kind.PILL, text, primary, onClick = onClick)

    /** Position by position: a view of the right kind is updated in place (text, colour, click), anything else is replaced. */
    private fun reconcile(rows: List<Row>) {
        rows.forEachIndexed { i, row ->
            val existing = content.getChildAt(i)
            if (existing != null && existing.tag == row.kind) {
                bind(existing, row)
            } else {
                val v = create(row.kind).also { it.tag = row.kind }
                bind(v, row)
                if (existing != null) content.removeViewAt(i)
                content.addView(v, i, params(row.kind))
            }
        }
        if (content.childCount > rows.size) content.removeViews(rows.size, content.childCount - rows.size)
        statusView = content.getChildAt(0) as TextView
    }

    private fun create(kind: Kind): View = when (kind) {
        Kind.STATUS -> TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        }
        Kind.INFO -> TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        Kind.HEADING -> TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFF78909C.toInt())
            setPadding(0, dp(12), 0, dp(4))
        }
        Kind.CODE -> TextView(this).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(12))
        }
        Kind.FOOTER -> TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF78909C.toInt())
            setPadding(0, dp(8), 0, 0)
        }
        Kind.NAME -> TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF90CAF9.toInt())
            setPadding(0, 0, 0, dp(8))
        }
        Kind.DROPDOWN -> Button(this).apply {
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFF90CAF9.toInt())
            minHeight = dp(40)
            minimumHeight = dp(40)
            isFocusable = false
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0xFF90CAF9.toInt()); setColor(Color.TRANSPARENT) }
        }
        Kind.EDIT -> EditText(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(c: CharSequence?, st: Int, n: Int, a: Int) = Unit
                override fun onTextChanged(c: CharSequence?, st: Int, b: Int, n: Int) { renameText = c?.toString() ?: "" }
                override fun afterTextChanged(e: Editable?) = Unit
            })
        }
        Kind.TOGGLE -> Switch(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            isFocusable = false
        }
        Kind.PILL -> Button(this).apply {
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            minHeight = dp(52)
            minimumHeight = dp(52)
            isFocusable = false // keep focus on the ScrollView so the rotary input scrolls it
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat() }
        }
    }

    private fun params(kind: Kind) = when (kind) {
        Kind.TOGGLE -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(4) }
        Kind.PILL, Kind.DROPDOWN -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4); bottomMargin = if (kind == Kind.DROPDOWN) dp(6) else 0 }
        else -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun bind(v: View, row: Row) {
        when (row.kind) {
            Kind.TOGGLE -> (v as Switch).apply {
                setOnCheckedChangeListener(null)
                if (text.toString() != row.text) text = row.text
                isChecked = row.checked
                setOnCheckedChangeListener { _, _ -> row.onClick?.invoke() }
            }
            Kind.PILL -> (v as Button).apply {
                if (text.toString() != row.text) text = row.text
                (background as GradientDrawable).setColor(getColor(if (row.primary) R.color.button_primary else R.color.button_bg))
                setOnClickListener { row.onClick?.invoke() }
            }
            Kind.DROPDOWN -> (v as Button).apply {
                if (text.toString() != row.text) text = row.text
                setOnClickListener { row.onClick?.invoke() }
            }
            Kind.EDIT -> (v as EditText).apply { if (text.toString() != row.text) setText(row.text) }
            else -> (v as TextView).apply {
                if (row.kind == Kind.INFO) textSize = row.size
                if (text.toString() != row.text) text = row.text
            }
        }
    }

    // endregion

    // region actions

    private fun run(command: Command, slot: Int = -1, label: String? = null) {
        val host = lastTarget ?: return
        withPermissions {
            setStatus(getString(R.string.status_sending))
            val args = if (slot >= 0) byteArrayOf(slot.toByte()) else ByteArray(0)
            client.send(command, args, host = host) { r ->
                if (isDestroyed) return@send
                setStatus(ResultText.announce(this, r, command, label))
            }
        }
    }

    /** Tap on the kill switch button: asks the target to toggle, shows the state it answers with. */
    private fun speaker(op: SpeakerOp) {
        val host = lastTarget ?: return
        withPermissions {
            setStatus(getString(R.string.status_speaker_asking))
            client.speaker(op, host) { r -> onSpeakerResult(host, r, quiet = false) }
        }
    }

    /** Silent like [checkForUpdate]: learn the state when the target is known so the button shows ON/OFF. */
    private fun querySpeaker() {
        val host = lastTarget ?: return
        if (!client.isPaired || !hasPermissions()) return
        client.speaker(SpeakerOp.QUERY, host) { r -> onSpeakerResult(host, r, quiet = true) }
    }

    private fun onSpeakerResult(host: String, r: Result, quiet: Boolean) {
        if (isDestroyed) return
        val state = r.speakerOn
        val changed = state != null && state != speakerStates[host]
        if (state != null) speakerStates[host] = state
        if (!quiet && host == lastTarget) setStatus(ResultText.announce(this, r, Command.SPEAKER))
        if (changed && mode == Mode.LIST) render()
    }

    private fun runMacro(id: Int, name: String) {
        val host = lastTarget ?: return
        withPermissions {
            setStatus(getString(R.string.status_sending))
            client.runMacro(id, host) { r ->
                if (isDestroyed) return@runMacro
                val text = ResultText.announce(this, r, Command.RUN_MACRO)
                setStatus(text)
                // The status line is at the top of a long list, so say it where the finger is too.
                android.widget.Toast.makeText(this, "$name: $text", android.widget.Toast.LENGTH_SHORT).show()
                if (r == Result.MACRO_NOT_FOUND) syncMacros()
            }
        }
    }

    /** Silent housekeeping like [checkForUpdate]: fetches the target's macro list only when its version differs. */
    private fun syncMacros() {
        val host = lastTarget ?: return
        if (!client.isPaired || !hasPermissions()) return
        client.syncMacros(prefs.macroVersion(host), host) { result ->
            if (isDestroyed) return@syncMacros
            var changed = false
            when (result) {
                is MacroSync.Updated -> {
                    changed = prefs.macros(host) != result.items || prefs.macrosSupported(host) != true
                    prefs.setMacros(host, result.items)
                    prefs.setMacroVersion(host, result.version)
                    prefs.setMacrosSupported(host, true)
                }
                MacroSync.Unchanged -> {
                    changed = prefs.macrosSupported(host) != true
                    prefs.setMacrosSupported(host, true)
                }
                MacroSync.Unsupported -> {
                    changed = prefs.macrosSupported(host) != false
                    prefs.setMacrosSupported(host, false)
                    prefs.setMacros(host, emptyList())
                    prefs.setMacroVersion(host, null)
                }
                is MacroSync.Failed -> Unit
            }
            if (changed && mode == Mode.LIST && host == lastTarget) render()
        }
    }

    private fun sync() {
        val host = lastTarget ?: return
        withPermissions {
            setStatus(getString(R.string.status_syncing))
            client.syncApps(host) { r ->
                if (isDestroyed) return@syncApps
                if (r == Result.SUCCESS) {
                    setStatus(getString(R.string.status_synced))
                    Haptics.success(this)
                    render()
                } else {
                    setStatus(ResultText.describe(this, r, Command.SYNC_APPS))
                    Haptics.failure(this)
                }
            }
        }
    }

    private fun beginPairing() = withPermissions {
        mode = Mode.PAIR_SEARCH
        statusText = getString(R.string.status_searching)
        render()
        client.startPairing(object : PairingListener {
            override fun onCode(code: String) {
                if (isDestroyed) return
                this@MainActivity.code = code
                mode = Mode.PAIR_CODE
                statusText = getString(R.string.status_check_code)
                render()
            }

            override fun onTabletNotConfirmed() {
                if (!isDestroyed) setStatus(getString(R.string.status_confirm_tablet))
            }

            override fun onFinished(result: Result) {
                if (isDestroyed || result == Result.CANCELLED) return
                mode = Mode.LIST
                statusText = if (result == Result.SUCCESS) getString(R.string.status_paired)
                else ResultText.describe(this@MainActivity, result, null)
                if (result == Result.SUCCESS) Haptics.success(this@MainActivity) else Haptics.failure(this@MainActivity)
                if (result == Result.SUCCESS) {
                    // The new host is the target now (it was picked by pairing it); its link is up, which counts as heard.
                    presence.open(sustained = true)
                    onPresence()
                } else {
                    render()
                }
            }
        })
    }

    /**
     * HELLO + offer pickup, silent on any problem: this is background housekeeping, not a user action. The target is asked first; when it
     * has no offer, the other heard hosts are asked too (each at most every few minutes), so any reachable host that offers a newer
     * watch app can update the watch.
     */
    private fun checkForUpdate() {
        val target = lastTarget ?: return
        if (!client.isPaired || !hasPermissions() || updater.isBusy) return
        val now = SystemClock.elapsedRealtime()
        if (checkStartedAt != 0L && now - checkStartedAt < CHECK_STUCK_MS) return // one at a time
        val order = UpdateSources.order(target, presence.heard(), otherChecked, System.currentTimeMillis())
        if (order.isEmpty()) return
        checkStartedAt = now
        runChecks(target, order, 0)
    }

    private fun runChecks(target: String, order: List<String>, i: Int) {
        val host = order[i]
        client.checkForUpdate(WatchUpdater.versionCode(this), host) { check ->
            if (isDestroyed) {
                checkStartedAt = 0L
                return@checkForUpdate
            }
            if (i == 0) pollFailures = if (check is UpdateCheck.Failed) pollFailures + 1 else 0
            if (host != target) otherChecked[host] = System.currentTimeMillis()
            updater.handleCheck(check, host)
            if (check !is UpdateCheck.Offer && i + 1 < order.size && !updater.isBusy) {
                runChecks(target, order, i + 1)
            } else {
                checkStartedAt = 0L
                if (i > 0 && target == lastTarget) client.warmUp(target) // back to the target's link
            }
        }
    }

    /** The "Verify watch app" button: hash the installed APK and key, send them to the target. */
    private fun verifyApp() {
        val host = lastTarget ?: return
        withPermissions {
            setStatus(getString(R.string.status_verifying))
            updater.verifyAndReport(host) { outcome ->
                if (isDestroyed) return@verifyAndReport
                setStatus(
                    getString(
                        when (outcome) {
                            WatchUpdater.VerifyOutcome.SENT -> R.string.status_verified
                            WatchUpdater.VerifyOutcome.NOT_SENT -> R.string.status_verify_not_sent
                            WatchUpdater.VerifyOutcome.TABLET_TOO_OLD -> R.string.res_speaker_unsupported
                            WatchUpdater.VerifyOutcome.UNREADABLE -> R.string.status_verify_unreadable
                        },
                    ),
                )
            }
        }
    }

    /** Update notifications need the runtime permission on Wear OS 4+; asked once, after Bluetooth is set up. */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33 || prefs.askedNotifications) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.askedNotifications = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    /** The system install dialog must be started from a visible activity; if that fails it becomes a notification. */
    private fun launchPendingConfirm() {
        val intent = updater.takePendingConfirm() ?: return
        try {
            startActivity(intent)
        } catch (e: Exception) {
            updater.launchFailed(intent)
            setStatus(getString(R.string.update_fail_install))
        }
    }

    private fun cancelPairing() {
        client.cancelPairing()
        mode = Mode.LIST
        statusText = getString(R.string.status_ready)
        render()
    }

    private fun hasPermissions() = Permissions.bluetoothGranted(this)

    private fun withPermissions(action: () -> Unit) {
        if (hasPermissions()) return action()
        pendingAction = action
        requestPermissions(Permissions.BLUETOOTH, REQUEST_BLUETOOTH)
    }

    private fun setStatus(text: String) {
        statusText = text
        statusView.text = text
    }

    // endregion

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_BLUETOOTH = 1
        const val REQUEST_NOTIFICATIONS = 2
        const val CHECK_STUCK_MS = 30_000L
    }
}
