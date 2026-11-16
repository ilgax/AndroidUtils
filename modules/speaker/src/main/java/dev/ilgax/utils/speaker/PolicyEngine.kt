package dev.ilgax.utils.speaker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.HandlerThread
import android.os.Looper
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Experimental hard block, shell-uid side: a dynamic AudioPolicy whose single mix matches players (all usages it accepts,
 * optionally only some uids) and routes them to a loopback instead of an output device; the AudioRecord of that loopback is
 * read and thrown away, so whatever the policy captures is never played. Needs MODIFY_AUDIO_ROUTING, which the shell holds.
 *
 * AudioPolicy, AudioMix and AudioMixingRule are system APIs, so everything is reflection. Any step can fail on a given
 * build; the exception text goes back to the app, which shows it in Diagnostics.
 */
internal class PolicyEngine {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            if (Looper.myLooper() == null) { if (Looper.getMainLooper() == null) Looper.prepareMainLooper() else Looper.prepare() }
            r.run()
        }, "speaker-policy")
    }

    private var context: Context? = null
    private var policyThread: HandlerThread? = null
    private var policy: Any? = null
    private var audioManager: AudioManager? = null
    private var record: AudioRecord? = null
    @Volatile private var draining = false
    @Volatile private var drainedBytes = 0L
    private var description = "not running"

    fun start(uids: IntArray): String = synchronized(lock) {
        stopLocked()
        try {
            worker.submit(Callable { startLocked(uids) }).get(20, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            stopLocked()
            "ERR ${describe(t)}"
        }
    }

    fun stop(): String = synchronized(lock) {
        val was = policy != null
        stopLocked()
        if (was) "OK stopped" else "OK not running"
    }

    fun status(): String = synchronized(lock) {
        if (policy == null) "not running" else "running: $description, discarded ${drainedBytes / 1024} KiB"
    }

    private fun startLocked(uids: IntArray): String {
        try { HiddenApiBypass.addHiddenApiExemptions("L") } catch (_: Throwable) {}
        val ctx = context ?: bootstrapContext().also { context = it }
        val am = ctx.getSystemService(AudioManager::class.java) ?: throw IllegalStateException("no AudioManager")
        audioManager = am

        val cRuleB = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleB = cRuleB.getConstructor().newInstance()
        try { cRuleB.getMethod("setTargetMixRole", Int::class.javaPrimitiveType).invoke(ruleB, MIX_ROLE_PLAYERS) } catch (_: NoSuchMethodException) {}
        val addRule = cRuleB.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
        var usages = 0
        val rejected = ArrayList<Int>()
        for (u in USAGES) {
            try {
                addRule.invoke(ruleB, RULE_MATCH_ATTRIBUTE_USAGE, AudioAttributes.Builder().setUsage(u).build())
                usages++
            } catch (t: Throwable) {
                rejected.add(u)
            }
        }
        if (usages == 0) throw IllegalStateException("AudioMixingRule accepted none of the usages")
        // Rules of different kinds are ANDed: "this usage AND this uid".
        for (uid in uids) addRule.invoke(ruleB, RULE_MATCH_UID, Integer.valueOf(uid))
        val rule = cRuleB.getMethod("build").invoke(ruleB)

        val format = AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val cRule = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val cMixB = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val cMix = Class.forName("android.media.audiopolicy.AudioMix")
        val mixB = cMixB.getConstructor(cRule).newInstance(rule)
        cMixB.getMethod("setFormat", AudioFormat::class.java).invoke(mixB, format)
        cMixB.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixB, ROUTE_FLAG_LOOP_BACK)
        val mix = cMixB.getMethod("build").invoke(mixB)

        val thread = HandlerThread("speaker-policy-cb").also { it.start() }
        policyThread = thread
        val cPolicyB = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyB = cPolicyB.getConstructor(Context::class.java).newInstance(ctx)
        cPolicyB.getMethod("addMix", cMix).invoke(policyB, mix)
        try { cPolicyB.getMethod("setLooper", Looper::class.java).invoke(policyB, thread.looper) } catch (_: NoSuchMethodException) {}
        val cPolicy = Class.forName("android.media.audiopolicy.AudioPolicy")
        val p = cPolicyB.getMethod("build").invoke(policyB)!!

        val code = AudioManager::class.java.getMethod("registerAudioPolicy", cPolicy).invoke(am, p) as Int
        if (code != 0) throw IllegalStateException("registerAudioPolicy returned $code")
        policy = p

        val rec = cPolicy.getMethod("createAudioRecordSink", cMix).invoke(p, mix) as? AudioRecord
            ?: throw IllegalStateException("createAudioRecordSink returned null")
        record = rec
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IllegalStateException("loopback record did not start (state ${rec.recordingState})")
        drainedBytes = 0
        draining = true
        Thread({
            val buf = ByteArray(16 * 1024)
            while (draining) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) drainedBytes += n
                else if (n == AudioRecord.ERROR_DEAD_OBJECT) break
                else if (n < 0) Thread.sleep(50)
            }
        }, "speaker-drain").apply { isDaemon = true; start() }

        description = "$usages usages" + (if (rejected.isEmpty()) "" else " (rejected ${rejected.joinToString()})") +
            if (uids.isEmpty()) ", every app" else ", uids ${uids.joinToString()}"
        return "OK mix registered: $description"
    }

    private fun stopLocked() {
        draining = false
        record?.let { try { it.stop() } catch (_: Throwable) {}; try { it.release() } catch (_: Throwable) {} }
        record = null
        val p = policy
        policy = null
        if (p != null) {
            try {
                AudioManager::class.java.getMethod("unregisterAudioPolicy", Class.forName("android.media.audiopolicy.AudioPolicy"))
                    .invoke(audioManager, p)
            } catch (_: Throwable) {}
        }
        policyThread?.quitSafely()
        policyThread = null
        description = "not running"
    }

    private fun bootstrapContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val sys: Context = try {
            val c = at.getDeclaredConstructor().also { it.isAccessible = true }
            val instance = c.newInstance()
            try { at.getDeclaredField("sCurrentActivityThread").also { it.isAccessible = true }.set(null, instance) } catch (_: Throwable) {}
            at.getMethod("getSystemContext").invoke(instance) as Context
        } catch (t: Throwable) {
            val instance = at.getMethod("systemMain").invoke(null)
            at.getMethod("getSystemContext").invoke(instance) as Context
        }
        return SpeakerShellContext(sys)
    }

    private fun describe(t: Throwable): String {
        var r = t
        while ((r is InvocationTargetException || r is ExecutionException) && r.cause != null) r = r.cause!!
        return "${r.javaClass.simpleName}: ${r.message ?: "no message"}"
    }

    private companion object {
        const val MIX_ROLE_PLAYERS = 0
        const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
        const val RULE_MATCH_UID = 0x1 shl 2
        const val ROUTE_FLAG_LOOP_BACK = 0x1 shl 1

        /** Every AudioAttributes usage constant, including the system ones (virtual source, assistant, emergency, safety, ...). */
        val USAGES = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 1000, 1001, 1002, 1003)
    }
}
