package dev.ilgax.tabremote.wear

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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.LinkPolicy
import dev.ilgax.tabremote.protocol.RetryPolicy
import dev.ilgax.tabremote.protocol.SpeakerOp
import dev.ilgax.tabremote.protocol.UpdateReport

class MainActivity : Activity() {
    private enum class Mode { LIST, PAIR_SEARCH, PAIR_CODE }

    private lateinit var client: RemoteClient
    private lateinit var prefs: WatchPrefs
    private lateinit var updater: WatchUpdater
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var statusView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var checkStartedAt = 0L
    /** Consecutive failed offer checks; drives the poll back-off. */
    private var pollFailures = 0
    private var lastPollAt = 0L
    private var mode = Mode.LIST
    private var code = ""
    private var statusText = ""
    private var pendingAction: (() -> Unit)? = null
    /** Kill switch state last reported by the tablet; null until the first answer (or when the tablet could not tell). */
    private var speakerOn: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = RemoteClient.get(this)
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
        // Opening the app is the moment the watch tells the tablet its version and picks up an update offer.
        updater.visible = true
        client.setForeground(true)
        client.onUpdateHint = { checkForUpdate() }
        client.onMacrosHint = { syncMacros() }
        updater.listener = { render(); launchPendingConfirm() }
        registerReceiver(screenState, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }, RECEIVER_NOT_EXPORTED)
        if (client.isPaired && hasPermissions()) client.warmUp() // connect while the user is still looking for a button
        checkForUpdate()
        syncMacros()
        querySpeaker()
        schedulePoll()
        if (client.isPaired && hasPermissions()) {
            updater.verifyIfNewVersion()
            askNotifications()
        }
    }

    override fun onStop() {
        ui.removeCallbacks(poll)
        unregisterReceiver(screenState)
        updater.visible = false
        updater.listener = null
        client.onUpdateHint = null
        client.onMacrosHint = null
        client.setForeground(false)
        super.onStop()
    }

    /** Screen off (or on) without the activity being stopped, e.g. ambient: let go of the radio, resume when it is back. */
    private val screenState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                ui.removeCallbacks(poll)
                client.setForeground(false)
            } else {
                client.setForeground(true)
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
            if (mode == Mode.LIST && LinkPolicy.shouldPoll(interactive, client.isLinked, now - lastPollAt)) {
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
        scroll.requestFocus()
        launchPendingConfirm()
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
        if (hasPermissions()) action?.invoke() else setStatus(getString(R.string.res_no_permission))
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

    private enum class Kind { STATUS, INFO, HEADING, CODE, PILL, TOGGLE, FOOTER }

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
            else -> listRows(out)
        }
        return out
    }

    private fun listRows(out: MutableList<Row>) {
        updateRows(out)
        out += pill(getString(R.string.btn_lock), primary = true) { run(Command.LOCK) }
        out += pill(getString(R.string.btn_play_pause)) { run(Command.PLAY_PAUSE) }
        out += pill(getString(R.string.btn_vol_up)) { run(Command.VOLUME_UP) }
        out += pill(getString(R.string.btn_vol_down)) { run(Command.VOLUME_DOWN) }
        out += pill(getString(R.string.btn_ring)) { run(Command.RING) }
        out += pill(getString(R.string.btn_close_app)) { run(Command.KILL_APP) }
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

        val labels = prefs.slotLabels
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
        // Macros stay hidden entirely unless the tablet's Utils answered a macro sync.
        if (prefs.macrosSupported == true) {
            out += Row(Kind.HEADING, getString(R.string.macros_title))
            val macros = prefs.macros
            if (macros.isEmpty()) out += info(getString(R.string.macros_empty))
            for (m in macros) out += pill(m.name) { runMacro(m.id, m.name) }
        }
        out += pill(getString(R.string.btn_sync)) { sync() }
        out += pill(getString(R.string.btn_repair)) { beginPairing() }
        out += pill(getString(R.string.btn_verify)) { verifyApp() }
        out += Row(Kind.FOOTER, updater.footer)
    }

    private fun updateRows(out: MutableList<Row>) {
        when (val u = updater.state) {
            UpdateUi.Idle -> Unit
            is UpdateUi.Available -> {
                out += info(getString(R.string.update_available))
                out += pill(getString(R.string.btn_update_to, u.offer.versionCode), primary = true) { updater.start(u.offer) }
                out += pill(getString(R.string.btn_dismiss)) { updater.dismiss() }
            }
            is UpdateUi.Downloading -> out += info(getString(R.string.update_downloading, u.percent))
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
        Kind.PILL -> LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
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
            else -> (v as TextView).apply {
                if (row.kind == Kind.INFO) textSize = row.size
                if (text.toString() != row.text) text = row.text
            }
        }
    }

    // endregion

    // region actions

    private fun run(command: Command, slot: Int = -1, label: String? = null) {
        withPermissions {
            setStatus(getString(R.string.status_sending))
            val args = if (slot >= 0) byteArrayOf(slot.toByte()) else ByteArray(0)
            client.send(command, args) { r ->
                if (isDestroyed) return@send
                setStatus(ResultText.announce(this, r, command, label))
            }
        }
    }

    /** Tap on the kill switch button: asks the tablet to toggle, shows the state it answers with. */
    private fun speaker(op: SpeakerOp) = withPermissions {
        setStatus(getString(R.string.status_speaker_asking))
        client.speaker(op) { r -> onSpeakerResult(r, quiet = false) }
    }

    /** Silent like [checkForUpdate]: learn the state when the app opens so the button shows ON/OFF. */
    private fun querySpeaker() {
        if (!client.isPaired || !hasPermissions()) return
        client.speaker(SpeakerOp.QUERY) { r -> onSpeakerResult(r, quiet = true) }
    }

    private fun onSpeakerResult(r: Result, quiet: Boolean) {
        if (isDestroyed) return
        val state = r.speakerOn
        val changed = state != null && state != speakerOn
        if (state != null) speakerOn = state
        if (!quiet) setStatus(ResultText.announce(this, r, Command.SPEAKER))
        if (changed && mode == Mode.LIST) render()
    }

    private fun runMacro(id: Int, name: String) = withPermissions {
        setStatus(getString(R.string.status_sending))
        client.runMacro(id) { r ->
            if (isDestroyed) return@runMacro
            val text = ResultText.announce(this, r, Command.RUN_MACRO)
            setStatus(text)
            // The status line is at the top of a long list, so say it where the finger is too.
            android.widget.Toast.makeText(this, "$name: $text", android.widget.Toast.LENGTH_SHORT).show()
            if (r == Result.MACRO_NOT_FOUND) syncMacros()
        }
    }

    /** Silent housekeeping like [checkForUpdate]: fetches the macro list only when the tablet's version differs. */
    private fun syncMacros() {
        if (!client.isPaired || !hasPermissions()) return
        client.syncMacros(prefs.macroVersion) { result ->
            if (isDestroyed) return@syncMacros
            var changed = false
            when (result) {
                is MacroSync.Updated -> {
                    changed = prefs.macros != result.items || prefs.macrosSupported != true
                    prefs.macros = result.items
                    prefs.macroVersion = result.version
                    prefs.macrosSupported = true
                }
                MacroSync.Unchanged -> {
                    changed = prefs.macrosSupported != true
                    prefs.macrosSupported = true
                }
                MacroSync.Unsupported -> {
                    changed = prefs.macrosSupported != false
                    prefs.macrosSupported = false
                    prefs.macros = emptyList()
                    prefs.macroVersion = null
                }
                is MacroSync.Failed -> Unit
            }
            if (changed && mode == Mode.LIST) render()
        }
    }

    private fun sync() = withPermissions {
        setStatus(getString(R.string.status_syncing))
        client.syncApps { r ->
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
                render()
                if (result == Result.SUCCESS) { checkForUpdate(); syncMacros() }
            }
        })
    }

    /** HELLO + offer pickup. Silent on any problem: this is background housekeeping, not a user action. */
    private fun checkForUpdate() {
        if (!client.isPaired || !hasPermissions() || updater.isBusy) return
        val now = SystemClock.elapsedRealtime()
        if (checkStartedAt != 0L && now - checkStartedAt < CHECK_STUCK_MS) return // one at a time
        checkStartedAt = now
        client.checkForUpdate(WatchUpdater.versionCode(this)) { check ->
            checkStartedAt = 0L
            if (isDestroyed) return@checkForUpdate
            pollFailures = if (check is UpdateCheck.Failed) pollFailures + 1 else 0
            updater.handleCheck(check)
        }
    }

    /** The "Verify watch app" button: hash the installed APK and key, send them to the tablet. */
    private fun verifyApp() = withPermissions {
        setStatus(getString(R.string.status_verifying))
        updater.verifyAndReport { outcome ->
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
