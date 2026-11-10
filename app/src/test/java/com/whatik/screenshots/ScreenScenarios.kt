package com.whatik.screenshots

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.whatik.R
import com.whatik.data.PackPlanner
import com.whatik.data.StickerPack
import com.whatik.data.StickerExporter
import com.whatik.image.CropSpec
import com.whatik.image.StickerProposal
import com.whatik.image.VideoFrameProducer
import com.whatik.ui.AddToPackScreen
import com.whatik.ui.CaptureScreen
import com.whatik.ui.CaptureUiState
import com.whatik.ui.EditorScreen
import com.whatik.ui.EditorSource
import com.whatik.ui.EditorState
import com.whatik.ui.ExportDialog
import com.whatik.ui.ExportState
import com.whatik.ui.LibraryScreen
import com.whatik.ui.LinkDialog
import com.whatik.ui.LinkResultsScreen
import com.whatik.ui.LinkState
import com.whatik.ui.MergeState
import com.whatik.ui.PackDetailScreen
import com.whatik.ui.PackDetailState
import com.whatik.ui.PacksScreen
import com.whatik.ui.ProgressDialog
import com.whatik.ui.ResultDialog
import com.whatik.ui.ScanScreen
import com.whatik.ui.ScanState
import com.whatik.ui.components.TopLevelDestination
import com.whatik.ui.components.WhatikNavigationBar
import org.junit.Test
import java.io.File

/**
 * Una funzione di test per schermata. Le schermate dell'app sono composable senza stato (ricevono i dati
 * come parametri), quindi si costruiscono direttamente con i dati finti di [FakeData]: non serve il
 * MainViewModel. I due test concreti (chiaro e scuro) ereditano tutti i casi.
 */
abstract class ScreenScenarios(suffix: String) : ScreenshotTestBase(suffix) {

    private val noSnackbar: @Composable () -> Unit = {}
    private val none: () -> Unit = {}

    // ------------------------------------------------------------ libreria

    private val selection get() = setOf(fake.item(1).id, fake.item(3).id, fake.item(5).id, fake.item(8).id)

    @Composable
    private fun Library(
        empty: Boolean = false,
        selected: Set<String> = emptySet(),
        busy: Boolean = false,
        snackbarHost: @Composable () -> Unit = noSnackbar,
    ) {
        LibraryScreen(
            items = if (empty) emptyList() else fake.items,
            selected = selected,
            busy = busy,
            fileOf = fake::file,
            snackbarHost = snackbarHost,
            onToggle = {}, onSelectAll = none, onClearSelection = none, onDeleteSelected = none, onCropSelected = none,
            onEdit = {}, onCapture = none, onImportVideo = none, onImportLink = none, onImportGallery = none,
            onImportFiles = none, onImportFolder = none, onScan = none, onOpenPacks = none, onExport = none,
            bottomBar = { WhatikNavigationBar(current = TopLevelDestination.LIBRARY, onSelect = {}, captureActive = false) },
        )
    }

    @Test
    fun libraryEmpty() {
        show { Library(empty = true) }
        snap("library-empty")
    }

    @Test
    fun libraryGrid() {
        show { Library() }
        snap("library-grid")
    }

    @Test
    fun librarySnackbar() {
        show {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { host.showSnackbar("3 sticker importati") }
            Library(snackbarHost = { SnackbarHost(host) })
        }
        snap("library-snackbar")
    }

    @Test
    fun librarySelection() {
        show { Library(selected = selection) }
        snap("library-selection")
    }

    @Test
    fun libraryBusy() {
        show { Library(busy = true) }
        snap("library-busy")
    }

    @Test
    fun libraryMoreMenu() {
        show { Library() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_more)).performClick()
        settle()
        snapScreen("library-more-menu")
    }

    @Test
    fun libraryDeleteConfirm() {
        show(dimmed = true) { Library(selected = selection) }
        compose.onNodeWithText(compose.activity.getString(R.string.action_delete)).performClick()
        settle()
        snapScreen("library-delete-confirm")
    }

    @Test
    fun libraryInfo() {
        show(dimmed = true) { Library() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_more)).performClick()
        settle()
        compose.onNodeWithText(compose.activity.getString(R.string.action_info)).performClick()
        settle()
        snapScreen("library-info")
    }

    // ------------------------------------------------------------ esportazione (dialoghi sopra la libreria)

    private val exportIndexes = listOf(0, 1, 2, 3, 4, 5, 6, 9, 10)

    private val exportItems get() = exportIndexes.map { PackPlanner.Item(fake.item(it).id, fake.item(it).animated) }

    private val exportConfig
        get() = ExportState.Configuring(
            items = exportItems,
            baseName = "Meme del giorno",
            publisher = "Whatik",
            mixMode = PackPlanner.MixMode.SEPARATE,
            staticTargetId = null,
            animatedTargetId = "pack_reazioni",
        )

    @Test
    fun exportConfig() {
        val config = exportConfig
        val target = fake.packs[1]
        val plan = PackPlanner.plan(
            config.items,
            config.mixMode,
            animatedTarget = PackPlanner.Target(target.identifier, target.name, target.animated, target.stickers.size),
        )
        showDirect(dimmed = true) {
            Library(selected = exportIndexes.map { fake.item(it).id }.toSet())
            ExportDialog(config = config, plan = plan, packs = fake.packs, onChange = {}, onConfirm = none, onDismiss = none)
        }
        snapScreen("export-config")
    }

    @Test
    fun exportProgress() {
        show(dimmed = true) {
            Library(selected = exportIndexes.map { fake.item(it).id }.toSet())
            ProgressDialog(ExportState.Running(done = 4, total = 9, currentName = "Gatto"), onCancel = none)
        }
        snapScreen("export-progress")
    }

    @Test
    fun exportResult() {
        show(dimmed = true) {
            Library(selected = exportIndexes.map { fake.item(it).id }.toSet())
            ResultDialog(
                result = StickerExporter.Result(
                    packs = listOf(fake.packs[0], fake.packs[1], fake.packs[2]),
                    failures = listOf(StickerExporter.Failure("Occhiali", "immagine troppo grande anche a qualità minima")),
                ),
                addedToWhatsApp = fake.addedToWhatsApp,
                onAddToWhatsApp = {}, onOpenPacks = none, onDismiss = none,
            )
        }
        snapScreen("export-result")
    }

    // ------------------------------------------------------------ cattura da TikTok

    @Test
    fun captureSetup() {
        show {
            CaptureScreen(
                state = CaptureUiState(overlayGranted = true, notificationsGranted = false, notificationsRequired = true),
                snackbarHost = noSnackbar, onBack = none, onGrantOverlay = none, onGrantNotifications = none,
                onStart = none, onStop = none, onOpenTikTok = none,
            )
        }
        snap("capture-setup")
    }

    @Test
    fun captureRunning() {
        val created = listOf(fake.item(0), fake.item(2), fake.item(6), fake.item(9))
        show {
            CaptureScreen(
                state = CaptureUiState(
                    overlayGranted = true, notificationsGranted = true, notificationsRequired = true, running = true,
                    message = "Pronto: tocca la bolla", createdIds = created.map { it.id },
                ),
                snackbarHost = noSnackbar, onBack = none, onGrantOverlay = none, onGrantNotifications = none,
                onStart = none, onStop = none, onOpenTikTok = none,
                capturedItems = created, fileOf = fake::file, onReviewCaptured = none,
            )
        }
        snap("capture-running")
    }

    // ------------------------------------------------------------ scansione della galleria

    @Test
    fun scanResults() {
        show {
            ScanScreen(
                state = ScanState.Loaded(
                    candidates = fake.scanCandidates,
                    selected = fake.scanCandidates.filter { it.looksTikTok }.map { it.uri }.toSet(),
                    onlyTikTok = true,
                ),
                busy = false, snackbarHost = noSnackbar, onBack = none, onRequestPermission = none, onRescan = none,
                onToggle = {}, onFilter = {}, onSelectAllVisible = {}, onImport = none,
            )
        }
        snap("scan-results")
    }

    @Test
    fun scanAllImages() {
        show {
            ScanScreen(
                state = ScanState.Loaded(
                    candidates = fake.scanCandidates,
                    selected = setOf(fake.scanCandidates[0].uri, fake.scanCandidates[6].uri),
                    onlyTikTok = false,
                ),
                busy = false, snackbarHost = noSnackbar, onBack = none, onRequestPermission = none, onRescan = none,
                onToggle = {}, onFilter = {}, onSelectAllVisible = {}, onImport = none,
            )
        }
        snap("scan-all-images")
    }

    @Test
    fun scanPermission() {
        show {
            ScanScreen(
                state = ScanState.PermissionDenied,
                busy = false, snackbarHost = noSnackbar, onBack = none, onRequestPermission = none, onRescan = none,
                onToggle = {}, onFilter = {}, onSelectAllVisible = {}, onImport = none,
            )
        }
        snap("scan-permission")
    }

    // ------------------------------------------------------------ importazione da link

    @Test
    fun linkResults() {
        show {
            LinkResultsScreen(
                state = LinkState.Results(
                    pageUrl = fake.linkPageUrl,
                    candidates = fake.linkCandidates,
                    selected = fake.linkCandidates.filter { it.looksSticker }.map { it.url }.take(4).toSet(),
                    onlyStickers = true,
                ),
                busy = false, snackbarHost = noSnackbar, onBack = none,
                onToggle = {}, onFilter = {}, onSelectAllVisible = {}, onImport = none,
            )
        }
        snap("link-results")
    }

    @Test
    fun linkDialog() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("link", "https://vm.tiktok.com/ZMr4kLq8d/"))
        showDirect(dimmed = true) {
            Library()
            LinkDialog(onConfirm = {}, onDismiss = none)
        }
        snapScreen("link-dialog")
    }

    // ------------------------------------------------------------ pack

    @Test
    fun packsList() {
        show {
            PacksScreen(
                packs = fake.packs, addedToWhatsApp = fake.addedToWhatsApp,
                trayOf = fake::trayOf,
                stickerFilesOf = { pack -> pack.stickers.map { fake.stickerFile(pack, it.fileName) } },
                snackbarHost = noSnackbar, onAddToWhatsApp = {}, onOpen = {}, onGoToLibrary = none,
                bottomBar = { WhatikNavigationBar(TopLevelDestination.PACKS, onSelect = {}, captureActive = false) },
            )
        }
        snap("packs-list")
    }

    @Test
    fun packsEmpty() {
        show {
            PacksScreen(
                packs = emptyList(), addedToWhatsApp = emptyMap(),
                trayOf = fake::trayOf, stickerFilesOf = { emptyList() },
                snackbarHost = noSnackbar, onAddToWhatsApp = {}, onOpen = {}, onGoToLibrary = none,
                bottomBar = { WhatikNavigationBar(TopLevelDestination.PACKS, onSelect = {}, captureActive = false) },
            )
        }
        snap("packs-empty")
    }

    @Composable
    private fun PackDetail(detail: PackDetailState, packIndex: Int = 0, pack: StickerPack = fake.packs[packIndex]) {
        PackDetailScreen(
            pack = pack,
            detail = detail,
            allPacks = fake.packs,
            added = fake.addedToWhatsApp[pack.identifier] == true,
            stickerFileOf = { p, name -> fake.stickerFile(p, name) },
            snackbarHost = noSnackbar,
            onBack = none, onToggleSticker = {}, onClearSelection = none, onRemoveSelected = none, onAddStickers = none,
            onOpenMerge = none, onDismissMerge = none, onToggleMergeSource = {}, onMergeResultAnimated = {},
            onMergeDeleteSources = {}, onConfirmMerge = none, onOpenRename = none, onDismissRename = none,
            onRename = { _, _ -> }, onDelete = none, onAddToWhatsApp = none,
        )
    }

    private val packSelection = setOf("sticker_02.png", "sticker_03.png", "sticker_07.png")

    @Test
    fun packDetail() {
        show { PackDetail(PackDetailState(packId = fake.packs[0].identifier)) }
        snap("pack-detail")
    }

    @Test
    fun packDetailSelection() {
        show { PackDetail(PackDetailState(packId = fake.packs[0].identifier, selected = packSelection)) }
        snap("pack-detail-selection")
    }

    @Test
    fun packDetailNotAdded() {
        show { PackDetail(PackDetailState(packId = fake.packs[1].identifier), packIndex = 1) }
        snap("pack-detail-animated")
    }

    @Test
    fun packDetailIncomplete() {
        show { PackDetail(PackDetailState(packId = fake.packs[2].identifier), packIndex = 2) }
        snap("pack-detail-incomplete")
    }

    @Test
    fun packDetailBusy() {
        show { PackDetail(PackDetailState(packId = fake.packs[0].identifier, progress = 3 to 8)) }
        snap("pack-detail-busy")
    }

    @Test
    fun packDetailEmpty() {
        val empty = fake.packs[0].copy(stickers = emptyList())
        show { PackDetail(PackDetailState(packId = empty.identifier), pack = empty) }
        snap("pack-detail-empty")
    }

    @Test
    fun packDetailMenu() {
        show { PackDetail(PackDetailState(packId = fake.packs[0].identifier)) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_more)).performClick()
        settle()
        snapScreen("pack-detail-menu")
    }

    @Test
    fun packDetailRemoveConfirm() {
        show(dimmed = true) { PackDetail(PackDetailState(packId = fake.packs[0].identifier, selected = packSelection)) }
        compose.onNodeWithText(compose.activity.getString(R.string.pack_action_remove)).performClick()
        settle()
        snapScreen("pack-detail-remove-confirm")
    }

    @Test
    fun packDetailDeleteConfirm() {
        show(dimmed = true) { PackDetail(PackDetailState(packId = fake.packs[0].identifier)) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_more)).performClick()
        settle()
        compose.onNodeWithText(compose.activity.getString(R.string.pack_action_delete)).performClick()
        settle()
        snapScreen("pack-detail-delete-confirm")
    }

    @Test
    fun packDetailMerge() {
        show(dimmed = true) {
            PackDetail(PackDetailState(packId = fake.packs[0].identifier, merge = MergeState(sourceIds = setOf("pack_reazioni"), deleteSources = false)))
        }
        snapScreen("pack-detail-merge")
    }

    @Test
    fun packDetailMergeDelete() {
        show(dimmed = true) {
            PackDetail(PackDetailState(packId = fake.packs[0].identifier, merge = MergeState(sourceIds = setOf("pack_reazioni", "pack_prova"), deleteSources = true)))
        }
        snapScreen("pack-detail-merge-delete")
    }

    @Test
    fun packDetailRename() {
        showDirect(dimmed = true) { PackDetail(PackDetailState(packId = fake.packs[0].identifier, renaming = true)) }
        snapScreen("pack-detail-rename")
    }

    @Composable
    private fun AddToPack(
        pack: StickerPack = fake.packs[1],
        items: List<com.whatik.data.StickerItem> = fake.items,
        selected: Set<String>,
        progress: Pair<Int, Int>? = null,
    ) {
        AddToPackScreen(
            pack = pack,
            items = items,
            selected = selected,
            progress = progress,
            fileOf = fake::file,
            snackbarHost = noSnackbar,
            onBack = none, onToggle = {}, onConfirm = none,
        )
    }

    @Test
    fun packAddStickers() {
        show { AddToPack(selected = setOf(fake.item(1).id, fake.item(5).id, fake.item(7).id)) }
        snap("pack-add-stickers")
    }

    @Test
    fun packAddBusy() {
        show { AddToPack(selected = setOf(fake.item(1).id), progress = 2 to 5) }
        snap("pack-add-busy")
    }

    @Test
    fun packAddFull() {
        // pack con 27 sticker: 3 posti liberi, tutti scelti
        val base = fake.packs[0]
        val extra = (1..15).map { com.whatik.data.PackSticker(fileName = "extra_$it.png", emojis = listOf("\uD83D\uDE00")) }
        val almostFull = base.copy(stickers = base.stickers + extra)
        val inPack = almostFull.stickers.mapNotNull { it.sourceId }.toSet()
        val chosen = fake.items.filter { it.id !in inPack }.take(3).map { it.id }.toSet()
        show { AddToPack(pack = almostFull, selected = chosen) }
        snap("pack-add-full")
    }

    @Test
    fun packAddEmpty() {
        show { AddToPack(items = emptyList(), selected = emptySet()) }
        snap("pack-add-empty")
    }

    // ------------------------------------------------------------ editor

    private fun editor(
        source: EditorSource,
        name: String,
        crop: CropSpec,
        preview: android.graphics.Bitmap?,
        endMs: Long = 0,
        proposals: List<StickerProposal>? = null,
        selectedProposal: Int? = null,
        keepOriginal: Boolean = true,
        converting: Boolean = false,
        progress: Pair<Int, Int>? = null,
    ) = EditorState(
        source = source, name = name, crop = crop, startMs = 0, endMs = endMs, previewTimeMs = 0, preview = preview,
        converting = converting, progress = progress, proposals = proposals, selectedProposal = selectedProposal,
        keepOriginal = keepOriginal,
    )

    @Composable
    private fun Editor(state: EditorState) {
        EditorScreen(
            state = state, snackbarHost = noSnackbar, onBack = none, onCropChange = {}, onRangeChange = { _, _ -> },
            onNameChange = {}, onCreate = none, onApplyProposal = {}, onCreateAll = none, onDetectAgain = none,
            onKeepOriginalChange = {},
        )
    }

    /** Screenshot con più sticker aperto dalla libreria: si ritaglia il cuore, tenendo anche l'originale. */
    @Test
    fun editorImage() {
        val sheet = FakeArt.sheet(cols = 2, rows = 2, firstIndex = 1)
        val state = editor(
            source = EditorSource.Image(ByteArray(0), sheet.width, sheet.height, animated = false, replaceItemId = fake.item(1).id),
            name = "Cuore", crop = CropSpec(0.03f, 0.03f, 0.5f, 0.5f), preview = sheet,
        )
        show { Editor(state) }
        snap("editor-image")
    }

    /** Sticker animato: oltre al riquadro compare l'intervallo di tempo da tenere. */
    @Test
    fun editorAnimated() {
        val art = FakeArt.sticker(2, 480)
        val state = editor(
            source = EditorSource.Image(ByteArray(0), 480, 480, animated = true, replaceItemId = fake.item(2).id, durationsMs = List(24) { 80 }),
            name = "Risata", crop = CropSpec(0.05f, 0.05f, 0.95f, 0.95f), preview = art, endMs = 1520,
        )
        show { Editor(state) }
        snap("editor-animated")
    }

    /** Registrazione dello schermo analizzata: sticker trovati in automatico e riquadro sul secondo. */
    @Test
    fun editorVideo() {
        val frame = FakeArt.screenFrame()
        // una proposta per ognuna delle prime sei tessere del pannello disegnato in FakeArt.screenFrame
        val proposals = FakeArt.screenFrameTiles().take(6).mapIndexed { i, tile ->
            StickerProposal(
                crop = CropSpec(tile.left / 1080f, tile.top / 2400f, tile.right / 1080f, tile.bottom / 2400f),
                startMs = 0, endMs = 3200, preview = FakeArt.sticker(i, 256), score = 0.9f - i * 0.05f,
            )
        }
        val state = editor(
            source = EditorSource.Video(File("registrazione.mp4"), VideoFrameProducer.VideoInfo(1080, 2400, 8000)),
            name = "Sticker 2", crop = proposals[1].crop, preview = frame, endMs = 3200,
            proposals = proposals, selectedProposal = 1,
        )
        show { Editor(state) }
        snap("editor-video")
    }

    @Test
    fun editorConverting() {
        val sheet = FakeArt.sheet(cols = 2, rows = 2, firstIndex = 4)
        val state = editor(
            source = EditorSource.Image(ByteArray(0), sheet.width, sheet.height, animated = false, replaceItemId = null),
            name = "Stella", crop = CropSpec(0.5f, 0.03f, 0.97f, 0.5f), preview = sheet,
            converting = true, progress = 0 to 0,
        )
        show { Editor(state) }
        snap("editor-converting")
    }
}
