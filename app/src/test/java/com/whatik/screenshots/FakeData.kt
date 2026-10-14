package com.whatik.screenshots

import android.graphics.Bitmap
import android.net.Uri
import com.whatik.data.MediaCandidate
import com.whatik.data.PackSticker
import com.whatik.data.RemoteCandidate
import com.whatik.data.StickerItem
import com.whatik.data.StickerPack
import java.io.File

/**
 * Dati finti ma realistici per le schermate: libreria con sticker veri (PNG generati in una cartella
 * temporanea), pack, candidati della scansione e dei link. Gli id e i file restano gli stessi in
 * ogni test, così "prima" e "dopo" una modifica grafica sono confrontabili pixel per pixel.
 */
class FakeData(private val dir: File) {

    /** Data fissa (ms), per non avere mai valori che cambiano da una registrazione all'altra. */
    private val baseTime = 1_760_000_000_000L

    private val libraryNames = listOf(
        "Sorriso", "Cuore", "Risata", "Gatto", "Stella", "Va bene", "Occhiali", "Piango", "Wow", "Fuoco", "GG", "Fantasma",
        "Sorriso 2", "Cuore 2", "Risata 2", "Gatto 2", "Stella 2", "Va bene 2",
    )

    /** Indici (nella libreria) degli sticker animati: mostrano il badge "GIF". */
    private val animatedIndexes = setOf(2, 4, 9, 13, 16)

    private val libraryDir = File(dir, "library").apply { mkdirs() }

    private fun png(file: File, bitmap: Bitmap): File {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    /** Libreria: 18 sticker, qualcuno animato. */
    val items: List<StickerItem> = libraryNames.mapIndexed { i, name ->
        val id = "item%02d".format(i + 1)
        val fileName = "$id.png"
        png(File(libraryDir, fileName), FakeArt.sticker(i))
        StickerItem(
            id = id,
            fileName = fileName,
            displayName = name,
            mimeType = "image/png",
            animated = i in animatedIndexes,
            width = 512,
            height = 512,
            sizeBytes = 38_000L + i * 1_700L,
            importedAt = baseTime - i * 3_600_000L,
            source = if (i % 3 == 0) "capture" else "picker",
            sha256 = "%064x".format(i + 1),
        )
    }

    fun file(item: StickerItem): File = File(libraryDir, item.fileName)

    fun item(index: Int): StickerItem = items[index]

    private fun pack(
        id: String,
        name: String,
        animated: Boolean,
        stickerIndexes: List<Int>,
        updatedAgo: Long,
    ): StickerPack {
        val packDir = File(dir, "packs/$id")
        png(File(packDir, "tray.png"), FakeArt.sticker(stickerIndexes.first(), 96))
        val stickers = stickerIndexes.mapIndexed { n, art ->
            val fileName = "sticker_%02d.png".format(n + 1)
            png(File(packDir, fileName), FakeArt.sticker(art))
            PackSticker(fileName = fileName, emojis = listOf("😀"), sourceId = items[art % items.size].id)
        }
        return StickerPack(
            identifier = id,
            name = name,
            publisher = "Whatik",
            animated = animated,
            stickers = stickers,
            createdAt = baseTime - updatedAgo - 86_400_000L,
            updatedAt = baseTime - updatedAgo,
        )
    }

    /** Tre pack: statico già su WhatsApp, animato non ancora aggiunto, e uno troppo piccolo. */
    val packs: List<StickerPack> = listOf(
        pack("pack_gatti_memes", "Gatti e meme", animated = false, stickerIndexes = listOf(3, 0, 5, 6, 7, 8, 11, 1, 4, 10, 12, 15), updatedAgo = 1_000_000L),
        pack("pack_reazioni", "Reazioni TikTok", animated = true, stickerIndexes = listOf(2, 4, 9, 13, 16, 14, 17, 12, 0), updatedAgo = 90_000_000L),
        pack("pack_prova", "Prova", animated = false, stickerIndexes = listOf(1, 3), updatedAgo = 400_000_000L),
    )

    /** Pack già presenti su WhatsApp (identifier -> true). */
    val addedToWhatsApp: Map<String, Boolean> = mapOf("pack_gatti_memes" to true)

    fun trayOf(pack: StickerPack): File = File(dir, "packs/${pack.identifier}/${pack.trayFile}")

    fun stickerFile(pack: StickerPack, fileName: String): File = File(dir, "packs/${pack.identifier}/$fileName")

    // ------------------------------------------------------------ scansione della galleria

    private val scanDir = File(dir, "scan").apply { mkdirs() }

    /** Candidati della scansione: file TikTok (selezionati) e altre immagini del telefono. */
    val scanCandidates: List<MediaCandidate> = listOf(
        Triple("TikTok_sticker_2025-09-14_08-21.webp", "Pictures/TikTok/", true),
        Triple("TikTok_sticker_2025-09-14_08-22.webp", "Pictures/TikTok/", true),
        Triple("TikTok_sticker_2025-09-12_19-02.webp", "Pictures/TikTok/", true),
        Triple("sticker_5F2A91.gif", "Download/TikTok/", true),
        Triple("TikTok_sticker_2025-09-03_22-47.webp", "Pictures/TikTok/", true),
        Triple("Screenshot_20250915-101233.png", "Pictures/Screenshots/", false),
        Triple("IMG_20250911_174455.jpg", "DCIM/Camera/", false),
        Triple("WhatsApp Image 2025-09-02 at 20.14.07.jpeg", "Pictures/WhatsApp Images/", false),
    ).mapIndexed { i, (name, path, tiktok) ->
        val uri = Uri.parse("content://media/external/images/media/${4200 + i}")
        png(File(scanDir, "scan$i.png"), if (tiktok) FakeArt.sticker(i + 2) else FakeArt.screenFrame(360, 640, i))
        MediaCandidate(
            uri = uri,
            displayName = name,
            path = path,
            sizeBytes = 64_000L + i * 21_000L,
            mimeType = if (name.endsWith(".webp")) "image/webp" else "image/png",
            dateAddedSec = baseTime / 1000 - i * 86_400L,
            looksTikTok = tiktok,
        )
    }

    // ------------------------------------------------------------ importazione da link

    val linkPageUrl = "https://www.tiktok.com/@gattoblu/sticker/gatti-e-reazioni-7298331442"

    val linkCandidates: List<RemoteCandidate> = listOf(
        "sticker_cuore.awebp" to true,
        "sticker_risata.awebp" to true,
        "sticker_gatto.awebp" to true,
        "sticker_stella.awebp" to true,
        "sticker_ok.awebp" to true,
        "avatar_gattoblu_100x100.jpeg" to false,
        "cover_video_720.jpeg" to false,
    ).mapIndexed { i, (name, sticker) ->
        val url = "https://p16-sign-va.tiktokcdn.com/obj/tos-maliva-i-sticker/${"%08x".format(0x5a3c1000 + i * 977)}/$name"
        png(File(scanDir, "link$i.png"), if (sticker) FakeArt.sticker(i + 1) else FakeArt.sticker(i + 4, 256))
        RemoteCandidate(url = url, name = name, looksSticker = sticker)
    }

    /** Dati di immagine per i modelli Coil che non sono file locali (content:// e https://). */
    val remoteImages: Map<String, File> = buildMap {
        scanCandidates.forEachIndexed { i, c -> put(c.uri.toString(), File(scanDir, "scan$i.png")) }
        linkCandidates.forEachIndexed { i, c -> put(c.url, File(scanDir, "link$i.png")) }
    }
}
