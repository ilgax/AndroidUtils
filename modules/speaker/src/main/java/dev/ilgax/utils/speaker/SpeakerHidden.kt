package dev.ilgax.utils.speaker

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Reflection bindings plus the shell-wrapped binders: IAudioService (routing, per-device volume, playback configurations,
 * ringer mode, fake wired headset), IAppOpsService (PLAY_AUDIO mode of other apps) and IPackageManager (package names of a
 * uid, unfiltered, which a plain app cannot see without QUERY_ALL_PACKAGES). Built once per Shizuku binder.
 *
 * Methods that changed between Android releases are found by name and their parameter types, and the signature that was
 * actually used is kept in [signatures] for the diagnostics.
 */
internal class Hidden {
    val service: Any
    /** name -> the signature that was bound, for the diagnostics. */
    val signatures = LinkedHashMap<String, String>()

    private val cAda: Class<*>
    private val adaCtor: Constructor<*>
    private val cVi: Class<*>
    private val viBuilderCtor: Constructor<*>
    private val bSetIndex: Method
    private val bBuild: Method
    private val viGetIndex: Method
    private val mSetNonDefault: Method
    private val mRemoveNonDefault: Method
    private val mGetStrategies: Method
    private val mSetDevVol: Method
    private val mGetDevVol: Method
    private val strategyGetId: Method
    private val mActivePlayback: Method?
    private val mSetRinger: Method?
    private val mSetWired: Method?

    private val appOps: Any?
    private val mOpSetMode: Method?
    private val mOpCheck: Method?
    private val packageManager: Any?
    private val mPackagesForUid: Method?
    val opCode: Int

    init {
        HiddenApiBypass.addHiddenApiExemptions("L")
        val cService = Class.forName("android.media.IAudioService")
        val cStub = Class.forName("android.media.IAudioService\$Stub")
        cAda = Class.forName("android.media.AudioDeviceAttributes")
        adaCtor = cAda.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
        cVi = Class.forName("android.media.VolumeInfo")
        val cViB = Class.forName("android.media.VolumeInfo\$Builder")
        viBuilderCtor = cViB.getConstructor(Int::class.javaPrimitiveType)
        bSetIndex = cViB.getMethod("setVolumeIndex", Int::class.javaPrimitiveType)
        bBuild = cViB.getMethod("build")
        viGetIndex = cVi.getMethod("getVolumeIndex")
        val i = Int::class.javaPrimitiveType
        mSetNonDefault = cService.getMethod("setDeviceAsNonDefaultForStrategy", i, cAda)
        mRemoveNonDefault = cService.getMethod("removeDeviceAsNonDefaultForStrategy", i, cAda)
        mGetStrategies = cService.getMethod("getAudioProductStrategies")
        mSetDevVol = cService.getMethod("setDeviceVolume", cVi, cAda, String::class.java)
        mGetDevVol = cService.getMethod("getDeviceVolume", cVi, cAda, String::class.java)
        strategyGetId = Class.forName("android.media.audiopolicy.AudioProductStrategy").getMethod("getId")
        mActivePlayback = optional(cService, "getActivePlaybackConfigurations")
        mSetRinger = optional(cService, "setRingerModeExternal", i, String::class.java)
        mSetWired = optional(cService, "setWiredDeviceConnectionState")
        val audio: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("audio"))
        service = cStub.getMethod("asInterface", IBinder::class.java).invoke(null, audio)!!

        var ops: Any? = null
        var setMode: Method? = null
        var check: Method? = null
        try {
            val cOps = Class.forName("com.android.internal.app.IAppOpsService")
            val stub = Class.forName("com.android.internal.app.IAppOpsService\$Stub")
            setMode = cOps.getMethod("setMode", i, i, String::class.java, i)
            signatures["IAppOpsService.setMode"] = setMode.toGenericString()
            check = optional(cOps, "checkOperationRaw") ?: optional(cOps, "checkOperation")
            ops = stub.getMethod("asInterface", IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(SystemServiceHelper.getSystemService("appops")))
        } catch (t: Throwable) {
            signatures["IAppOpsService"] = "unavailable: ${root(t).javaClass.simpleName} ${root(t).message}"
        }
        appOps = ops; mOpSetMode = setMode; mOpCheck = check

        var pm: Any? = null
        var pkgs: Method? = null
        try {
            val cPm = Class.forName("android.content.pm.IPackageManager")
            pkgs = cPm.getMethod("getPackagesForUid", i)
            pm = Class.forName("android.content.pm.IPackageManager\$Stub").getMethod("asInterface", IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package")))
        } catch (t: Throwable) {
            signatures["IPackageManager"] = "unavailable: ${root(t).javaClass.simpleName} ${root(t).message}"
        }
        packageManager = pm; mPackagesForUid = pkgs

        opCode = try {
            Class.forName("android.app.AppOpsManager").getMethod("strOpToOp", String::class.java).invoke(null, "android:play_audio") as Int
        } catch (t: Throwable) {
            AppMuter.OP_PLAY_AUDIO
        }
    }

    private fun optional(cls: Class<*>, name: String, vararg params: Class<*>?): Method? {
        val exact = try { if (params.isNotEmpty()) cls.getMethod(name, *params) else null } catch (_: NoSuchMethodException) { null }
        val m = exact ?: cls.methods.firstOrNull { it.name == name }
        if (m != null) signatures["${cls.simpleName}.$name"] = m.toGenericString()
        else signatures["${cls.simpleName}.$name"] = "NOT FOUND"
        return m
    }

    private fun root(t: Throwable): Throwable = (t as? InvocationTargetException)?.targetException ?: t

    fun ada(type: Int, address: String): Any = adaCtor.newInstance(ROLE_OUTPUT, type, address)
    fun strategyIds(): List<Int> = (mGetStrategies.invoke(service) as List<*>).map { strategyGetId.invoke(it) as Int }
    fun setNonDefault(strategy: Int, ada: Any) = mSetNonDefault.invoke(service, strategy, ada) as Int
    fun removeNonDefault(strategy: Int, ada: Any) = mRemoveNonDefault.invoke(service, strategy, ada) as Int

    private fun vi(stream: Int, index: Int): Any =
        bBuild.invoke(viBuilderCtor.newInstance(stream).also { bSetIndex.invoke(it, index) })!!

    fun setDeviceVolume(stream: Int, ada: Any, index: Int) { mSetDevVol.invoke(service, vi(stream, index), ada, SHELL_PKG) }
    fun getDeviceVolume(stream: Int, ada: Any): Int = viGetIndex.invoke(mGetDevVol.invoke(service, vi(stream, 0), ada, SHELL_PKG)) as Int

    fun setRingerMode(mode: Int) {
        (mSetRinger ?: throw NoSuchMethodException("IAudioService.setRingerModeExternal")).invoke(service, mode, SHELL_PKG)
    }

    /** IAudioService.setWiredDeviceConnectionState(AudioDeviceAttributes, int state, String caller); the arguments are matched by type. */
    fun setWiredDeviceConnectionState(type: Int, connected: Boolean) {
        val m = mSetWired ?: throw NoSuchMethodException("IAudioService.setWiredDeviceConnectionState")
        val args = m.parameterTypes.map { p ->
            when {
                p == cAda -> ada(type, "")
                p == Int::class.javaPrimitiveType -> if (connected) CONNECTION_STATE_CONNECTED else CONNECTION_STATE_DISCONNECTED
                p == String::class.java -> SHELL_PKG
                else -> throw NoSuchMethodException("unsupported parameter ${p.name} in ${m.toGenericString()}")
            }
        }
        m.invoke(service, *args.toTypedArray())
    }

    // ---- players ----

    /**
     * Full (un-anonymised, for a caller holding MODIFY_AUDIO_ROUTING) playback configurations. The device a player is
     * routed to comes from AudioPlaybackConfiguration.getAudioDeviceInfo(), or from the hidden mDeviceId field matched
     * against the output devices.
     */
    fun playbackRoutes(am: AudioManager): List<PlayerRoute> {
        val m = mActivePlayback ?: throw NoSuchMethodException("IAudioService.getActivePlaybackConfigurations")
        val list = m.invoke(service) as? List<*> ?: return emptyList()
        return list.filterNotNull().map { routeOf(it, am) }
    }

    fun packagesForUid(uid: Int): List<String>? =
        try { (mPackagesForUid?.invoke(packageManager, uid) as? Array<*>)?.filterIsInstance<String>() } catch (t: Throwable) { null }

    fun opMode(uid: Int, pkg: String): Int {
        val m = mOpCheck ?: throw NoSuchMethodException("IAppOpsService.checkOperationRaw")
        var ints = 0
        var strings = 0
        val args = m.parameterTypes.map { p ->
            when {
                p == Int::class.javaPrimitiveType -> if (ints++ == 0) opCode else uid
                p == String::class.java -> if (strings++ == 0) pkg else null
                else -> throw NoSuchMethodException("unsupported parameter ${p.name} in ${m.toGenericString()}")
            }
        }
        return m.invoke(appOps, *args.toTypedArray()) as Int
    }

    fun setOpMode(uid: Int, pkg: String, mode: Int) {
        (mOpSetMode ?: throw NoSuchMethodException("IAppOpsService.setMode")).invoke(appOps, opCode, uid, pkg, mode)
    }

    companion object {
        const val SHELL_PKG = "com.android.shell"
        private const val ROLE_OUTPUT = 2 // AudioDeviceAttributes.ROLE_OUTPUT
        private const val CONNECTION_STATE_DISCONNECTED = 0
        private const val CONNECTION_STATE_CONNECTED = 1
        private const val PLAYER_STATE_STARTED = 2

        fun routeOf(cfg: Any, am: AudioManager): PlayerRoute {
            val cls = cfg.javaClass
            val uid = try { cls.getMethod("getClientUid").invoke(cfg) as Int } catch (_: Throwable) { -1 }
            val state = try { cls.getMethod("getPlayerState").invoke(cfg) as Int } catch (_: Throwable) { PLAYER_STATE_STARTED }
            var device: AudioDeviceInfo? = try { cls.getMethod("getAudioDeviceInfo").invoke(cfg) as? AudioDeviceInfo } catch (_: Throwable) { null }
            if (device == null) {
                val id = try {
                    cls.getDeclaredField("mDeviceId").also { it.isAccessible = true }.getInt(cfg)
                } catch (_: Throwable) { 0 }
                if (id != 0) device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
            }
            return PlayerRoute(uid, state == PLAYER_STATE_STARTED, device?.let { SpeakerController.isSpeakerType(it.type) })
        }
    }
}
