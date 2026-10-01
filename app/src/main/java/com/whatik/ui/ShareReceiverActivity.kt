package com.whatik.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.whatik.R
import com.whatik.WhatikApp
import com.whatik.data.UrlImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Riceve dal menu "Condividi" immagini (importate subito), video (aperti nell'editor
 * per ritagliare lo sticker da una registrazione dello schermo) e link (importati
 * dalla pagina o dal file indicato).
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as WhatikApp
        val uris = extractUris(intent)
        val link = if (intent?.type?.startsWith("text/") == true) {
            UrlImporter.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        } else null

        if (uris.isEmpty() && link == null) {
            Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            val next = Intent(this@ShareReceiverActivity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

            val (images, videos) = uris.partition { !isVideo(it) }
            if (images.isNotEmpty()) {
                val summary = app.library.importUris(images, "share")
                val message = resources.getQuantityString(R.plurals.msg_imported, summary.added, summary.added)
                Toast.makeText(this@ShareReceiverActivity, message, Toast.LENGTH_LONG).show()
            }
            videos.firstOrNull()?.let { video ->
                // La concessione di lettura vale solo per questa activity: copiamo il file subito.
                val copied = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(app.cacheDir, "editor").apply { mkdirs() }
                        val target = File(dir, "${UUID.randomUUID()}.mp4")
                        contentResolver.openInputStream(video)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                            ?: throw IllegalStateException("Video non leggibile")
                        target
                    }.getOrNull()
                }
                if (copied != null) {
                    next.putExtra(MainViewModel.EXTRA_VIDEO_PATH, copied.absolutePath)
                    next.putExtra(MainViewModel.EXTRA_VIDEO_NAME, displayName(video))
                } else {
                    Toast.makeText(this@ShareReceiverActivity, getString(R.string.msg_video_unreadable, ""), Toast.LENGTH_LONG).show()
                }
            }
            link?.let { next.putExtra(MainViewModel.EXTRA_LINK, it) }
            startActivity(next)
            finish()
        }
    }

    private fun isVideo(uri: Uri): Boolean {
        val type = contentResolver.getType(uri) ?: intent?.type ?: ""
        return type.startsWith("video/")
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun extractUris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val result = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { result.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { result.addAll(it) }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { result.add(it) }
        }
        return result.toList()
    }
}
