package com.whatik.data

import com.whatik.image.StickerConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Esegue un [PackPlanner.Plan]: converte ogni sticker selezionato e lo deposita nel
 * pack previsto (nuovo o esistente), riportando l'avanzamento.
 */
class StickerExporter(private val library: StickerLibrary, private val packStore: PackStore) {

    data class Failure(val itemName: String, val reason: String)

    data class Result(val packs: List<StickerPack>, val failures: List<Failure>)

    data class Config(
        val baseName: String,
        val publisher: String,
        val convertAnimatedToStatic: Boolean,
        val emojis: List<String>,
    )

    suspend fun export(
        plan: PackPlanner.Plan,
        config: Config,
        onProgress: (done: Int, total: Int, currentName: String) -> Unit,
    ): Result = withContext(Dispatchers.Default) {
        val total = plan.packs.sumOf { it.items.size }
        var done = 0
        val resultPacks = ArrayList<StickerPack>()
        val failures = ArrayList<Failure>()

        for (planned in plan.packs) {
            coroutineContext.ensureActive()
            val targetId = planned.existingIdentifier
                ?: packStore.createPack(PackPlanner.packName(config.baseName, plan, planned), config.publisher, planned.animated).identifier

            val converted = ArrayList<PackStore.ConvertedSticker>()
            for (plannedItem in planned.items) {
                coroutineContext.ensureActive()
                val item = library.find(plannedItem.id)
                if (item == null) {
                    failures.add(Failure(plannedItem.id, "Sticker non più in libreria"))
                    done++
                    continue
                }
                onProgress(done, total, item.displayName)
                try {
                    val bytes = library.file(item).readBytes()
                    val result = StickerConverter.convert(bytes, forceStatic = !planned.animated)
                    if (result.animated != planned.animated) {
                        failures.add(Failure(item.displayName, if (planned.animated) "non contiene un'animazione" else "risultato inatteso"))
                    } else {
                        converted.add(
                            PackStore.ConvertedSticker(
                                bytes = result.bytes,
                                animated = result.animated,
                                emojis = config.emojis,
                                accessibilityText = item.displayName.take(if (result.animated) 255 else 125),
                                sourceId = item.id,
                            ),
                        )
                    }
                } catch (e: Exception) {
                    failures.add(Failure(item.displayName, e.message ?: e.javaClass.simpleName))
                }
                done++
                onProgress(done, total, item.displayName)
            }

            if (converted.isNotEmpty()) {
                resultPacks.add(packStore.addStickers(targetId, converted) { StickerConverter.makeTrayIcon(it) })
            } else if (planned.isNew) {
                packStore.delete(targetId) // pack nuovo rimasto vuoto: niente da tenere
            } else {
                packStore.load(targetId)?.let { resultPacks.add(it) }
            }
        }
        Result(resultPacks, failures)
    }
}
