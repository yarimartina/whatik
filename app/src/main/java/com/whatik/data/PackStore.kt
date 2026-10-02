package com.whatik.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Pack WhatsApp generati dall'app. Ogni pack vive in files/packs/<identifier>/ con
 * un pack.json, gli sticker .webp e l'icona tray.png. Il ContentProvider legge da qui.
 */
class PackStore(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "packs").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val mutex = Mutex()

    private val _packs = MutableStateFlow(loadAll())
    val packs: StateFlow<List<StickerPack>> = _packs

    class ConvertedSticker(
        val bytes: ByteArray,
        val animated: Boolean,
        val emojis: List<String>,
        val accessibilityText: String?,
        val sourceId: String?,
    )

    fun packDir(identifier: String): File = File(root, identifier)

    fun trayFile(pack: StickerPack): File = File(packDir(pack.identifier), pack.trayFile)

    fun stickerFile(pack: StickerPack, sticker: PackSticker): File = File(packDir(pack.identifier), sticker.fileName)

    /** Lettura sincrona dal disco (usata dal ContentProvider, che gira in un processo-contesto proprio). */
    fun loadAll(): List<StickerPack> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir -> readPack(File(dir, PACK_JSON)) }.sortedByDescending { it.updatedAt }
    }

    fun load(identifier: String): StickerPack? {
        if (!isSafeIdentifier(identifier)) return null
        return readPack(File(packDir(identifier), PACK_JSON))
    }

    /** File di un asset richiesto da WhatsApp, solo se dichiarato nel pack (niente path traversal). */
    fun resolveAsset(identifier: String, fileName: String): File? {
        val pack = load(identifier) ?: return null
        if (fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) return null
        val declared = fileName == pack.trayFile || pack.stickers.any { it.fileName == fileName }
        if (!declared) return null
        val file = File(packDir(identifier), fileName)
        return if (file.isFile) file else null
    }

    suspend fun createPack(name: String, publisher: String, animated: Boolean): StickerPack = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val identifier = newIdentifier()
            val pack = StickerPack(
                identifier = identifier,
                name = sanitizeText(name, "Sticker"),
                publisher = sanitizeText(publisher, "Whatik"),
                animated = animated,
                stickers = emptyList(),
                createdAt = now,
                updatedAt = now,
            )
            packDir(identifier).mkdirs()
            writePack(pack)
            refresh()
            pack
        }
    }

    /** Aggiunge sticker già convertiti al pack e rigenera l'icona se manca. */
    suspend fun addStickers(identifier: String, converted: List<ConvertedSticker>, trayIcon: (ByteArray) -> ByteArray): StickerPack =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val pack = load(identifier) ?: throw IllegalArgumentException("Pack $identifier inesistente")
                val dir = packDir(identifier)
                val added = ArrayList<PackSticker>()
                for ((i, sticker) in converted.withIndex()) {
                    require(sticker.animated == pack.animated) { "Tipo di sticker incompatibile con il pack" }
                    val fileName = String.format(Locale.US, "s_%d_%06x.webp", pack.stickers.size + i + 1, Random.nextInt(0xFFFFFF))
                    File(dir, fileName).writeBytes(sticker.bytes)
                    added.add(PackSticker(fileName, sticker.emojis, sticker.accessibilityText, sticker.sourceId))
                }
                val tray = File(dir, pack.trayFile)
                if (!tray.exists() && added.isNotEmpty()) {
                    tray.writeBytes(trayIcon(converted.first().bytes))
                }
                val updated = pack.copy(
                    stickers = pack.stickers + added,
                    updatedAt = System.currentTimeMillis(),
                    imageDataVersion = pack.imageDataVersion + 1,
                )
                writePack(updated)
                refresh()
                updated
            }
        }

    suspend fun removeStickers(identifier: String, fileNames: Set<String>): StickerPack? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pack = load(identifier) ?: return@withLock null
            val remaining = pack.stickers.filterNot { it.fileName in fileNames }
            if (remaining.size == pack.stickers.size) return@withLock pack
            fileNames.forEach { File(packDir(identifier), it).delete() }
            val updated = pack.copy(stickers = remaining, updatedAt = System.currentTimeMillis(), imageDataVersion = pack.imageDataVersion + 1)
            writePack(updated)
            refresh()
            updated
        }
    }

    fun readSticker(pack: StickerPack, sticker: PackSticker): ByteArray = stickerFile(pack, sticker).readBytes()

    /** Sostituisce tutti gli sticker (es. cambio di tipo statico/animato), conservando emoji e nomi. */
    suspend fun replaceAllStickers(identifier: String, animated: Boolean, stickers: List<Pair<PackSticker, ByteArray>>): StickerPack =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val pack = load(identifier) ?: throw IllegalArgumentException("Pack $identifier inesistente")
                val dir = packDir(identifier)
                val oldFiles = pack.stickers.map { it.fileName }.toSet()
                val replaced = stickers.mapIndexed { i, (meta, bytes) ->
                    val fileName = String.format(Locale.US, "s_%d_%06x.webp", i + 1, Random.nextInt(0xFFFFFF))
                    File(dir, fileName).writeBytes(bytes)
                    meta.copy(fileName = fileName)
                }
                oldFiles.forEach { File(dir, it).delete() }
                val updated = pack.copy(
                    animated = animated,
                    stickers = replaced,
                    updatedAt = System.currentTimeMillis(),
                    imageDataVersion = pack.imageDataVersion + 1,
                )
                writePack(updated)
                refresh()
                updated
            }
        }

    suspend fun rename(identifier: String, name: String, publisher: String): StickerPack? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pack = load(identifier) ?: return@withLock null
            val updated = pack.copy(name = sanitizeText(name, pack.name), publisher = sanitizeText(publisher, pack.publisher), updatedAt = System.currentTimeMillis())
            writePack(updated)
            refresh()
            updated
        }
    }

    suspend fun delete(identifier: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isSafeIdentifier(identifier)) packDir(identifier).deleteRecursively()
            refresh()
        }
    }

    private fun refresh() {
        _packs.value = loadAll()
    }

    private fun readPack(file: File): StickerPack? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(StickerPack.serializer(), file.readText()) }.getOrNull()
    }

    private fun writePack(pack: StickerPack) {
        val dir = packDir(pack.identifier).apply { mkdirs() }
        val tmp = File(dir, "$PACK_JSON.tmp")
        tmp.writeText(json.encodeToString(StickerPack.serializer(), pack))
        if (!tmp.renameTo(File(dir, PACK_JSON))) {
            File(dir, PACK_JSON).writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun newIdentifier(): String {
        val time = java.lang.Long.toString(System.currentTimeMillis(), 36)
        val rand = Integer.toString(Random.nextInt(0, 36 * 36 * 36 * 36), 36).padStart(4, '0')
        return "whatik_${time}_$rand"
    }

    companion object {
        const val PACK_JSON = "pack.json"
        private val SAFE_ID = Regex("^[A-Za-z0-9_.-]{1,128}$")
        private val ALLOWED_TEXT = Regex("[^\\w\\-.,'\\s]")

        fun isSafeIdentifier(id: String): Boolean = SAFE_ID.matches(id) && !id.contains("..")

        /** WhatsApp accetta solo lettere, numeri, spazi e . , ' - _ nei nomi (max 128 caratteri). */
        fun sanitizeText(text: String, fallback: String): String {
            val cleaned = ALLOWED_TEXT.replace(text, "").replace("..", ".").trim().take(128)
            return cleaned.ifEmpty { fallback }
        }
    }
}
