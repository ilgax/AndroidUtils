package dev.ilgax.utils.wear

import android.content.Context
import dev.ilgax.utils.protocol.AppSlots
import dev.ilgax.utils.protocol.HostBook
import dev.ilgax.utils.protocol.MacroItem

class WatchPrefs private constructor(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tabremote", Context.MODE_PRIVATE)

    /** Forces the preferences file to load; call off the main thread. */
    fun preload() { sp.all }

    @Volatile private var book: HostBook? = null

    /** The paired hosts. The first start after the update turns the single stored tablet into entry #1 (same Keystore alias). */
    val hosts: HostBook
        @Synchronized get() = book ?: run {
            val migrated = HostBook.migrate(sp.getString(KEY_HOSTS, null), sp.getString(KEY_ID, null))
            if (!sp.contains(KEY_HOSTS)) {
                val e = sp.edit().putString(KEY_HOSTS, migrated.encode())
                // The old single-tablet caches move to entry #1.
                migrated.entries.firstOrNull()?.let { first ->
                    sp.getString(KEY_SLOTS, null)?.let { e.putString(KEY_SLOTS + first.id, it) }
                    sp.getString(KEY_MACROS, null)?.let { e.putString(KEY_MACROS + first.id, it) }
                    if (sp.contains(KEY_MACRO_VERSION)) e.putInt(KEY_MACRO_VERSION + first.id, sp.getInt(KEY_MACRO_VERSION, 0))
                    if (sp.contains(KEY_MACROS_SUPPORTED)) e.putBoolean(KEY_MACROS_SUPPORTED + first.id, sp.getBoolean(KEY_MACROS_SUPPORTED, false))
                    e.putInt(KEY_VERIFIED_VERSION + first.id, sp.getInt(KEY_VERIFIED_VERSION, -1))
                }
                e.remove(KEY_ID).remove(KEY_SLOTS).remove(KEY_MACROS).remove(KEY_MACRO_VERSION).remove(KEY_MACROS_SUPPORTED)
                e.apply()
            }
            migrated.also { book = it }
        }

    @Synchronized
    fun updateHosts(change: (HostBook) -> HostBook) {
        val next = change(hosts)
        book = next
        sp.edit().putString(KEY_HOSTS, next.encode()).apply()
    }

    /** The host the user picked last (hex id); null = never picked. */
    var selectedHost: String?
        get() = sp.getString(KEY_SELECTED, null)
        set(v) = sp.edit().apply { if (v == null) remove(KEY_SELECTED) else putString(KEY_SELECTED, v) }.apply()

    /** Labels of a host's app slots from the last sync; empty string = unset. */
    fun slotLabels(host: String): List<String> =
        AppSlots.decode(sp.getString(KEY_SLOTS + host, "")?.toByteArray(Charsets.UTF_8))

    fun setSlotLabels(host: String, v: List<String>) =
        sp.edit().putString(KEY_SLOTS + host, String(AppSlots.encode(v), Charsets.UTF_8)).apply()

    /** When on, slot buttons send SWITCH_APP (close the current app, then open) instead of OPEN_APP. */
    var closeFirst: Boolean
        get() = sp.getBoolean(KEY_CLOSE_FIRST, false)
        set(v) = sp.edit().putBoolean(KEY_CLOSE_FIRST, v).apply()

    /** A host's macro list from the last sync, shown instantly (also offline). */
    fun macros(host: String): List<MacroItem> = (sp.getString(KEY_MACROS + host, "") ?: "").split('\n').mapNotNull { line ->
        val tab = line.indexOf('\t')
        val id = if (tab > 0) line.substring(0, tab).toIntOrNull() else null
        if (id == null) null else MacroItem(id, line.substring(tab + 1))
    }

    fun setMacros(host: String, v: List<MacroItem>) = sp.edit()
        .putString(KEY_MACROS + host, v.joinToString("\n") { "${it.id}\t${it.name.replace('\n', ' ').replace('\t', ' ')}" }).apply()

    /** Version of [macros] as the host reported it; null = never synced. */
    fun macroVersion(host: String): Int? = if (sp.contains(KEY_MACRO_VERSION + host)) sp.getInt(KEY_MACRO_VERSION + host, 0) else null

    fun setMacroVersion(host: String, v: Int?) =
        sp.edit().apply { if (v == null) remove(KEY_MACRO_VERSION + host) else putInt(KEY_MACRO_VERSION + host, v) }.apply()

    /** True once the host answered a macro sync, false if its Utils predates macros, null = not known yet. */
    fun macrosSupported(host: String): Boolean? =
        if (sp.contains(KEY_MACROS_SUPPORTED + host)) sp.getBoolean(KEY_MACROS_SUPPORTED + host, false) else null

    fun setMacrosSupported(host: String, v: Boolean?) =
        sp.edit().apply { if (v == null) remove(KEY_MACROS_SUPPORTED + host) else putBoolean(KEY_MACROS_SUPPORTED + host, v) }.apply()

    /** versionCode last reported to a host with an installed-binary report; -1 = never. */
    fun verifiedVersion(host: String): Int = sp.getInt(KEY_VERIFIED_VERSION + host, -1)

    fun setVerifiedVersion(host: String, v: Int) = sp.edit().putInt(KEY_VERIFIED_VERSION + host, v).apply()

    /** The notification permission prompt is shown once; after that the user's answer stands. */
    var askedNotifications: Boolean
        get() = sp.getBoolean(KEY_ASKED_NOTIF, false)
        set(v) = sp.edit().putBoolean(KEY_ASKED_NOTIF, v).apply()

    /** Forgets a host: its entry, its caches and (if it was picked) the selection. The caller deletes the Keystore key. */
    fun forgetHost(id: String) {
        updateHosts { it.remove(id) }
        sp.edit().remove(KEY_SLOTS + id).remove(KEY_MACROS + id).remove(KEY_MACRO_VERSION + id)
            .remove(KEY_MACROS_SUPPORTED + id).remove(KEY_VERIFIED_VERSION + id).apply()
        if (selectedHost == id) selectedHost = null
    }

    companion object {
        @Volatile private var instance: WatchPrefs? = null

        fun get(context: Context): WatchPrefs =
            instance ?: synchronized(this) { instance ?: WatchPrefs(context).also { instance = it } }

        private const val KEY_ID = "tablet_id"
        private const val KEY_HOSTS = "hosts"
        private const val KEY_SELECTED = "selected_host"
        private const val KEY_SLOTS = "slots"
        private const val KEY_CLOSE_FIRST = "close_first"
        private const val KEY_MACROS = "macros"
        private const val KEY_MACRO_VERSION = "macro_version"
        private const val KEY_MACROS_SUPPORTED = "macros_supported"
        private const val KEY_VERIFIED_VERSION = "verified_version"
        private const val KEY_ASKED_NOTIF = "asked_notifications"
    }
}
