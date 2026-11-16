package dev.ilgax.tabremote.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

object Permissions {
    val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    /** Both runtime permissions the BLE link needs. A revoked one is noticed on the next command, never cached. */
    fun bluetoothGranted(context: Context): Boolean =
        BLUETOOTH.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
