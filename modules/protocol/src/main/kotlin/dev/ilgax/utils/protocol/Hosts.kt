package dev.ilgax.utils.protocol

import java.security.MessageDigest

/** Phone or tablet, as the host reports it (smallestScreenWidthDp >= 600 is a tablet). UNKNOWN: an old host, nothing was sent. */
enum class DeviceClass(val code: Int) {
    UNKNOWN(0), PHONE(1), TABLET(2);

    companion object {
        fun fromCode(code: Int): DeviceClass = entries.firstOrNull { it.code == code } ?: UNKNOWN
        fun fromSmallestWidthDp(dp: Int): DeviceClass = if (dp >= 600) TABLET else PHONE
    }
}

/** What a host says about itself: a display name for the watch's lists and its Bluetooth name (for native-phone matching). */
class HostInfo(val displayName: String, val btName: String?, val deviceClass: DeviceClass) {
    override fun equals(other: Any?): Boolean = other is HostInfo && displayName == other.displayName &&
        btName == other.btName && deviceClass == other.deviceClass

    override fun hashCode(): Int = displayName.hashCode() * 31 + deviceClass.code

    companion object {
        /** Settings.Global DEVICE_NAME, else the Bluetooth name, else the model. */
        fun pickName(deviceName: String?, btName: String?, model: String?): String =
            listOf(deviceName, btName, model).firstNotNullOfOrNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } } ?: "Device"
    }
}

/**
 * INFO characteristic payload: `format(1) | class(1) | nameLen(1) | name | btLen(1) | btName | tag(16)`, UTF-8, each name at most
 * [MAX_NAME] bytes (cut on a character boundary). tag = HMAC-SHA256(key, "inf" | nonce | everything before the tag), 16 bytes,
 * bound to the nonce of the GET_INFO frame that granted the read.
 */
object InfoFrame {
    const val FORMAT = 1
    const val MAX_NAME = 40
    private val PREFIX = "inf".toByteArray(Charsets.US_ASCII)

    sealed interface Result {
        class Valid(val info: HostInfo) : Result
        data object Invalid : Result
    }

    fun encode(mac: (ByteArray) -> ByteArray, nonce: ByteArray, info: HostInfo): ByteArray {
        require(nonce.size == Protocol.NONCE_SIZE) { "bad nonce" }
        val name = clip(info.displayName)
        val bt = clip(info.btName ?: "")
        val body = byteArrayOf(FORMAT.toByte(), info.deviceClass.code.toByte(), name.size.toByte()) + name +
            byteArrayOf(bt.size.toByte()) + bt
        return body + tag(mac, nonce, body)
    }

    /** Authenticates before parsing; [Result.Invalid] for anything malformed or not authentic. */
    fun verify(mac: (ByteArray) -> ByteArray, nonce: ByteArray, frame: ByteArray): Result {
        if (nonce.size != Protocol.NONCE_SIZE || frame.size < 4 + Protocol.TAG_SIZE) return Result.Invalid
        val body = frame.copyOfRange(0, frame.size - Protocol.TAG_SIZE)
        val given = frame.copyOfRange(frame.size - Protocol.TAG_SIZE, frame.size)
        if (!MessageDigest.isEqual(tag(mac, nonce, body), given)) return Result.Invalid
        if (body[0].toInt() != FORMAT) return Result.Invalid
        val nameLen = body[2].toInt() and 0xFF
        if (body.size < 4 + nameLen) return Result.Invalid
        val btLen = body[3 + nameLen].toInt() and 0xFF
        if (body.size != 4 + nameLen + btLen) return Result.Invalid
        val name = String(body, 3, nameLen, Charsets.UTF_8)
        val bt = String(body, 4 + nameLen, btLen, Charsets.UTF_8)
        return Result.Valid(HostInfo(name, bt.ifEmpty { null }, DeviceClass.fromCode(body[1].toInt() and 0xFF)))
    }

    private fun clip(s: String): ByteArray {
        var out = s.trim().replace('\n', ' ').replace('\t', ' ')
        while (out.toByteArray(Charsets.UTF_8).size > MAX_NAME) out = out.dropLast(1)
        return out.toByteArray(Charsets.UTF_8)
    }

    private fun tag(mac: (ByteArray) -> ByteArray, nonce: ByteArray, body: ByteArray): ByteArray =
        mac(PREFIX + nonce + body).copyOf(Protocol.TAG_SIZE)
}

/** Timing of the "who can I hear" scanning. Pure constants and decisions. */
object Reach {
    /** A host is dropped from the reachable set after this long without a beacon (hosts beacon about once a second). */
    const val AGE_OUT_MS = 5_000L

    /** The short low-latency scan when the app opens or the tile comes into view. */
    const val BURST_MS = 1_500L

    /** A burst result younger than this is reused: reopening is instant and does not scan again. */
    const val CACHE_MS = 4_000L

    /**
     * Sustained scan while the app is visible. SCAN_MODE_LOW_POWER listens about 0.5 s of every 5 s, so with a beacon per second it
     * can miss a host for more than [AGE_OUT_MS]; BALANCED listens about 1 s of every 4 s, which hears a once-a-second beacon every
     * window (longest silence about 4 s). The scanner's hardware filter keeps the watch CPU asleep for every other advertiser.
     */
    const val SUSTAINED_BALANCED = true

    fun needsBurst(lastBurstEndedAt: Long?, now: Long): Boolean =
        lastBurstEndedAt == null || now - lastBurstEndedAt !in 0 until CACHE_MS
}

/** The hosts heard recently, in the order they were first heard (stable while they stay audible). Ids are lowercase hex. */
class Reachability(private val ageOutMs: Long = Reach.AGE_OUT_MS) {
    private val lastHeard = LinkedHashMap<String, Long>()

    /** Records a beacon. Returns true when the host was not in the set (never heard, or aged out) before. */
    @Synchronized
    fun hear(id: String, now: Long): Boolean {
        val wasHeard = lastHeard[id]?.let { now - it in 0 until ageOutMs } ?: false
        if (!wasHeard) lastHeard.remove(id) // re-enters at the end of the order
        lastHeard[id] = now
        return !wasHeard
    }

    /** Heard within the age-out window; [pinned] hosts (a live connection) count as heard whatever their beacon does. */
    @Synchronized
    fun heard(now: Long, pinned: String? = null): List<String> {
        lastHeard.entries.removeAll { now - it.value >= ageOutMs && it.key != pinned }
        val out = lastHeard.keys.toMutableList()
        if (pinned != null && pinned !in out) out += pinned
        return out
    }

    @Synchronized
    fun clear() = lastHeard.clear()
}

/** What the target selection shows. Names are resolved by the caller from the ids. */
sealed interface TargetState {
    /** Nothing audible: "No device in range". */
    data object NoneInRange : TargetState

    /** One host to talk to; header only, no dropdown. */
    data class Single(val id: String) : TargetState

    /** Several heard: [id] is the selected one, [heard] the picker's choices. */
    data class Choice(val id: String, val heard: List<String>) : TargetState

    /** The picked host [id] is silent while others are heard: "<name> not in range" plus the picker; nothing is sent anywhere. */
    data class NotInRange(val id: String, val heard: List<String>) : TargetState

    /** The host commands go to; null when there is none to talk to. */
    val targetId: String? get() = when (this) {
        is Single -> id
        is Choice -> id
        else -> null
    }
}

/** [remember]: a selection the caller must store (only ever the default for "several heard, nothing picked yet"). */
class Resolution(val state: TargetState, val remember: String? = null)

object TargetSelection {
    /**
     * The in-app rules. Never switches by itself: [picked] (last manual pick) wins whenever it is heard; when it is silent and
     * another host is heard the picker is offered with "not in range". With nothing ever picked, one heard host is the target and
     * several heard default to the first heard, which is remembered so it stays put afterwards.
     */
    fun resolve(picked: String?, heard: List<String>, paired: Set<String>): Resolution {
        val h = heard.filter { it in paired }.distinct()
        val pick = picked?.takeIf { it in paired }
        return when {
            h.isEmpty() -> Resolution(TargetState.NoneInRange)
            pick != null && pick !in h -> Resolution(TargetState.NotInRange(pick, h))
            pick != null -> Resolution(if (h.size == 1) TargetState.Single(pick) else TargetState.Choice(pick, h))
            h.size == 1 -> Resolution(TargetState.Single(h[0]))
            else -> Resolution(TargetState.Choice(h[0], h), remember = h[0])
        }
    }

    /**
     * The tile: one heard host is the target; several heard mean the last picked, and when that one is silent the tile shows the
     * not-in-range state (it cannot host a picker). Nothing picked yet: the first heard. Never remembers anything.
     */
    fun tile(picked: String?, heard: List<String>, paired: Set<String>): TargetState {
        val h = heard.filter { it in paired }.distinct()
        val pick = picked?.takeIf { it in paired }
        return when {
            h.isEmpty() -> TargetState.NoneInRange
            h.size == 1 -> TargetState.Single(h[0])
            pick == null -> TargetState.Single(h[0])
            pick in h -> TargetState.Single(pick)
            else -> TargetState.NotInRange(pick, h)
        }
    }
}

/** A classic-Bluetooth device bonded to the watch. [majorClass] is BluetoothClass.getMajorDeviceClass(), -1 when unknown. */
class BondedDevice(val name: String?, val majorClass: Int)

/** Is a host the phone this watch is natively paired to (Galaxy Wearable)? Only then the built-in controls are hidden. */
object NativePhone {
    const val MAJOR_PHONE = 0x0200

    /**
     * [override] (the manual toggle) wins. Otherwise the host must say it is not a tablet and give its Bluetooth name, and a bonded
     * phone-class device must carry exactly that name. Anything unknown means false: nothing gets hidden on a guess.
     */
    fun isNative(btName: String?, hostClass: DeviceClass, bonded: List<BondedDevice>, override: Boolean?): Boolean {
        if (override != null) return override
        if (hostClass == DeviceClass.TABLET) return false
        val name = btName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return bonded.any { it.majorClass == MAJOR_PHONE && it.name?.trim().equals(name, ignoreCase = true) }
    }
}

/** What the watch offers for a target. The built-in watch-phone link already covers media, volume and Find My Phone. */
object HostFeatures {
    private val BUILT_IN = setOf(
        Command.PLAY_PAUSE, Command.VOLUME_UP, Command.VOLUME_DOWN, Command.NEXT, Command.PREVIOUS, Command.RING,
    )

    fun available(command: Command, nativePhone: Boolean): Boolean = !(nativePhone && command in BUILT_IN)

    fun nowPlaying(nativePhone: Boolean): Boolean = !nativePhone
}

/** A paired host as the watch stores it. [alias] is the Keystore alias of its HMAC key. */
data class HostEntry(
    val id: String,
    val alias: String,
    val name: String,
    val customName: Boolean = false,
    val deviceClass: DeviceClass = DeviceClass.UNKNOWN,
    val btName: String? = null,
    val nativeAuto: Boolean = false,
    /** The manual toggle: null = follow [nativeAuto]. */
    val nativeOverride: Boolean? = null,
    /** When the INFO was last asked (epoch ms), 0 = never. Set after an answer or an "unsupported". */
    val infoAt: Long = 0,
) {
    val isNative: Boolean get() = nativeOverride ?: nativeAuto
}

/** The watch's paired hosts. Immutable; every change returns a new book. */
class HostBook(val entries: List<HostEntry> = emptyList()) {
    fun get(id: String): HostEntry? = entries.firstOrNull { it.id == id }
    val ids: Set<String> get() = entries.map { it.id }.toSet()
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Pairing: a new host is added with its own alias; a known id keeps everything (name, toggle, alias) and only its key is replaced
     * by the caller, in the entry's alias.
     */
    fun paired(id: String): HostBook =
        if (get(id) != null) this else HostBook(entries + HostEntry(id, aliasFor(id), DEFAULT_NAME))

    fun remove(id: String) = HostBook(entries.filterNot { it.id == id })

    fun rename(id: String, name: String): HostBook {
        val clean = name.trim().replace('\n', ' ').replace('\t', ' ').take(MAX_NAME)
        return update(id) { if (clean.isEmpty()) it.copy(customName = false) else it.copy(name = clean, customName = true) }
    }

    fun setNativeOverride(id: String, value: Boolean?) = update(id) { it.copy(nativeOverride = value) }

    /** A host's answer: its default name is taken unless the user renamed it; [nativeAuto] is the detection result. */
    fun applyInfo(id: String, info: HostInfo, nativeAuto: Boolean, now: Long) = update(id) {
        it.copy(
            name = if (it.customName) it.name else info.displayName.take(MAX_NAME).ifEmpty { it.name },
            deviceClass = info.deviceClass, btName = info.btName, nativeAuto = nativeAuto, infoAt = now,
        )
    }

    /** The host has no INFO (old Utils): nothing becomes known, and it is not asked again for a while. */
    fun infoUnsupported(id: String, now: Long) = update(id) { it.copy(infoAt = now) }

    fun withNativeAuto(id: String, nativeAuto: Boolean) = update(id) { it.copy(nativeAuto = nativeAuto) }

    private fun update(id: String, f: (HostEntry) -> HostEntry) =
        if (get(id) == null) this else HostBook(entries.map { if (it.id == id) f(it) else it })

    fun encode(): String = entries.joinToString("\n") { e ->
        listOf(
            e.id, e.alias, clean(e.name), if (e.customName) "1" else "0", e.deviceClass.code.toString(), clean(e.btName ?: ""),
            if (e.nativeAuto) "1" else "0", when (e.nativeOverride) { true -> "1"; false -> "0"; null -> "" }, e.infoAt.toString(),
        ).joinToString("\t")
    }

    companion object {
        const val LEGACY_ALIAS = "tabremote-hmac-watch"
        const val DEFAULT_NAME = "Device"
        const val MAX_NAME = 40

        fun aliasFor(id: String) = "$LEGACY_ALIAS-$id"

        private fun clean(s: String) = s.replace('\n', ' ').replace('\t', ' ')

        fun decode(text: String?): HostBook = HostBook(
            (text ?: "").lines().mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 9 || f[0].length != Protocol.TABLET_ID_SIZE * 2 || f[1].isEmpty()) return@mapNotNull null
                HostEntry(
                    id = f[0], alias = f[1], name = f[2], customName = f[3] == "1",
                    deviceClass = DeviceClass.fromCode(f[4].toIntOrNull() ?: 0), btName = f[5].ifEmpty { null },
                    nativeAuto = f[6] == "1", nativeOverride = when (f[7]) { "1" -> true; "0" -> false; else -> null },
                    infoAt = f[8].toLongOrNull() ?: 0,
                )
            }.distinctBy { it.id },
        )

        /**
         * First start after the update: the single stored tablet id becomes entry #1, keeping the old Keystore alias (a Keystore key
         * cannot be exported, so it is not copied). Does nothing when a book exists already or nothing was paired.
         */
        fun migrate(storedBook: String?, legacyId: String?): HostBook {
            if (storedBook != null) return decode(storedBook)
            if (legacyId == null || legacyId.length != Protocol.TABLET_ID_SIZE * 2) return HostBook()
            return HostBook(listOf(HostEntry(legacyId, LEGACY_ALIAS, "Tablet")))
        }

        fun hex(id: ByteArray): String = id.joinToString("") { "%02x".format(it) }

        fun idBytes(hex: String): ByteArray? =
            if (hex.length != Protocol.TABLET_ID_SIZE * 2) null else try {
                ByteArray(Protocol.TABLET_ID_SIZE) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } catch (_: NumberFormatException) {
                null
            }
    }
}

/** Which hosts the watch asks about updates, and when. */
object UpdateSources {
    /** Re-asking a host that is not the selected one is rare: it costs a connection switch. */
    const val OTHER_RECHECK_MS = 10 * 60_000L

    /** INFO is asked at most this often per host. */
    const val INFO_REFRESH_MS = 12 * 60 * 60_000L

    fun infoDue(infoAt: Long, now: Long): Boolean = infoAt == 0L || now - infoAt !in 0 until INFO_REFRESH_MS

    /** The selected host first, then the other heard ones; [lastChecked] (epoch ms per id) keeps the others from being asked again soon. */
    fun order(selected: String?, heard: List<String>, lastChecked: Map<String, Long>, now: Long): List<String> {
        val out = ArrayList<String>()
        if (selected != null && selected in heard) out += selected
        for (id in heard) {
            if (id == selected) continue
            val at = lastChecked[id]
            if (at == null || now - at !in 0 until OTHER_RECHECK_MS) out += id
        }
        return out
    }
}
