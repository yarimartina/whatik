package com.whatik.data

import kotlinx.serialization.Serializable

/** Uno sticker importato nella libreria dell'app (file originale, non ancora convertito). */
@Serializable
data class StickerItem(
    val id: String,
    val fileName: String,
    val displayName: String,
    val mimeType: String,
    val animated: Boolean,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val importedAt: Long,
    /** Da dove arriva: "share", "picker", "folder", "scan". */
    val source: String,
    val sha256: String,
)

/** Uno sticker già convertito e inserito in un pack. */
@Serializable
data class PackSticker(
    val fileName: String,
    val emojis: List<String>,
    val accessibilityText: String? = null,
    /** id dello [StickerItem] di origine, se ancora in libreria. */
    val sourceId: String? = null,
)

/** Un pack WhatsApp generato dall'app (cartella packs/<identifier>/). */
@Serializable
data class StickerPack(
    val identifier: String,
    val name: String,
    val publisher: String,
    val animated: Boolean,
    val stickers: List<PackSticker>,
    val createdAt: Long,
    val updatedAt: Long,
    val trayFile: String = "tray.png",
    /** Da incrementare a ogni modifica: WhatsApp lo usa per capire se ricaricare le immagini. */
    val imageDataVersion: Int = 1,
) {
    val isAddable: Boolean get() = stickers.size in PackPlanner.MIN_STICKERS..PackPlanner.MAX_STICKERS
}
