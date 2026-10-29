package com.whatik.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class StickerLibraryTest {

    /** Contesto minimo: la libreria usa solo applicationContext e filesDir. */
    private class FakeContext(private val dir: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = dir
    }

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("whatik-library").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun newLibrary(name: String = "a") = StickerLibrary(FakeContext(File(root, name).apply { mkdirs() }))

    private fun indexOnDisk(name: String = "a"): List<StickerItem> {
        val file = File(root, "$name/library/index.json")
        if (!file.exists()) return emptyList()
        return Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(StickerItem.serializer()), file.readText())
    }

    /** GIF 2x2 valida e diversa per ogni [n] (cambia la palette), così ha un sha256 suo. */
    private fun gif(n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.write(byteArrayOf(2, 0, 2, 0, 0x80.toByte(), 0, 0)) // 2x2, tabella colori globale da 2 voci
        out.write(byteArrayOf(0, (n shr 8).toByte(), n.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 2, 0, 0, 2)) // immagine 2x2, min code size 2
        // LZW "ingenuo" da 3 bit: clear prima di ogni pixel (0 1 1 0), poi end
        var buffer = 0L
        var bits = 0
        val data = ByteArrayOutputStream()
        for (code in intArrayOf(4, 0, 4, 1, 4, 1, 4, 0, 5)) {
            buffer = buffer or (code.toLong() shl bits)
            bits += 3
            while (bits >= 8) { data.write((buffer and 0xFF).toInt()); buffer = buffer ushr 8; bits -= 8 }
        }
        if (bits > 0) data.write((buffer and 0xFF).toInt())
        out.write(data.size())
        out.write(data.toByteArray())
        out.write(0)
        out.write(0x3B)
        return out.toByteArray()
    }

    /** Ciò che deve essere uguale fra due importazioni degli stessi file (id, nome file e istante sono casuali). */
    private data class Stable(
        val displayName: String, val mimeType: String, val animated: Boolean, val width: Int, val height: Int,
        val sizeBytes: Long, val source: String, val sha256: String,
    )

    private fun stable(items: List<StickerItem>) =
        items.map { Stable(it.displayName, it.mimeType, it.animated, it.width, it.height, it.sizeBytes, it.source, it.sha256) }

    /** Quaranta GIF diverse, due copie di GIF già viste nel lotto, due copie di una già in libreria, un file vuoto. */
    private fun inputs(): List<Pair<String, ByteArray>> {
        val list = ArrayList<Pair<String, ByteArray>>()
        for (i in 0 until 40) {
            list.add("sticker $i.gif" to gif(i))
            if (i == 10 || i == 25) list.add("copia $i.gif" to gif(i - 3))
        }
        list.add("vuoto.gif" to ByteArray(0))
        list.add("gia presente.gif" to gif(1000))
        list.add("ancora uguale.gif" to gif(1000))
        return list
    }

    @Test
    fun batchImportProducesTheSameIndexAsOneFileAtATime() = runBlocking {
        val sequential = newLibrary("seq")
        val batch = newLibrary("batch")
        for (lib in listOf(sequential, batch)) {
            assertTrue(lib.importBytes(gif(1000), "gia presente.gif", "picker") is StickerLibrary.ImportResult.Added)
        }

        var added = 0; var duplicates = 0; var failed = 0
        for ((name, bytes) in inputs()) {
            when (sequential.importBytes(bytes, name, "folder")) {
                is StickerLibrary.ImportResult.Added -> added++
                is StickerLibrary.ImportResult.Duplicate -> duplicates++
                is StickerLibrary.ImportResult.Failed -> failed++
            }
        }
        val summary = batch.importBatch(inputs()) { (name, bytes) -> batch.importBytes(bytes, name, "folder", deferSave = true) }

        assertEquals(40, summary.added)
        assertEquals(4, summary.duplicates)
        assertEquals(1, summary.failed.size)
        assertEquals(added, summary.added)
        assertEquals(duplicates, summary.duplicates)
        assertEquals(failed, summary.failed.size)
        assertEquals(stable(sequential.items.value), stable(batch.items.value))
        // l'indice su disco è quello in memoria, nello stesso ordine (le più recenti per prime)
        assertEquals(batch.items.value, indexOnDisk("batch"))
        assertEquals(stable(sequential.items.value), stable(indexOnDisk("batch")))
    }

    @Test
    fun duplicatesAreFoundInTheBatchAndInTheLibrary() = runBlocking {
        val lib = newLibrary()
        val existing = (lib.importBytes(gif(5), "uno.gif", "picker") as StickerLibrary.ImportResult.Added).item
        val files = listOf("due.gif" to gif(6), "uno bis.gif" to gif(5), "due bis.gif" to gif(6), "tre.gif" to gif(7))
        val results = ArrayList<StickerLibrary.ImportResult>()
        val summary = lib.importBatch(files) { (name, bytes) -> lib.importBytes(bytes, name, "folder", deferSave = true).also { results.add(it) } }

        assertEquals(2, summary.added)
        assertEquals(2, summary.duplicates)
        // un doppione restituisce la voce già in libreria, non una nuova
        assertEquals(existing.id, (results[1] as StickerLibrary.ImportResult.Duplicate).item.id)
        assertEquals((results[0] as StickerLibrary.ImportResult.Added).item.id, (results[2] as StickerLibrary.ImportResult.Duplicate).item.id)
        assertEquals(listOf("tre", "due", "uno"), lib.items.value.map { it.displayName })
    }

    @Test
    fun indexIsNotRewrittenForEveryFileOfABatch() = runBlocking {
        val lib = newLibrary()
        val files = List(130) { "s$it.gif" to gif(it) }
        var imported = 0
        lib.importBatch(files) { (name, bytes) ->
            lib.importBytes(bytes, name, "folder", deferSave = true).also {
                imported++
                val onDisk = indexOnDisk().size
                // l'indice su disco non resta mai indietro di 50 o più voci, ma non è riscritto a ogni file
                assertTrue("$imported importati, $onDisk su disco", imported - onDisk in 0..49)
                if (imported == 1) assertEquals(0, onDisk)
            }
        }
        assertEquals(130, indexOnDisk().size)
        assertEquals(130, lib.items.value.size)
    }

    @Test
    fun singleImportsAreSavedImmediately() = runBlocking {
        val lib = newLibrary()
        for (i in 0 until 3) {
            lib.importBytes(gif(i), "s$i.gif", "share")
            assertEquals(i + 1, indexOnDisk().size)
        }
    }

    @Test
    fun failureInTheMiddleOfABatchKeepsTheCopiedStickers() {
        val lib = newLibrary()
        val files = List(20) { "s$it.gif" to gif(it) }
        var imported = 0
        try {
            runBlocking {
                lib.importBatch(files) { (name, bytes) ->
                    if (imported == 7) throw IllegalStateException("errore a metà")
                    imported++
                    lib.importBytes(bytes, name, "folder", deferSave = true)
                }
            }
            throw AssertionError("atteso l'errore")
        } catch (e: IllegalStateException) {
            assertEquals("errore a metà", e.message)
        }
        assertEquals(7, indexOnDisk().size)
        assertEquals(lib.items.value, indexOnDisk())
        // e dopo un riavvio i file ci sono tutti
        assertEquals(lib.items.value, newLibrary().items.value)
    }

    @Test
    fun cancelledBatchKeepsTheCopiedStickers() = runBlocking {
        val lib = newLibrary()
        val files = List(20) { "s$it.gif" to gif(it) }
        var imported = 0
        val job = launch(Dispatchers.Default) {
            lib.importBatch(files) { (name, bytes) ->
                lib.importBytes(bytes, name, "folder", deferSave = true).also {
                    if (++imported == 7) currentCoroutineContext().cancel()
                }
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(7, lib.items.value.size)
        assertEquals(lib.items.value, indexOnDisk())
        assertEquals(lib.items.value, newLibrary().items.value)
    }

    @Test
    fun deleteKeepsDuplicateDetectionInSync() = runBlocking {
        val lib = newLibrary()
        val a = (lib.importBytes(gif(1), "a.gif", "picker") as StickerLibrary.ImportResult.Added).item
        val b = (lib.importBytes(gif(2), "b.gif", "picker") as StickerLibrary.ImportResult.Added).item
        lib.delete(setOf(a.id))
        assertFalse(lib.file(a).exists())
        // a non c'è più: si può reimportare; b c'è ancora: è un doppione
        assertTrue(lib.importBytes(gif(1), "a.gif", "picker") is StickerLibrary.ImportResult.Added)
        assertEquals(b.id, (lib.importBytes(gif(2), "b.gif", "picker") as StickerLibrary.ImportResult.Duplicate).item.id)
        // anche dopo il riavvio
        val reloaded = newLibrary()
        assertEquals(b.id, (reloaded.importBytes(gif(2), "b.gif", "picker") as StickerLibrary.ImportResult.Duplicate).item.id)
    }
}
