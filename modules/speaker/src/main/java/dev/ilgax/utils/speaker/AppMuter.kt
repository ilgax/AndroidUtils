package dev.ilgax.utils.speaker

/** Which apps have a player on a built-in speaker right now. */
internal class SpeakerScan(
    /** Owners of started players routed to a speaker. */
    val uids: Set<Int>,
    /** A player is on the speaker but its owner is hidden (anonymised configuration): the app cannot be muted, only paused. */
    val unknownOwner: Boolean,
)

internal object SpeakerScans {
    /**
     * [speakerActive] says the speaker is where media goes now; a started player whose own route is not known yet is taken
     * to follow it. [unknownOwner] is only raised for players known to be on the speaker.
     */
    fun scan(routes: List<PlayerRoute>, speakerActive: Boolean): SpeakerScan {
        val uids = LinkedHashSet<Int>()
        var unknown = false
        for (r in routes) {
            if (!r.started) continue
            val onSpeaker = r.onSpeaker ?: speakerActive
            if (!onSpeaker) continue
            if (r.uid < 0) unknown = unknown || r.onSpeaker == true else uids.add(r.uid)
        }
        return SpeakerScan(uids, unknown)
    }
}

/**
 * Silences apps whose audio still reaches the speaker (Samsung "Separate app sound" sends an app there, past the routing
 * block and the volume) by setting their PLAY_AUDIO app op to ignored, and gives each one its previous mode back when its
 * players leave the speaker or the switch goes OFF. What was muted, and its previous mode, is persisted before the change
 * is made, so a crash or a missing Shizuku never leaves an app muted without a record.
 *
 * System sounds: anything below the app uid range (system_server is 1000, media and audio servers, root, shell), this
 * app and SystemUI are never muted. SystemUI's and the system's sounds go through the zeroed streams instead; muting
 * them would also silence what the user must still see announced on screen.
 */
internal class AppMuter(
    private val store: KeyValueStore,
    private val ownUid: Int,
    private val opCode: Int = OP_PLAY_AUDIO,
) {
    data class Entry(val uid: Int, val pkg: String, val previous: Int)

    class Result(val muted: List<Entry>, val restored: List<Entry>, val failures: List<String>)

    fun entries(): List<Entry> = (store.getString(K_MUTED) ?: "").lines().mapNotNull(::parse)

    fun hasEntries() = entries().isNotEmpty()

    fun isProtectedUid(uid: Int) = uid == ownUid || uid % PER_USER_RANGE < FIRST_APPLICATION_UID

    /** Mutes every uid in [wanted] that is not muted yet and restores every muted uid that is not in it. A null [ops] changes nothing. */
    fun reconcile(wanted: Set<Int>, ops: AppOpsBackend?): Result {
        val muted = ArrayList<Entry>()
        val restored = ArrayList<Entry>()
        val failures = ArrayList<String>()
        if (ops == null) return Result(muted, restored, failures)
        for (e in entries()) {
            if (e.uid in wanted) continue
            if (restore(e, ops, failures)) restored.add(e)
        }
        for (uid in wanted) {
            if (isProtectedUid(uid)) continue
            val packages = try { ops.packagesForUid(uid) } catch (t: Throwable) { failures.add("packages of uid $uid: ${t.message}"); continue }
            for (pkg in packages) {
                if (pkg in PROTECTED_PACKAGES) continue
                if (entries().any { it.uid == uid && it.pkg == pkg }) continue
                try {
                    val previous = ops.mode(uid, pkg)
                    if (previous == MODE_IGNORED) continue // already silent by the user's own doing: leave it alone
                    val entry = Entry(uid, pkg, previous)
                    add(entry)
                    try {
                        ops.setMode(uid, pkg, MODE_IGNORED)
                        muted.add(entry)
                    } catch (t: Throwable) {
                        remove(entry)
                        throw t
                    }
                } catch (t: Throwable) {
                    failures.add("mute $pkg (uid $uid): ${t.javaClass.simpleName} ${t.message}")
                }
            }
        }
        return Result(muted, restored, failures)
    }

    /** Restores everything. True when nothing is left (false while Shizuku is missing or a restore failed). */
    fun releaseAll(ops: AppOpsBackend?): Result {
        val restored = ArrayList<Entry>()
        val failures = ArrayList<String>()
        if (ops != null) for (e in entries()) if (restore(e, ops, failures)) restored.add(e)
        return Result(emptyList(), restored, failures)
    }

    /** Puts [e] back. If the op is no longer ignored someone else changed it meanwhile: that choice wins and the record is dropped. */
    private fun restore(e: Entry, ops: AppOpsBackend, failures: MutableList<String>): Boolean = try {
        if (ops.mode(e.uid, e.pkg) == MODE_IGNORED) ops.setMode(e.uid, e.pkg, e.previous)
        remove(e)
        true
    } catch (t: Throwable) {
        failures.add("restore ${e.pkg} (uid ${e.uid}): ${t.javaClass.simpleName} ${t.message}")
        false
    }

    private fun add(e: Entry) = write(entries() + e)
    private fun remove(e: Entry) = write(entries().filterNot { it.uid == e.uid && it.pkg == e.pkg })

    private fun write(list: List<Entry>) {
        if (list.isEmpty()) store.remove(K_MUTED) else store.putString(K_MUTED, list.joinToString("\n") { "${it.uid}|${it.previous}|${it.pkg}" })
    }

    private fun parse(line: String): Entry? {
        val parts = line.split('|', limit = 3)
        if (parts.size != 3) return null
        return Entry(parts[0].toIntOrNull() ?: return null, parts[2], parts[1].toIntOrNull() ?: return null)
    }

    companion object {
        const val K_MUTED = "muted_apps"
        const val OP_PLAY_AUDIO = 28 // AppOpsManager.OP_PLAY_AUDIO; looked up by name at run time, this is the fallback
        const val MODE_IGNORED = 1
        const val FIRST_APPLICATION_UID = 10000
        const val PER_USER_RANGE = 100000
        val PROTECTED_PACKAGES = setOf("android", "com.android.systemui")
    }
}
