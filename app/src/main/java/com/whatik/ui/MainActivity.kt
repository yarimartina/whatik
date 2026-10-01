package com.whatik.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.whatik.R
import com.whatik.WhatikApp
import com.whatik.data.MediaScanner
import com.whatik.data.StickerPack
import com.whatik.ui.theme.WhatikTheme
import com.whatik.whatsapp.WhatsAppBridge

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) viewModel.handleIntent(intent)
        setContent {
            WhatikTheme {
                WhatikRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshWhatsAppStatus()
    }
}

@Composable
fun WhatikRoot(vm: MainViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as WhatikApp
    val screen by vm.screen.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val packs by vm.packs.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val scanState by vm.scanState.collectAsStateWithLifecycle()
    val linkState by vm.linkState.collectAsStateWithLifecycle()
    val editorState by vm.editorState.collectAsStateWithLifecycle()
    val exportState by vm.exportState.collectAsStateWithLifecycle()
    val addedToWhatsApp by vm.addedToWhatsApp.collectAsStateWithLifecycle()
    val snackbarState = remember { SnackbarHostState() }
    var showLinkDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbarState.showSnackbar(it) }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        vm.importUris(uris, "picker")
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.openVideoEditor(it, null) }
    }
    val openDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importUris(uris, "picker")
    }
    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { vm.importFolder(it) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.onPermissionResult()
    }
    val whatsAppLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.notify(context.getString(R.string.msg_whatsapp_added))
        } else {
            val error = result.data?.getStringExtra(WhatsAppBridge.EXTRA_VALIDATION_ERROR)
            vm.notify(
                if (error.isNullOrBlank()) context.getString(R.string.msg_whatsapp_cancelled)
                else context.getString(R.string.msg_whatsapp_error, error),
            )
        }
        vm.refreshWhatsAppStatus()
    }
    val addToWhatsApp: (StickerPack) -> Unit = { pack ->
        val intent = WhatsAppBridge.addPackIntent(context, pack)
        if (intent == null) vm.notify(context.getString(R.string.msg_whatsapp_missing)) else whatsAppLauncher.launch(intent)
    }

    BackHandler(enabled = screen != Screen.LIBRARY || selected.isNotEmpty()) { vm.back() }

    val snackbarHost: @Composable () -> Unit = { SnackbarHost(snackbarState) }

    when (screen) {
        Screen.LIBRARY -> LibraryScreen(
            items = items,
            selected = selected,
            busy = busy,
            fileOf = { app.library.file(it) },
            snackbarHost = snackbarHost,
            onToggle = vm::toggle,
            onSelectAll = vm::selectAll,
            onClearSelection = vm::clearSelection,
            onDeleteSelected = vm::deleteSelected,
            onCropSelected = { selected.singleOrNull()?.let { vm.openImageEditor(it) } },
            onImportVideo = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            onImportLink = { showLinkDialog = true },
            onImportGallery = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onImportFiles = { openDocuments.launch(arrayOf("image/*")) },
            onImportFolder = { openTree.launch(null) },
            onScan = vm::openScan,
            onOpenPacks = { vm.navigate(Screen.PACKS) },
            onExport = vm::openExport,
        )
        Screen.SCAN -> ScanScreen(
            state = scanState,
            busy = busy,
            snackbarHost = snackbarHost,
            onBack = { vm.back() },
            onRequestPermission = { permissions.launch(MediaScanner.requiredPermissions()) },
            onRescan = vm::startScan,
            onToggle = vm::toggleScanCandidate,
            onFilter = vm::setScanFilter,
            onSelectAllVisible = vm::scanSelectAllVisible,
            onImport = vm::importScanSelection,
        )
        Screen.PACKS -> PacksScreen(
            packs = packs,
            addedToWhatsApp = addedToWhatsApp,
            trayOf = { app.packStore.trayFile(it) },
            stickerFilesOf = { pack -> pack.stickers.map { app.packStore.stickerFile(pack, it) } },
            snackbarHost = snackbarHost,
            onBack = { vm.back() },
            onAddToWhatsApp = addToWhatsApp,
            onDelete = vm::deletePack,
        )
        Screen.EDITOR -> {
            val state = editorState
            if (state != null) {
                EditorScreen(
                    state = state,
                    snackbarHost = snackbarHost,
                    onBack = vm::closeEditor,
                    onCropChange = vm::updateCrop,
                    onRangeChange = vm::updateRange,
                    onNameChange = vm::updateEditorName,
                    onCreate = vm::createStickerFromEditor,
                    onApplyProposal = vm::applyProposal,
                    onCreateAll = vm::createAllProposals,
                    onDetectAgain = vm::detectStickers,
                )
            } else {
                vm.navigate(Screen.LIBRARY)
            }
        }
        Screen.LINK_RESULTS -> LinkResultsScreen(
            state = linkState,
            busy = busy,
            snackbarHost = snackbarHost,
            onBack = { vm.back() },
            onToggle = vm::toggleLinkCandidate,
            onFilter = vm::setLinkFilter,
            onSelectAllVisible = vm::linkSelectAllVisible,
            onImport = vm::importLinkSelection,
        )
    }

    if (showLinkDialog) {
        LinkDialog(
            onConfirm = { text ->
                showLinkDialog = false
                vm.importFromLink(text)
            },
            onDismiss = { showLinkDialog = false },
        )
    }

    when (val state = exportState) {
        is ExportState.Hidden -> Unit
        is ExportState.Configuring -> ExportDialog(
            config = state,
            plan = vm.currentPlan(state),
            packs = packs,
            onChange = vm::updateExportConfig,
            onConfirm = vm::runExport,
            onDismiss = vm::dismissExport,
        )
        is ExportState.Running -> ProgressDialog(state, onCancel = vm::dismissExport)
        is ExportState.Finished -> {
            var autoLaunched by remember(state) { mutableStateOf(false) }
            LaunchedEffect(state) {
                val only = state.result.packs.singleOrNull()
                if (!autoLaunched && only != null && only.isAddable && state.result.failures.isEmpty() &&
                    addedToWhatsApp[only.identifier] != true && WhatsAppBridge.isAnyWhatsAppInstalled(context)
                ) {
                    autoLaunched = true
                    addToWhatsApp(only)
                }
            }
            ResultDialog(
                result = state.result,
                addedToWhatsApp = addedToWhatsApp,
                onAddToWhatsApp = addToWhatsApp,
                onOpenPacks = {
                    vm.dismissExport()
                    vm.navigate(Screen.PACKS)
                },
                onDismiss = vm::dismissExport,
            )
        }
    }
}
