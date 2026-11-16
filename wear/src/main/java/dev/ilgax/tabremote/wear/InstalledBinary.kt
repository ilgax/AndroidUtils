package dev.ilgax.tabremote.wear

import android.content.Context
import android.content.pm.PackageManager
import dev.ilgax.tabremote.protocol.IntegrityReport
import dev.ilgax.tabremote.protocol.WatchIntegrity
import java.io.File
import java.security.MessageDigest

/** Hashes of this app's own installed APK(s) and signing certificate, and of a downloaded APK's certificate. */
object InstalledBinary {
    /**
     * Streams every installed APK file (base first, then splits in the order the system lists them) through one SHA-256;
     * for a single-APK install this is simply the hash of that file. Blocking: call off the main thread.
     */
    fun report(context: Context): IntegrityReport? {
        val info = context.applicationInfo
        val files = listOf(info.sourceDir) + (info.splitSourceDirs?.toList() ?: emptyList())
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(32 * 1024)
        try {
            for (path in files) {
                File(path).inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        val cert = ownCert(context) ?: return null
        return IntegrityReport(WatchUpdater.versionCode(context), md.digest(), cert)
    }

    /** SHA-256 of the signing certificate of the installed app, or null if the system will not say. */
    fun ownCert(context: Context): ByteArray? = try {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        certOf(info)
    } catch (e: Exception) {
        null
    }

    /**
     * Signing certificate of the APK at [apk] if it is a package named [packageName]; null for a file the system cannot
     * parse or one that is some other package.
     */
    fun archiveCert(context: Context, apk: File, packageName: String): ByteArray? = try {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        if (info == null || info.packageName != packageName) null else certOf(info)
    } catch (e: Exception) {
        null
    }

    private fun certOf(info: android.content.pm.PackageInfo): ByteArray? {
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        return WatchIntegrity.certDigest(signers.map { it.toByteArray() })
    }
}
