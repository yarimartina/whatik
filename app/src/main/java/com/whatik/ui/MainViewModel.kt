package com.whatik.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whatik.WhatikApp
import com.whatik.data.MediaCandidate
import com.whatik.data.MediaScanner
import com.whatik.data.PackPlanner
import com.whatik.data.StickerExporter
import com.whatik.data.StickerItem
import com.whatik.data.StickerLibrary
import com.whatik.data.StickerPack
import com.whatik.whatsapp.WhatsAppBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { LIBRARY, SCAN, PACKS }

sealed class ScanState {
    data object Idle : ScanState()
    data object Loading : ScanState()
    data object PermissionDenied : ScanState()
    data class Loaded(
        val candidates: List<MediaCandidate>,
        val selected: Set<Uri>,
        val onlyTikTok: Boolean,
    ) : ScanState() {
        val visible: List<MediaCandidate> get() = if (onlyTikTok) candidates.filter { it.looksTikTok } else candidates
        val tiktokCount: Int get() = candidates.count { it.looksTikTok }
    }
}

sealed class ExportState {
    data object Hidden : ExportState()
    data class Configuring(
        val items: List<PackPlanner.Item>,
        val baseName: String,
        val publisher: String,
        val convertAnimatedToStatic: Boolean,
        val staticTargetId: String?,
        val animatedTargetId: String?,
    ) : ExportState()
    data class Running(val done: Int, val total: Int, val currentName: String) : ExportState()
    data class Finished(val result: StickerExporter.Result) : ExportState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WhatikApp
    private val library: StickerLibrary = app.library

    val items: StateFlow<List<StickerItem>> = library.items
    val packs: StateFlow<List<StickerPack>> = app.packStore.packs

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _screen = MutableStateFlow(Screen.LIBRARY)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Hidden)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Pack già presenti su WhatsApp (identifier -> true). */
    private val _addedToWhatsApp = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val addedToWhatsApp: StateFlow<Map<String, Boolean>> = _addedToWhatsApp.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var exportJob: Job? = null

    // ------------------------------------------------------------ navigazione

    fun navigate(screen: Screen) {
        _screen.value = screen
        if (screen == Screen.PACKS) refreshWhatsAppStatus()
    }

    fun back(): Boolean {
        if (_screen.value != Screen.LIBRARY) {
            _screen.value = Screen.LIBRARY
            return true
        }
        if (_selected.value.isNotEmpty()) {
            clearSelection()
            return true
        }
        return false
    }

    // ------------------------------------------------------------ selezione

    fun toggle(id: String) = _selected.update { if (id in it) it - id else it + id }

    fun selectAll() {
        _selected.value = items.value.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun deleteSelected() {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            library.delete(ids)
            _selected.value = emptySet()
            _messages.emit(app.getString(com.whatik.R.string.msg_deleted, ids.size))
        }
    }

    // ------------------------------------------------------------ importazione

    fun importUris(uris: List<Uri>, source: String) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            val summary = library.importUris(uris, source)
            _busy.value = false
            _messages.emit(summaryMessage(summary))
        }
    }

    fun importFolder(treeUri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            val summary = library.importFolder(treeUri)
            _busy.value = false
            _messages.emit(summaryMessage(summary))
        }
    }

    private fun summaryMessage(summary: StickerLibrary.ImportSummary): String {
        val parts = ArrayList<String>()
        parts.add(app.resources.getQuantityString(com.whatik.R.plurals.msg_imported, summary.added, summary.added))
        if (summary.duplicates > 0) parts.add(app.getString(com.whatik.R.string.msg_duplicates, summary.duplicates))
        if (summary.failed.isNotEmpty()) parts.add(app.getString(com.whatik.R.string.msg_failed, summary.failed.size, summary.failed.first().reason))
        return parts.joinToString(" · ")
    }

    // ------------------------------------------------------------ scansione

    fun openScan() {
        _screen.value = Screen.SCAN
        if (_scanState.value is ScanState.Idle || _scanState.value is ScanState.PermissionDenied) {
            if (MediaScanner.hasPermission(app)) startScan() else _scanState.value = ScanState.PermissionDenied
        }
    }

    fun onPermissionResult() {
        if (MediaScanner.hasPermission(app)) startScan() else _scanState.value = ScanState.PermissionDenied
    }

    fun startScan() {
        _scanState.value = ScanState.Loading
        viewModelScope.launch {
            val candidates = runCatching { MediaScanner.scan(app) }.getOrDefault(emptyList())
            val tiktok = candidates.filter { it.looksTikTok }
            _scanState.value = ScanState.Loaded(
                candidates = candidates,
                selected = tiktok.map { it.uri }.toSet(),
                onlyTikTok = tiktok.isNotEmpty(),
            )
        }
    }

    fun toggleScanCandidate(uri: Uri) {
        _scanState.update { s ->
            if (s is ScanState.Loaded) s.copy(selected = if (uri in s.selected) s.selected - uri else s.selected + uri) else s
        }
    }

    fun setScanFilter(onlyTikTok: Boolean) {
        _scanState.update { s -> if (s is ScanState.Loaded) s.copy(onlyTikTok = onlyTikTok) else s }
    }

    fun scanSelectAllVisible(select: Boolean) {
        _scanState.update { s ->
            if (s is ScanState.Loaded) {
                val visible = s.visible.map { it.uri }.toSet()
                s.copy(selected = if (select) s.selected + visible else s.selected - visible)
            } else s
        }
    }

    fun importScanSelection() {
        val state = _scanState.value as? ScanState.Loaded ?: return
        val chosen = state.candidates.filter { it.uri in state.selected }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            var added = 0
            var duplicates = 0
            val failed = ArrayList<StickerLibrary.ImportResult.Failed>()
            for (candidate in chosen) {
                when (val r = library.importUri(candidate.uri, "scan", candidate.displayName)) {
                    is StickerLibrary.ImportResult.Added -> added++
                    is StickerLibrary.ImportResult.Duplicate -> duplicates++
                    is StickerLibrary.ImportResult.Failed -> failed.add(r)
                }
            }
            _busy.value = false
            _messages.emit(summaryMessage(StickerLibrary.ImportSummary(added, duplicates, failed)))
            _scanState.update { s -> if (s is ScanState.Loaded) s.copy(selected = emptySet()) else s }
            _screen.value = Screen.LIBRARY
        }
    }

    // ------------------------------------------------------------ esportazione

    fun openExport() {
        val chosen = items.value.filter { it.id in _selected.value }
        if (chosen.isEmpty()) return
        _exportState.value = ExportState.Configuring(
            items = chosen.map { PackPlanner.Item(it.id, it.animated) },
            baseName = app.getString(com.whatik.R.string.default_pack_name),
            publisher = app.getString(com.whatik.R.string.default_publisher),
            convertAnimatedToStatic = false,
            staticTargetId = null,
            animatedTargetId = null,
        )
    }

    fun updateExportConfig(transform: (ExportState.Configuring) -> ExportState.Configuring) {
        _exportState.update { s -> if (s is ExportState.Configuring) transform(s) else s }
    }

    fun dismissExport() {
        exportJob?.cancel()
        exportJob = null
        _exportState.value = ExportState.Hidden
    }

    fun currentPlan(config: ExportState.Configuring): PackPlanner.Plan {
        val staticTarget = packs.value.firstOrNull { it.identifier == config.staticTargetId }?.let { it.toTarget() }
        val animatedTarget = packs.value.firstOrNull { it.identifier == config.animatedTargetId }?.let { it.toTarget() }
        return PackPlanner.plan(config.items, config.convertAnimatedToStatic, staticTarget, animatedTarget)
    }

    private fun StickerPack.toTarget() = PackPlanner.Target(identifier, name, animated, stickers.size)

    fun runExport() {
        val config = _exportState.value as? ExportState.Configuring ?: return
        val plan = currentPlan(config)
        val total = plan.packs.sumOf { it.items.size }
        _exportState.value = ExportState.Running(0, total, "")
        exportJob = viewModelScope.launch {
            val result = try {
                app.exporter.export(
                    plan,
                    StickerExporter.Config(config.baseName, config.publisher, config.convertAnimatedToStatic, DEFAULT_EMOJIS),
                ) { done, totalCount, name ->
                    _exportState.value = ExportState.Running(done, totalCount, name)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                StickerExporter.Result(emptyList(), listOf(StickerExporter.Failure("", e.message ?: e.javaClass.simpleName)))
            }
            _selected.value = emptySet()
            refreshWhatsAppStatus()
            _exportState.value = ExportState.Finished(result)
        }
    }

    // ------------------------------------------------------------ pack

    fun deletePack(identifier: String) {
        viewModelScope.launch {
            app.packStore.delete(identifier)
            _messages.emit(app.getString(com.whatik.R.string.msg_pack_deleted))
        }
    }

    fun refreshWhatsAppStatus() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                packs.value.associate { it.identifier to WhatsAppBridge.isPackAdded(app, it.identifier) }
            }
            _addedToWhatsApp.value = status
        }
    }

    fun notify(message: String) {
        viewModelScope.launch { _messages.emit(message) }
    }

    companion object {
        /** WhatsApp richiede da 1 a 3 emoji per sticker: li usa per la ricerca. */
        val DEFAULT_EMOJIS = listOf("🎵", "😀")
    }
}
