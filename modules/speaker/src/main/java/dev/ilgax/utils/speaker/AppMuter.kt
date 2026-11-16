package dev.ilgax.utils.speaker

/** Which apps have a player on a built-in speaker right now. */
internal class SpeakerScan(
    /** Owners of started players routed to a speaker. */
    val uids: Set<Int>,
    /** A player is on the speaker but its owner is hidden (anonymised configuration): the app cannot be muted, only paused. */
    val unknownOwner: Boolean,
    /** Owners with a player (started or not) whose route is known to be something other than a speaker. */
    val offSpeaker: Set<Int> = emptySet(),
    /** Owners with any player at all, in any state and on any route. */
    val present: Set<Int> = emptySet(),
)

internal object SpeakerScans {
    /**
     * [speakerActive] says the speaker is where media goes now; a started player whose own route is not known yet is taken
     * to follow it. [unknownOwner] is only raised for players known to be on the speaker.
     */
    fun scan(routes: List<PlayerRoute>, speakerActive: Boolean): SpeakerScan {
        val uids = LinkedHashSet<Int>()
        val off = LinkedHashSet<Int>()
        val present = LinkedHashSet<Int>()
        var unknown = false
        for (r in routes) {
            if (r.uid >= 0) {
                present.add(r.uid)
                if (r.onSpeaker == false) off.add(r.uid) // an unknown route is not evidence of anything
            }
            if (!r.started) continue
            val onSpeaker = r.onSpeaker ?: speakerActive
            if (!onSpeaker) continue
            if (r.uid < 0) unknown = unknown || r.onSpeaker == true else uids.add(r.uid)
        }
        return SpeakerScan(uids, unknown, off, present)
    }
}

/**
 * Silences apps whose audio still reaches the speaker (Samsung "Separate app sound" sends an app there, past the routing
 * block and the volume) by setting their PLAY_AUDIO app op to ignored, and gives each one its previous mode back when the
 * switch goes OFF. What was muted, and its previous mode, is persisted before the change is made, so a crash or a missing
 * Shizuku never leaves an app muted without a record.
 *
 * Muting is sticky: an app that paused, stopped or swiped to the next video is still muted, because un-muting it would let
 * the next player start out loud on the speaker until the next scan caught it. A caught app is restored only when (a) the
 * switch goes OFF, (b) one of its players reports a known route that is not a speaker, or (c) it has had no player at all
 * for [GRACE_MS]. An unknown route is never evidence. Apps the user pre-selected ([MuteList]) are muted from the moment
 * the switch turns ON and stay muted until OFF whatever their route.
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
    /** [seen]: last time (epoch ms) a player of the uid was seen, 0 when unknown. [pinned]: from the user's list, never released by a scan. */
    data class Entry(val uid: Int, val pkg: String, val previous: Int, val seen: Long = 0L, val pinned: Boolean = false)

    class Result(val muted: List<Entry>, val restored: List<Entry>, val failures: List<String>)

    fun entries(): List<Entry> = (store.getString(K_MUTED) ?: "").lines().mapNotNull(::parse)

    fun hasEntries() = entries().isNotEmpty()

    fun isProtectedUid(uid: Int) = uid == ownUid || uid % PER_USER_RANGE < FIRST_APPLICATION_UID

    /**
     * Mutes every package in [pinned] and every uid in [wanted] that is not muted yet, and restores the muted ones whose
     * app is now known to play elsewhere ([offSpeaker]), has no player for [GRACE_MS] (see [present]) or left [pinned].
     * [now] is epoch ms. A null [ops] changes nothing.
     */
    fun reconcile(
        wanted: Set<Int>,
        ops: AppOpsBackend?,
        now: Long = 0L,
        offSpeaker: Set<Int> = emptySet(),
        present: Set<Int> = emptySet(),
        pinned: Set<String> = emptySet(),
    ): Result {
        val muted = ArrayList<Entry>()
        val restored = ArrayList<Entry>()
        val failures = ArrayList<String>()
        if (ops == null) return Result(muted, restored, failures)
        for (e in entries()) {
            if (e.pinned && e.pkg !in pinned) { // taken off the list while ON
                if (restore(e, ops, failures)) restored.add(e)
                continue
            }
            if (!e.pinned && e.pkg in pinned) { replace(e, e.copy(pinned = true)); continue }
            if (e.pinned) continue
            val seen = when {
                e.uid in wanted || e.uid in present -> now
                else -> e.seen
            }
            val release = when {
                e.uid in wanted -> false
                e.uid in offSpeaker -> true
                e.uid in present -> false
                seen > 0 && now - seen > GRACE_MS -> true
                else -> false
            }
            if (release) {
                if (restore(e, ops, failures)) restored.add(e)
            } else if (now > 0 && (e.seen == 0L || seen - e.seen >= SEEN_WRITE_MS)) {
                replace(e, e.copy(seen = if (seen == 0L) now else seen)) // first sighting of a legacy record, or a refresh
            }
        }
        for (pkg in pinned) {
            if (pkg in PROTECTED_PACKAGES) continue
            val uid = try { ops.uidForPackage(pkg) } catch (t: Throwable) { failures.add("uid of $pkg: ${t.message}"); null } ?: continue
            if (isProtectedUid(uid)) continue
            mute(uid, pkg, now, true, ops, muted, failures)
        }
        for (uid in wanted) {
            if (isProtectedUid(uid)) continue
            val packages = try { ops.packagesForUid(uid) } catch (t: Throwable) { failures.add("packages of uid $uid: ${t.message}"); continue }
            for (pkg in packages) {
                if (pkg in PROTECTED_PACKAGES) continue
                mute(uid, pkg, now, false, ops, muted, failures)
            }
        }
        return Result(muted, restored, failures)
    }

    private fun mute(uid: Int, pkg: String, now: Long, pinned: Boolean, ops: AppOpsBackend, muted: MutableList<Entry>, failures: MutableList<String>) {
        if (entries().any { it.uid == uid && it.pkg == pkg }) return
        try {
            val previous = ops.mode(uid, pkg)
            if (previous == MODE_IGNORED) return // already silent by the user's own doing: leave it alone
            val entry = Entry(uid, pkg, previous, now, pinned)
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
    private fun replace(old: Entry, new: Entry) = write(entries().map { if (it.uid == old.uid && it.pkg == old.pkg) new else it })

    private fun write(list: List<Entry>) {
        if (list.isEmpty()) store.remove(K_MUTED) else store.putString(K_MUTED, list.joinToString("\n") { "${it.uid}|${it.previous}|${it.pkg}|${it.seen}|${if (it.pinned) 1 else 0}" })
    }

    private fun parse(line: String): Entry? {
        // "uid|previous|pkg" (up to 0.8.0) or "uid|previous|pkg|seen|pinned"; package names never contain '|'.
        val parts = line.split('|')
        if (parts.size != 3 && parts.size != 5) return null
        val uid = parts[0].toIntOrNull() ?: return null
        val previous = parts[1].toIntOrNull() ?: return null
        if (parts.size == 3) return Entry(uid, parts[2], previous)
        return Entry(uid, parts[2], previous, parts[3].toLongOrNull() ?: 0L, parts[4] == "1")
    }

    companion object {
        const val K_MUTED = "muted_apps"
        const val OP_PLAY_AUDIO = 28 // AppOpsManager.OP_PLAY_AUDIO; looked up by name at run time, this is the fallback
        const val MODE_IGNORED = 1
        /** An app with no player at all for this long is let go. Only checked on a scan, and only while it has no player. */
        const val GRACE_MS = 10 * 60 * 1000L
        /** The last-seen time is rewritten at most this often, so a busy player does not rewrite the store on every callback. */
        const val SEEN_WRITE_MS = 60 * 1000L
        const val FIRST_APPLICATION_UID = 10000
        const val PER_USER_RANGE = 100000
        val PROTECTED_PACKAGES = setOf("android", "com.android.systemui")
    }
}

/** The user's "Mute on speaker" list, as package names, one per line. */
internal class MuteList(private val store: KeyValueStore) {
    fun packages(): Set<String> = (store.getString(K_MUTE_LIST) ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

    fun add(pkg: String) = write(packages() + pkg)
    fun remove(pkg: String) = write(packages() - pkg)

    private fun write(set: Set<String>) {
        if (set.isEmpty()) store.remove(K_MUTE_LIST) else store.putString(K_MUTE_LIST, set.joinToString("\n"))
    }

    companion object { const val K_MUTE_LIST = "mute_on_speaker" }
}
