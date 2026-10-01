package com.whatik.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaCandidate(
    val uri: Uri,
    val displayName: String,
    val path: String,
    val sizeBytes: Long,
    val mimeType: String,
    val dateAddedSec: Long,
    /** true se il percorso o il nome richiamano TikTok (cartella "TikTok", "musically"...). */
    val looksTikTok: Boolean,
)

/**
 * Cerca nel MediaStore le immagini salvate sul telefono: gli sticker che TikTok
 * permette di scaricare finiscono in galleria (tipicamente in una cartella "TikTok"
 * o in Download) ed è da lì che li andiamo a prendere.
 */
object MediaScanner {
    private val TIKTOK_HINTS = listOf("tiktok", "musically", "musical.ly", "douyin", "trill")

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Basta uno dei permessi: su Android 14 l'utente può concedere l'accesso solo ad alcune foto. */
    fun hasPermission(context: Context): Boolean =
        requiredPermissions().any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    suspend fun scan(context: Context, limit: Int = 3000): List<MediaCandidate> = withContext(Dispatchers.IO) {
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val pathColumn = if (useRelativePath) MediaStore.Images.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            pathColumn,
        )
        val selection = MediaStore.Images.Media.MIME_TYPE + " IN (?, ?, ?, ?)"
        val args = arrayOf("image/gif", "image/webp", "image/png", "image/jpeg")
        val sort = MediaStore.Images.Media.DATE_ADDED + " DESC"
        val result = ArrayList<MediaCandidate>()
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(pathColumn)
            while (cursor.moveToNext() && result.size < limit) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "immagine_$id"
                val bucket = cursor.getString(bucketCol) ?: ""
                val rawPath = cursor.getString(pathCol) ?: ""
                val path = if (useRelativePath) rawPath + name else rawPath
                val haystack = (path + " " + bucket).lowercase()
                result.add(
                    MediaCandidate(
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        displayName = name,
                        path = path,
                        sizeBytes = cursor.getLong(sizeCol),
                        mimeType = cursor.getString(mimeCol) ?: "image/*",
                        dateAddedSec = cursor.getLong(dateCol),
                        looksTikTok = TIKTOK_HINTS.any { haystack.contains(it) },
                    ),
                )
            }
        }
        result
    }
}
