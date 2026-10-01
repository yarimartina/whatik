package com.whatik.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatik.R
import com.whatik.WhatikApp
import com.whatik.data.UrlImporter
import com.whatik.ui.theme.WhatikTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Modalità sperimentale: il sito di TikTok dentro Whatik. Dopo l'accesso, aprendo il pannello
 * degli sticker nei messaggi la pagina scarica i file .awebp della raccolta: li raccogliamo
 * intercettando le richieste della WebView e li restituiamo alla schermata di importazione.
 */
class TikTokWebActivity : ComponentActivity() {

    private val found = MutableStateFlow<List<String>>(emptyList())
    private val seen = LinkedHashMap<String, String>()
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this) {
            val wv = webView
            if (wv != null && wv.canGoBack()) wv.goBack() else finish()
        }
        setContent {
            WhatikTheme { Screen() }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen() {
        val urls by found.collectAsStateWithLifecycle()
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.title_tiktok_web)) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        TextButton(onClick = { finishWithResult() }, enabled = urls.isNotEmpty()) {
                            Text(stringResource(R.string.tiktok_web_import, urls.size))
                        }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    if (urls.isEmpty()) stringResource(R.string.tiktok_web_hint) else stringResource(R.string.tiktok_web_found, urls.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                AndroidView(
                    factory = { ctx -> createWebView(ctx).also { webView = it } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context): WebView {
        val wv = WebView(context)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            userAgentString = WhatikApp.BROWSER_USER_AGENT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                collect(request.url.toString())
                return null
            }
        }
        wv.loadUrl(START_URL)
        return wv
    }

    private fun collect(url: String) {
        if (!UrlImporter.looksLikeTikTokSticker(url)) return
        val key = url.substringBefore('?')
        synchronized(seen) {
            if (seen.containsKey(key)) return
            seen[key] = url
            found.value = seen.values.toList()
        }
    }

    private fun finishWithResult() {
        setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_URLS, ArrayList(found.value)))
        finish()
    }

    override fun onDestroy() {
        runCatching { CookieManager.getInstance().flush() }
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URLS = "com.whatik.extra.URLS"
        private const val START_URL = "https://www.tiktok.com/messages"
    }
}
