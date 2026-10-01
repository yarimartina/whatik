package com.whatik.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Versione installata e impronta della chiave di firma, per diagnosticare gli aggiornamenti falliti. */
object AppInfo {
    data class Info(val versionName: String, val versionCode: Long, val signatureSha256: String)

    fun read(context: Context): Info {
        val pm = context.packageManager
        val pkg = context.packageName
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()
        }
        val fingerprint = signature?.let { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
                .take(8).joinToString(":") { "%02X".format(it) }
        } ?: "?"
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return Info(info.versionName ?: "?", code, fingerprint)
    }
}
