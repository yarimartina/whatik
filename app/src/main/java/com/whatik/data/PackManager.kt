package com.whatik.data

import com.whatik.image.StickerConverter
import com.whatik.image.WebPContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Operazioni sui pack già creati: aggiunta di sticker dalla libreria, rimozione,
 * cambio di tipo e unione di più pack, rispettando le regole di WhatsApp
 * (max 30 sticker, pack tutti animati o tutti statici).
 */
class PackManager(private val library: StickerLibrary, private val packStore: PackStore) {

    data class Report(
        /** Sticker aggiunti al pack di destinazione. */
        val added: Int,
        /** Sticker non aggiunti perché il pack era pieno (solo per l'aggiunta dalla libreria). */
        val skipped: Int,
        /** Pack nuovi creati per gli sticker in eccesso (solo per l'unione). */
        val overflowPacks: List<StickerPack>,
        val failures: List<String>,
    )

    /** Aggiunge sticker della libreria al pack, convertendoli al tipo del pack. */
    suspend fun addLibraryItems(
        packId: String,
        itemIds: List<String>,
        emojis: List<String>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Report = withContext(Dispatchers.Default) {
        val pack = packStore.load(packId) ?: throw IllegalArgumentException("Pack inesistente")
        val free = (PackPlanner.MAX_STICKERS - pack.stickers.size).coerceAtLeast(0)
        val chosen = itemIds.take(free)
        val failures = ArrayList<String>()
        val converted = ArrayList<PackStore.ConvertedSticker>()
        for ((i, id) in chosen.withIndex()) {
            coroutineContext.ensureActive()
            onProgress(i, chosen.size)
            val item = library.find(id) ?: continue
            try {
                val result = StickerConverter.convertForPack(library.file(item).readBytes(), pack.animated)
                converted.add(
                    PackStore.ConvertedSticker(
                        bytes = result.bytes,
                        animated = result.animated,
                        emojis = emojis,
                        accessibilityText = item.displayName.take(if (result.animated) 255 else 125),
                        sourceId = item.id,
                    ),
                )
            } catch (e: Exception) {
                failures.add("${item.displayName}: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (converted.isNotEmpty()) packStore.addStickers(packId, converted) { StickerConverter.makeTrayIcon(it) }
        onProgress(chosen.size, chosen.size)
        Report(converted.size, itemIds.size - chosen.size, emptyList(), failures)
    }

    /**
     * Unisce i pack [sourceIds] dentro [targetId]. Se il tipo risultante è diverso da quello
     * del pack di destinazione, prima converte i suoi sticker. Oltre i 30 sticker vengono
     * creati pack nuovi numerati.
     */
    suspend fun merge(
        targetId: String,
        sourceIds: List<String>,
        resultAnimated: Boolean,
        deleteSources: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Report = withContext(Dispatchers.Default) {
        var target = packStore.load(targetId) ?: throw IllegalArgumentException("Pack inesistente")
        val sources = sourceIds.mapNotNull { packStore.load(it) }.filter { it.identifier != targetId }
        val total = target.stickers.size + sources.sumOf { it.stickers.size }
        var done = 0
        val failures = ArrayList<String>()

        if (target.animated != resultAnimated) {
            target = convertPackType(target, resultAnimated) { done = it; onProgress(done, total) }
        }
        done = target.stickers.size

        val incoming = ArrayList<PackStore.ConvertedSticker>()
        for (source in sources) {
            for (sticker in source.stickers) {
                coroutineContext.ensureActive()
                try {
                    val bytes = convertType(packStore.readSticker(source, sticker), resultAnimated)
                    incoming.add(PackStore.ConvertedSticker(bytes, resultAnimated, sticker.emojis, sticker.accessibilityText, sticker.sourceId))
                } catch (e: Exception) {
                    failures.add("${source.name}: ${e.message ?: e.javaClass.simpleName}")
                }
                done++
                onProgress(done, total)
            }
        }

        val free = (PackPlanner.MAX_STICKERS - target.stickers.size).coerceAtLeast(0)
        val toTarget = incoming.take(free)
        if (toTarget.isNotEmpty()) packStore.addStickers(targetId, toTarget) { StickerConverter.makeTrayIcon(it) }
        val overflow = incoming.drop(free).chunked(PackPlanner.MAX_STICKERS)
        val overflowPacks = ArrayList<StickerPack>()
        for ((i, chunk) in overflow.withIndex()) {
            val created = packStore.createPack("${target.name} ${i + 2}", target.publisher, resultAnimated)
            overflowPacks.add(packStore.addStickers(created.identifier, chunk) { StickerConverter.makeTrayIcon(it) })
        }
        if (deleteSources) sources.forEach { packStore.delete(it.identifier) }
        Report(toTarget.size, 0, overflowPacks, failures)
    }

    /** Converte tutti gli sticker di un pack al tipo indicato (fermo -> due fotogrammi, animato -> primo fotogramma). */
    suspend fun convertPackType(pack: StickerPack, animated: Boolean, onProgress: (Int) -> Unit = {}): StickerPack {
        if (pack.animated == animated) return pack
        val converted = pack.stickers.mapIndexed { i, sticker ->
            coroutineContext.ensureActive()
            onProgress(i)
            sticker to convertType(packStore.readSticker(pack, sticker), animated)
        }
        return packStore.replaceAllStickers(pack.identifier, animated, converted)
    }

    companion object {
        /** Adatta uno sticker WebP già convertito al tipo richiesto. */
        fun convertType(bytes: ByteArray, animated: Boolean): ByteArray {
            val isAnimated = WebPContainer.isAnimated(bytes) && WebPContainer.parse(bytes).frames.size > 1
            return when {
                animated && !isAnimated -> StickerConverter.animateStill(bytes).bytes
                !animated && isAnimated -> StickerConverter.convert(bytes, forceStatic = true).bytes
                else -> bytes
            }
        }
    }
}
