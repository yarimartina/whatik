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
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.whatik.R
import com.whatik.data.PackPlanner
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
        snackbarHost: @Composable () -> Unit = noSnackbar,
    ) {
        LibraryScreen(
            items = if (empty) emptyList() else fake.items,
            selected = selected,
            busy = false,
            fileOf = fake::file,
            snackbarHost = snackbarHost,
            onToggle = {}, onSelectAll = none, onClearSelection = none, onDeleteSelected = none, onCropSelected = none,
            onEdit = {}, onCapture = none, onImportVideo = none, onImportLink = none, onImportGallery = none,
            onImportFiles = none, onImportFolder = none, onScan = none, onOpenPacks = none, onExport = none,
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
    fun libraryImportMenu() {
        show { Library() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_import)).performClick()
        settle()
        snapScreen("library-import-menu")
    }

    @Test
    fun libraryDeleteConfirm() {
        show(dimmed = true) { Library(selected = selection) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_delete)).performClick()
        settle()
        snapScreen("library-delete-confirm")
    }

    @Test
    fun libraryInfo() {
        show(dimmed = true) { Library() }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.action_info)).performClick()
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
                snackbarHost = noSnackbar, onBack = none, onAddToWhatsApp = {}, onDelete = {}, onOpen = {},
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
                snackbarHost = noSnackbar, onBack = none, onAddToWhatsApp = {}, onDelete = {}, onOpen = {},
            )
        }
        snap("packs-empty")
    }

    @Composable
    private fun PackDetail(detail: PackDetailState, packIndex: Int = 0) {
        val pack = fake.packs[packIndex]
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

    @Test
    fun packDetail() {
        show { PackDetail(PackDetailState(packId = fake.packs[0].identifier)) }
        snap("pack-detail")
    }

    @Test
    fun packDetailSelection() {
        val pack = fake.packs[0]
        show {
            PackDetail(PackDetailState(packId = pack.identifier, selected = setOf("sticker_02.png", "sticker_03.png", "sticker_07.png")))
        }
        snap("pack-detail-selection")
    }

    @Test
    fun packDetailNotAdded() {
        show { PackDetail(PackDetailState(packId = fake.packs[1].identifier), packIndex = 1) }
        snap("pack-detail-animated")
    }

    @Test
    fun packDetailMerge() {
        show(dimmed = true) {
            PackDetail(PackDetailState(packId = fake.packs[0].identifier, merge = MergeState(sourceIds = setOf("pack_reazioni"))))
        }
        snapScreen("pack-detail-merge")
    }

    @Test
    fun packDetailRename() {
        showDirect(dimmed = true) { PackDetail(PackDetailState(packId = fake.packs[0].identifier, renaming = true)) }
        snapScreen("pack-detail-rename")
    }

    @Test
    fun packAddStickers() {
        show {
            AddToPackScreen(
                pack = fake.packs[1],
                items = fake.items,
                selected = setOf(fake.item(1).id, fake.item(5).id, fake.item(7).id),
                busy = false,
                fileOf = fake::file,
                snackbarHost = noSnackbar,
                onBack = none, onToggle = {}, onConfirm = none,
            )
        }
        snap("pack-add-stickers")
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
