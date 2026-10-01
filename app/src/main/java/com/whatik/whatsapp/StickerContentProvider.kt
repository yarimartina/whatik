package com.whatik.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.whatik.data.PackStore
import com.whatik.data.StickerPack

/**
 * ContentProvider nel formato richiesto da WhatsApp (vedi github.com/WhatsApp/stickers).
 * WhatsApp interroga:
 *  - content://<authority>/metadata                       -> elenco pack
 *  - content://<authority>/metadata/<id>                  -> singolo pack
 *  - content://<authority>/stickers/<id>                  -> sticker del pack
 *  - content://<authority>/stickers_asset/<id>/<file>     -> file webp / icona png
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var authority: String
    private lateinit var matcher: UriMatcher
    private lateinit var store: PackStore

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        authority = WhatsAppBridge.authority(ctx)
        store = PackStore(ctx)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, METADATA, CODE_METADATA)
            addURI(authority, "$METADATA/*", CODE_METADATA_SINGLE)
            addURI(authority, "$STICKERS/*", CODE_STICKERS)
            addURI(authority, "$STICKERS_ASSET/*/*", CODE_STICKERS_ASSET)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor {
        return when (matcher.match(uri)) {
            CODE_METADATA -> metadataCursor(uri, store.loadAll())
            CODE_METADATA_SINGLE -> metadataCursor(uri, listOfNotNull(store.load(uri.lastPathSegment ?: "")))
            CODE_STICKERS -> stickersCursor(uri, store.load(uri.lastPathSegment ?: ""))
            else -> throw IllegalArgumentException("URI sconosciuto: $uri")
        }
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        CODE_METADATA -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        CODE_METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        CODE_STICKERS_ASSET -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("URI sconosciuto: $uri")
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != CODE_STICKERS_ASSET) throw IllegalArgumentException("URI sconosciuto: $uri")
        val segments = uri.pathSegments
        if (segments.size != 3) throw IllegalArgumentException("Percorso asset non valido: $uri")
        val file = store.resolveAsset(segments[1], segments[2]) ?: return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    private fun metadataCursor(uri: Uri, packs: List<StickerPack>): Cursor {
        val cursor = MatrixCursor(
            arrayOf(
                COL_IDENTIFIER, COL_NAME, COL_PUBLISHER, COL_TRAY_IMAGE,
                COL_PLAY_STORE_LINK, COL_IOS_LINK, COL_PUBLISHER_EMAIL, COL_PUBLISHER_WEBSITE,
                COL_PRIVACY_POLICY, COL_LICENSE_AGREEMENT, COL_IMAGE_DATA_VERSION,
                COL_AVOID_CACHE, COL_ANIMATED,
            ),
        )
        for (pack in packs) {
            cursor.newRow()
                .add(COL_IDENTIFIER, pack.identifier)
                .add(COL_NAME, pack.name)
                .add(COL_PUBLISHER, pack.publisher)
                .add(COL_TRAY_IMAGE, pack.trayFile)
                .add(COL_PLAY_STORE_LINK, "")
                .add(COL_IOS_LINK, "")
                .add(COL_PUBLISHER_EMAIL, "")
                .add(COL_PUBLISHER_WEBSITE, "")
                .add(COL_PRIVACY_POLICY, "")
                .add(COL_LICENSE_AGREEMENT, "")
                .add(COL_IMAGE_DATA_VERSION, pack.imageDataVersion.toString())
                .add(COL_AVOID_CACHE, 0)
                .add(COL_ANIMATED, if (pack.animated) 1 else 0)
        }
        cursor.setNotificationUri(context?.contentResolver, uri)
        return cursor
    }

    private fun stickersCursor(uri: Uri, pack: StickerPack?): Cursor {
        val cursor = MatrixCursor(arrayOf(COL_STICKER_FILE, COL_STICKER_EMOJI, COL_STICKER_ACCESSIBILITY))
        pack?.stickers?.forEach { sticker ->
            cursor.newRow()
                .add(COL_STICKER_FILE, sticker.fileName)
                .add(COL_STICKER_EMOJI, sticker.emojis.joinToString(","))
                .add(COL_STICKER_ACCESSIBILITY, sticker.accessibilityText ?: "")
        }
        cursor.setNotificationUri(context?.contentResolver, uri)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Sola lettura")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("Sola lettura")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = throw UnsupportedOperationException("Sola lettura")

    companion object {
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"

        private const val CODE_METADATA = 1
        private const val CODE_METADATA_SINGLE = 2
        private const val CODE_STICKERS = 3
        private const val CODE_STICKERS_ASSET = 4

        const val COL_IDENTIFIER = "sticker_pack_identifier"
        const val COL_NAME = "sticker_pack_name"
        const val COL_PUBLISHER = "sticker_pack_publisher"
        const val COL_TRAY_IMAGE = "sticker_pack_icon"
        const val COL_PLAY_STORE_LINK = "android_play_store_link"
        const val COL_IOS_LINK = "ios_app_download_link"
        const val COL_PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val COL_PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val COL_PRIVACY_POLICY = "sticker_pack_privacy_policy_website"
        const val COL_LICENSE_AGREEMENT = "sticker_pack_license_agreement_website"
        const val COL_IMAGE_DATA_VERSION = "image_data_version"
        const val COL_AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val COL_ANIMATED = "animated_sticker_pack"
        const val COL_STICKER_FILE = "sticker_file_name"
        const val COL_STICKER_EMOJI = "sticker_emoji"
        const val COL_STICKER_ACCESSIBILITY = "sticker_accessibility_text"
    }
}
