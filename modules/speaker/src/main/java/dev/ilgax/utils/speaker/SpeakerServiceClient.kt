package dev.ilgax.utils.speaker

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** App-process side of [SpeakerUserService]: binds it on first use, blocking. Never call from the main thread. */
internal class SpeakerServiceClient private constructor(context: Context) {
    private val app = context.applicationContext
    @Volatile private var service: ISpeakerService? = null
    private var connection: ServiceConnection? = null

    @Synchronized
    fun <T> call(block: (ISpeakerService) -> T): T = try {
        block(connect())
    } catch (t: Throwable) {
        unbind()
        throw t
    }

    /** Unbinds and destroys the process, which also drops the audio policy. Harmless when nothing is bound. */
    @Synchronized
    fun unbind() {
        val conn = connection ?: return
        connection = null
        service = null
        try { Shizuku.unbindUserService(args(), conn, true) } catch (_: Throwable) {}
    }

    @Synchronized
    fun isBound() = service?.asBinder()?.pingBinder() == true

    private fun connect(): ISpeakerService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        unbind()
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) service = ISpeakerService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        connection = conn
        Shizuku.bindUserService(args(), conn)
        if (!latch.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) throw IllegalStateException("UserService did not start in ${BIND_TIMEOUT_S}s")
        return service ?: throw IllegalStateException("UserService binder is not alive")
    }

    private fun args() = Shizuku.UserServiceArgs(ComponentName(app.packageName, SpeakerUserService::class.java.name))
        .daemon(false)
        .processNameSuffix("speaker")
        .debuggable(BuildConfig.DEBUG)
        .version(versionCode())

    @Suppress("DEPRECATION")
    private fun versionCode(): Int = try {
        app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toInt()
    } catch (t: Throwable) {
        1
    }

    companion object {
        private const val BIND_TIMEOUT_S = 10L
        @Volatile private var instance: SpeakerServiceClient? = null
        fun get(context: Context): SpeakerServiceClient =
            instance ?: synchronized(this) { instance ?: SpeakerServiceClient(context).also { instance = it } }
    }
}
