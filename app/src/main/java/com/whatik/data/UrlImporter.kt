package com.whatik.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Un'immagine trovata in una pagina web, candidata all'importazione. */
data class RemoteCandidate(val url: String, val name: String, val looksSticker: Boolean)

/**
 * Importa sticker da un link: un URL diretto a un'immagine (es. il file .awebp di uno
 * sticker sul CDN di TikTok) oppure una pagina web in cui cercare le immagini.
 */
class UrlImporter(private val library: StickerLibrary) {

    sealed class Fetched {
        class Image(val bytes: ByteArray, val name: String) : Fetched()
        class Page(val pageUrl: String, val candidates: List<RemoteCandidate>) : Fetched()
        class Error(val message: String) : Fetched()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun fetch(rawUrl: String): Fetched = withContext(Dispatchers.IO) {
        val url = extractUrl(rawUrl) ?: return@withContext Fetched.Error("Nessun link valido")
        try {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", "*/*").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Fetched.Error("Il server ha risposto ${response.code}")
                val body = response.body ?: return@withContext Fetched.Error("Risposta vuota")
                val contentType = body.contentType()?.toString()?.lowercase() ?: ""
                val finalUrl = response.request.url.toString()
                if (contentType.startsWith("text/html") || contentType.contains("xml")) {
                    val html = body.string()
                    return@withContext Fetched.Page(finalUrl, findImages(html, finalUrl))
                }
                val bytes = body.bytes()
                if (bytes.size > StickerLibrary.MAX_IMPORT_BYTES) return@withContext Fetched.Error("File troppo grande")
                if (ImageFormatCheck.looksLikeImage(bytes)) {
                    Fetched.Image(bytes, nameFromUrl(finalUrl))
                } else if (looksLikeHtml(bytes)) {
                    Fetched.Page(finalUrl, findImages(String(bytes, Charsets.UTF_8), finalUrl))
                } else {
                    Fetched.Error("Il link non contiene un'immagine")
                }
            }
        } catch (e: Exception) {
            Fetched.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Scarica e importa in libreria le immagini scelte da una pagina. */
    suspend fun importCandidates(candidates: List<RemoteCandidate>, source: String = "link"): StickerLibrary.ImportSummary =
        withContext(Dispatchers.IO) {
            var added = 0
            var duplicates = 0
            val failed = ArrayList<StickerLibrary.ImportResult.Failed>()
            for (candidate in candidates) {
                when (val fetched = fetch(candidate.url)) {
                    is Fetched.Image -> when (val r = library.importBytes(fetched.bytes, candidate.name, source)) {
                        is StickerLibrary.ImportResult.Added -> added++
                        is StickerLibrary.ImportResult.Duplicate -> duplicates++
                        is StickerLibrary.ImportResult.Failed -> failed.add(r)
                    }
                    is Fetched.Page -> failed.add(StickerLibrary.ImportResult.Failed(candidate.name, "non è un'immagine"))
                    is Fetched.Error -> failed.add(StickerLibrary.ImportResult.Failed(candidate.name, fetched.message))
                }
            }
            StickerLibrary.ImportSummary(added, duplicates, failed)
        }

    companion object {
        private const val USER_AGENT = com.whatik.WhatikApp.BROWSER_USER_AGENT
        private val TIKTOK_HOSTS = listOf("tiktok", "ibyteimg", "tiktokcdn", "byteimg", "muscdn")
        private val NON_IMAGE_EXT = Regex("""\.(js|css|svg|json|html|woff2?|ttf|mp4|m3u8|ts)(\?|#|$)""", RegexOption.IGNORE_CASE)

        /** URL di un probabile sticker TikTok (file .awebp o percorso "sticker" sui CDN di TikTok). */
        fun looksLikeTikTokSticker(url: String): Boolean {
            val lower = url.lowercase()
            if (!lower.startsWith("http")) return false
            val host = lower.substringAfter("://").substringBefore('/')
            if (TIKTOK_HOSTS.none { host.contains(it) }) return false
            if (NON_IMAGE_EXT.containsMatchIn(lower)) return false
            return lower.contains(".awebp") || lower.contains("video2sticker") || lower.contains("sticker")
        }
        private val URL_IN_TEXT = Regex("""https?://[^\s<>"'\\]+""")
        private val IMAGE_EXT = Regex("""\.(awebp|webp|gif|png|jpe?g)(\?|#|$)""", RegexOption.IGNORE_CASE)
        private val STICKER_HINTS = listOf("sticker", "awebp", "video2sticker", "ibyteimg", "tiktokcdn", "emoji")

        /** Estrae il primo URL da un testo (es. "Guarda questo sticker! https://...") e ripulisce la fine. */
        fun extractUrl(text: String): String? {
            val trimmed = text.trim()
            val match = URL_IN_TEXT.find(trimmed) ?: return null
            return match.value.trimEnd('.', ',', ')', ']')
        }

        fun nameFromUrl(url: String): String {
            val path = url.substringBefore('?').substringBefore('#')
            val last = path.substringAfterLast('/').ifEmpty { "sticker" }
            return StickerLibrary.stripExtension(last).replace('~', ' ').take(60).ifEmpty { "sticker" }
        }

        /** Cerca gli URL di immagini dentro l'HTML/JSON di una pagina (TikTok li serializza con /). */
        fun findImages(html: String, pageUrl: String): List<RemoteCandidate> {
            val text = html
                .replace("\\u002F", "/").replace("\\u002f", "/").replace("\\/", "/")
                .replace("&amp;", "&").replace("\\u0026", "&")
            val seen = LinkedHashMap<String, RemoteCandidate>()
            for (m in URL_IN_TEXT.findAll(text)) {
                val url = m.value.trimEnd('.', ',', ')', ']', ';')
                val lower = url.lowercase()
                val isImage = IMAGE_EXT.containsMatchIn(lower) || lower.contains("video2sticker") || lower.contains("tplv-") && lower.contains("sticker")
                if (!isImage) continue
                val key = url.substringBefore('?')
                if (seen.containsKey(key)) continue
                val looksSticker = STICKER_HINTS.any { lower.contains(it) } || lower.endsWith(".gif") || lower.contains(".webp")
                seen[key] = RemoteCandidate(url, nameFromUrl(url), looksSticker)
                if (seen.size >= 300) break
            }
            // gli sticker probabili prima, nell'ordine in cui compaiono nella pagina
            return seen.values.sortedByDescending { it.looksSticker }
        }

        private fun looksLikeHtml(bytes: ByteArray): Boolean {
            val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1).lowercase()
            return head.contains("<html") || head.contains("<!doctype")
        }
    }
}

/** Piccolo helper per non legare UrlImporter alle classi Android di decodifica. */
object ImageFormatCheck {
    fun looksLikeImage(bytes: ByteArray): Boolean =
        com.whatik.image.ImageFormat.sniff(bytes) != com.whatik.image.ImageKind.UNKNOWN ||
            (bytes.size > 12 && bytes[0] == 0.toByte() && String(bytes, 4, 4, Charsets.ISO_8859_1) == "ftyp") // HEIF/AVIF
}
