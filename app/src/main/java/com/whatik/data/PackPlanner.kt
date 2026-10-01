package com.whatik.data

/**
 * Decide come distribuire gli sticker selezionati nei pack WhatsApp, rispettando le
 * regole di WhatsApp: da 3 a 30 sticker per pack e pack composti solo da sticker
 * statici oppure solo da sticker animati.
 *
 * Logica pura, senza dipendenze Android, per essere testabile sulla JVM.
 */
object PackPlanner {
    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30

    data class Item(val id: String, val animated: Boolean)

    data class Target(val identifier: String, val name: String, val animated: Boolean, val stickerCount: Int)

    data class PlannedPack(
        /** identificatore del pack esistente da estendere, oppure null per un pack nuovo. */
        val existingIdentifier: String?,
        val animated: Boolean,
        val items: List<Item>,
        /** numero di sticker che il pack avrà alla fine. */
        val resultingCount: Int,
        /** progressivo (1-based) usato per nominare i pack nuovi quando sono più di uno. */
        val newIndex: Int,
    ) {
        val isNew: Boolean get() = existingIdentifier == null
        val isAddable: Boolean get() = resultingCount in MIN_STICKERS..MAX_STICKERS
    }

    data class Plan(val packs: List<PlannedPack>) {
        val newPackCount: Int get() = packs.count { it.isNew }
        val tooSmall: List<PlannedPack> get() = packs.filter { !it.isAddable }
        val staticCount: Int get() = packs.filter { !it.animated }.sumOf { it.items.size }
        val animatedCount: Int get() = packs.filter { it.animated }.sumOf { it.items.size }
    }

    /**
     * @param convertAnimatedToStatic se true gli sticker animati vengono trattati come
     *        statici (verrà usato il primo fotogramma) e finiscono nei pack statici.
     * @param staticTarget pack esistente in cui inserire gli sticker statici (o null = pack nuovo).
     * @param animatedTarget pack esistente in cui inserire gli sticker animati (o null = pack nuovo).
     */
    fun plan(
        items: List<Item>,
        convertAnimatedToStatic: Boolean,
        staticTarget: Target? = null,
        animatedTarget: Target? = null,
    ): Plan {
        val normalized = if (convertAnimatedToStatic) items.map { it.copy(animated = false) } else items
        val packs = ArrayList<PlannedPack>()
        var newIndex = 0
        for (animated in listOf(false, true)) {
            val group = normalized.filter { it.animated == animated }
            if (group.isEmpty()) continue
            val target = if (animated) animatedTarget else staticTarget
            var remaining = group
            if (target != null && target.animated == animated) {
                val free = (MAX_STICKERS - target.stickerCount).coerceAtLeast(0)
                val take = remaining.take(free)
                if (take.isNotEmpty() || free == 0) {
                    packs.add(PlannedPack(target.identifier, animated, take, target.stickerCount + take.size, 0))
                }
                remaining = remaining.drop(take.size)
            }
            val chunks = remaining.chunked(MAX_STICKERS).map { it.toMutableList() }
            // Evita che l'ultimo pack nuovo resti sotto il minimo quando ce n'è più d'uno:
            // sposta qualche sticker dal pack precedente (che è pieno).
            if (chunks.size >= 2) {
                val last = chunks.last()
                val prev = chunks[chunks.size - 2]
                while (last.size < MIN_STICKERS && prev.size > MIN_STICKERS) {
                    last.add(0, prev.removeAt(prev.size - 1))
                }
            }
            for (chunk in chunks) {
                newIndex++
                packs.add(PlannedPack(null, animated, chunk, chunk.size, newIndex))
            }
        }
        return Plan(packs)
    }

    /** Nome del pack nuovo n-esimo: "Nome", "Nome 2", "Nome 3"... con suffisso per gli animati se serve. */
    fun packName(baseName: String, plan: Plan, pack: PlannedPack): String {
        val base = baseName.trim().ifEmpty { "Sticker" }
        val hasBothKinds = plan.packs.any { it.isNew && it.animated } && plan.packs.any { it.isNew && !it.animated }
        val kind = if (hasBothKinds) (if (pack.animated) " animati" else " statici") else ""
        val sameKindNew = plan.packs.filter { it.isNew && it.animated == pack.animated }
        val ordinal = if (sameKindNew.size > 1) " ${sameKindNew.indexOf(pack) + 1}" else ""
        return (base + kind + ordinal).take(128)
    }
}
