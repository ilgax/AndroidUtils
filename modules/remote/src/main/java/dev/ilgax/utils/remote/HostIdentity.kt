package dev.ilgax.utils.remote

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.ilgax.utils.protocol.DeviceClass
import dev.ilgax.utils.protocol.HostInfo

/** What this device tells a watch about itself (INFO): a display name, its Bluetooth name and whether it is a phone or a tablet. */
object HostIdentity {
    fun read(context: Context): HostInfo {
        val bt = bluetoothName(context)
        val deviceName = try {
            Settings.Global.getString(context.contentResolver, "device_name")
        } catch (_: Exception) {
            null
        }
        return HostInfo(
            displayName = HostInfo.pickName(deviceName, bt, Build.MODEL),
            btName = bt?.trim()?.takeIf { it.isNotEmpty() },
            deviceClass = DeviceClass.fromSmallestWidthDp(context.resources.configuration.smallestScreenWidthDp),
        )
    }

    private fun bluetoothName(context: Context): String? = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.name
    } catch (_: SecurityException) {
        null
    }
}
