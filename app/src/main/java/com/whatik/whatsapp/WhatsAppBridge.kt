package com.whatik.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.whatik.data.StickerPack

/** Integrazione con WhatsApp (app consumer e Business) secondo l'API ufficiale degli sticker. */
object WhatsAppBridge {
    const val PACKAGE_CONSUMER = "com.whatsapp"
    const val PACKAGE_BUSINESS = "com.whatsapp.w4b"
    const val ACTION_ENABLE_STICKER_PACK = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"
    const val EXTRA_STICKER_PACK_ID = "sticker_pack_id"
    const val EXTRA_STICKER_PACK_AUTHORITY = "sticker_pack_authority"
    const val EXTRA_STICKER_PACK_NAME = "sticker_pack_name"
    const val EXTRA_VALIDATION_ERROR = "validation_error"

    fun authority(context: Context): String = context.packageName + ".stickercontentprovider"

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isAnyWhatsAppInstalled(context: Context): Boolean =
        isInstalled(context, PACKAGE_CONSUMER) || isInstalled(context, PACKAGE_BUSINESS)

    /** Intent che chiede a WhatsApp di aggiungere il pack; null se WhatsApp non è installato. */
    fun addPackIntent(context: Context, pack: StickerPack): Intent? {
        val consumer = isInstalled(context, PACKAGE_CONSUMER)
        val business = isInstalled(context, PACKAGE_BUSINESS)
        if (!consumer && !business) return null
        val intent = Intent(ACTION_ENABLE_STICKER_PACK).apply {
            putExtra(EXTRA_STICKER_PACK_ID, pack.identifier)
            putExtra(EXTRA_STICKER_PACK_AUTHORITY, authority(context))
            putExtra(EXTRA_STICKER_PACK_NAME, pack.name)
        }
        return when {
            consumer && business -> Intent.createChooser(intent, pack.name)
            consumer -> intent.setPackage(PACKAGE_CONSUMER)
            else -> intent.setPackage(PACKAGE_BUSINESS)
        }
    }

    /** true se almeno una delle app WhatsApp installate ha già il pack tra i propri sticker. */
    fun isPackAdded(context: Context, identifier: String): Boolean {
        val authority = authority(context)
        return listOf(PACKAGE_CONSUMER, PACKAGE_BUSINESS)
            .filter { isInstalled(context, it) }
            .any { isWhitelisted(context, it, authority, identifier) }
    }

    private fun isWhitelisted(context: Context, whatsAppPackage: String, authority: String, identifier: String): Boolean {
        val uri = Uri.Builder()
            .scheme("content")
            .authority("$whatsAppPackage.provider.sticker_whitelist_check")
            .appendPath("is_whitelisted")
            .appendQueryParameter("authority", authority)
            .appendQueryParameter("identifier", identifier)
            .build()
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val col = cursor.getColumnIndex("result")
                    col >= 0 && cursor.getInt(col) == 1
                } else {
                    false
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
