package com.whatik.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.whatik.R
import com.whatik.WhatikApp
import kotlinx.coroutines.launch

/**
 * Riceve le immagini condivise da altre app (TikTok, galleria, file manager...) tramite
 * il menu "Condividi" e le importa nella libreria.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val app = application as WhatikApp
        lifecycleScope.launch {
            val summary = app.library.importUris(uris, "share")
            val message = resources.getQuantityString(R.plurals.msg_imported, summary.added, summary.added)
            Toast.makeText(this@ShareReceiverActivity, message, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(this@ShareReceiverActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }

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
