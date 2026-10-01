package com.whatik.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.whatik.image.GifDecoder
import com.whatik.image.ImageFormat
import com.whatik.image.ImageKind
import com.whatik.image.WebPContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Libreria degli sticker importati: i file originali vengono copiati in
 * files/library/ e descritti in un indice JSON. Da qui l'utente seleziona cosa
 * convertire e copiare su WhatsApp.
 */
class StickerLibrary(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "library").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _items = MutableStateFlow(loadIndex())
    val items: StateFlow<List<StickerItem>> = _items

    sealed class ImportResult {
        class Added(val item: StickerItem) : ImportResult()
        class Duplicate(val item: StickerItem) : ImportResult()
        class Failed(val name: String, val reason: String) : ImportResult()
    }

    data class ImportSummary(val added: Int, val duplicates: Int, val failed: List<ImportResult.Failed>) {
        val total: Int get() = added + duplicates + failed.size
    }

    fun file(item: StickerItem): File = File(dir, item.fileName)

    fun find(id: String): StickerItem? = _items.value.firstOrNull { it.id == id }

    suspend fun importUris(uris: List<Uri>, source: String): ImportSummary = withContext(Dispatchers.IO) {
        var added = 0
        var duplicates = 0
        val failed = ArrayList<ImportResult.Failed>()
        for (uri in uris) {
            when (val result = importUri(uri, source)) {
                is ImportResult.Added -> added++
                is ImportResult.Duplicate -> duplicates++
                is ImportResult.Failed -> failed.add(result)
            }
        }
        ImportSummary(added, duplicates, failed)
    }

    suspend fun importUri(uri: Uri, source: String, displayNameHint: String? = null): ImportResult = withContext(Dispatchers.IO) {
        val name = displayNameHint ?: queryDisplayName(uri) ?: uri.lastPathSegment ?: "sticker"
        try {
            val bytes = readBytes(uri) ?: return@withContext ImportResult.Failed(name, "Impossibile leggere il file")
            importBytes(bytes, name, source)
        } catch (e: SecurityException) {
            ImportResult.Failed(name, "Permesso negato")
        } catch (e: Exception) {
            ImportResult.Failed(name, e.message ?: "Errore sconosciuto")
        }
    }

    suspend fun importBytes(bytes: ByteArray, name: String, source: String): ImportResult = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext ImportResult.Failed(name, "File vuoto")
        if (bytes.size > MAX_IMPORT_BYTES) return@withContext ImportResult.Failed(name, "File troppo grande (max 30 MB)")
        val kind = ImageFormat.sniff(bytes)
        val probe = probe(bytes, kind) ?: return@withContext ImportResult.Failed(name, "Non è un'immagine supportata")
        val hash = sha256(bytes)
        mutex.withLock {
            _items.value.firstOrNull { it.sha256 == hash }?.let { return@withContext ImportResult.Duplicate(it) }
            val id = UUID.randomUUID().toString().replace("-", "")
            val fileName = "$id.${kind.extension.takeIf { kind != ImageKind.UNKNOWN } ?: "img"}"
            File(dir, fileName).writeBytes(bytes)
            val item = StickerItem(
                id = id,
                fileName = fileName,
                displayName = stripExtension(name).take(80),
                mimeType = if (kind == ImageKind.UNKNOWN) probe.mimeType else kind.mimeType,
                animated = probe.animated,
                width = probe.width,
                height = probe.height,
                sizeBytes = bytes.size.toLong(),
                importedAt = System.currentTimeMillis(),
                source = source,
                sha256 = hash,
            )
            val updated = listOf(item) + _items.value
            saveIndex(updated)
            _items.value = updated
            ImportResult.Added(item)
        }
    }

    /** Importa ricorsivamente tutte le immagini di una cartella scelta con ACTION_OPEN_DOCUMENT_TREE. */
    suspend fun importFolder(treeUri: Uri, source: String = "folder"): ImportSummary = withContext(Dispatchers.IO) {
        val uris = ArrayList<Pair<Uri, String>>()
        collectDocuments(treeUri, DocumentsContract.getTreeDocumentId(treeUri), uris, depth = 0)
        var added = 0
        var duplicates = 0
        val failed = ArrayList<ImportResult.Failed>()
        for ((uri, name) in uris) {
            when (val result = importUri(uri, source, name)) {
                is ImportResult.Added -> added++
                is ImportResult.Duplicate -> duplicates++
                is ImportResult.Failed -> failed.add(result)
            }
        }
        ImportSummary(added, duplicates, failed)
    }

    private fun collectDocuments(treeUri: Uri, documentId: String, out: MutableList<Pair<Uri, String>>, depth: Int) {
        if (depth > MAX_FOLDER_DEPTH || out.size >= MAX_FOLDER_FILES) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: id
                val mime = cursor.getString(2) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    collectDocuments(treeUri, id, out, depth + 1)
                } else if (mime.startsWith("image/") || hasImageExtension(name)) {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id) to name)
                }
                if (out.size >= MAX_FOLDER_FILES) return
            }
        }
    }

    suspend fun delete(ids: Set<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (removed, kept) = _items.value.partition { it.id in ids }
            removed.forEach { File(dir, it.fileName).delete() }
            saveIndex(kept)
            _items.value = kept
        }
    }

    // ---------------------------------------------------------------- interni

    private class Probe(val width: Int, val height: Int, val animated: Boolean, val mimeType: String)

    private fun probe(bytes: ByteArray, kind: ImageKind): Probe? {
        return when (kind) {
            ImageKind.GIF -> runCatching {
                val info = GifDecoder.readInfo(bytes)
                Probe(info.width, info.height, info.frameCount > 1, kind.mimeType)
            }.getOrNull()
            ImageKind.WEBP -> runCatching {
                val parsed = WebPContainer.parse(bytes)
                Probe(parsed.width, parsed.height, parsed.animated && parsed.frames.size > 1, kind.mimeType)
            }.getOrNull()
            else -> {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) null
                else Probe(options.outWidth, options.outHeight, false, options.outMimeType ?: kind.mimeType)
            }
        }
    }

    private fun readBytes(uri: Uri): ByteArray? =
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_IMPORT_BYTES) return ByteArray(MAX_IMPORT_BYTES + 1)
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun loadIndex(): List<StickerItem> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(StickerItem.serializer()), indexFile.readText())
                .filter { File(dir, it.fileName).exists() }
        }.getOrDefault(emptyList())
    }

    private fun saveIndex(items: List<StickerItem>) {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(json.encodeToString(ListSerializer(StickerItem.serializer()), items))
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val MAX_IMPORT_BYTES = 30 * 1024 * 1024
        private const val MAX_FOLDER_DEPTH = 6
        private const val MAX_FOLDER_FILES = 2000
        private val IMAGE_EXTENSIONS = setOf("gif", "webp", "png", "jpg", "jpeg", "bmp", "heic", "heif")

        fun hasImageExtension(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

        fun stripExtension(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0 && name.length - dot <= 6) name.substring(0, dot) else name
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
