package com.whatik.ui

import android.app.Application
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.whatik.capture.CaptureService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.whatik.R
import com.whatik.WhatikApp
import com.whatik.data.MediaCandidate
import com.whatik.data.MediaScanner
import com.whatik.data.PackPlanner
import com.whatik.data.RemoteCandidate
import com.whatik.data.StickerExporter
import com.whatik.data.StickerItem
import com.whatik.data.StickerLibrary
import com.whatik.data.StickerPack
import com.whatik.data.UrlImporter
import com.whatik.image.CropSpec
import com.whatik.image.CroppedFrameProducer
import com.whatik.image.FrameProducer
import com.whatik.image.StickerConverter
import com.whatik.image.StickerProposal
import com.whatik.image.TrimmedFrameProducer
import com.whatik.image.VideoAnalyzer
import com.whatik.image.VideoFrameProducer
import com.whatik.whatsapp.WhatsAppBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class Screen { LIBRARY, SCAN, PACKS, EDITOR, LINK_RESULTS, CAPTURE }

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

sealed class LinkState {
    data object Idle : LinkState()
    data object Loading : LinkState()
    data class Results(
        val pageUrl: String,
        val candidates: List<RemoteCandidate>,
        val selected: Set<String>,
        val onlyStickers: Boolean,
    ) : LinkState() {
        val visible: List<RemoteCandidate> get() = if (onlyStickers) candidates.filter { it.looksSticker } else candidates
        val stickerCount: Int get() = candidates.count { it.looksSticker }
    }
}

sealed class EditorSource {
    abstract val width: Int
    abstract val height: Int

    class Video(val file: File, val info: VideoFrameProducer.VideoInfo) : EditorSource() {
        override val width: Int get() = info.width
        override val height: Int get() = info.height
    }

    class Image(
        val bytes: ByteArray,
        override val width: Int,
        override val height: Int,
        val animated: Boolean,
        val replaceItemId: String?,
        /** Durate dei fotogrammi se animato (per accorciare il loop); vuoto se statico. */
        val durationsMs: List<Int> = emptyList(),
    ) : EditorSource()
}

data class EditorState(
    val source: EditorSource,
    val name: String,
    val crop: CropSpec,
    val startMs: Long,
    val endMs: Long,
    val previewTimeMs: Long,
    val preview: Bitmap?,
    val converting: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    /** true mentre l'analisi automatica della registrazione è in corso. */
    val detecting: Boolean = false,
    /** Sticker trovati automaticamente (null = analisi non ancora eseguita). */
    val proposals: List<StickerProposal>? = null,
    val selectedProposal: Int? = null,
    /** Avanzamento della creazione in blocco: (sticker fatti, totale). */
    val bulkProgress: Pair<Int, Int>? = null,
    /** Ritagliando uno sticker della libreria: true = l'originale resta, false = viene sostituito. */
    val keepOriginal: Boolean = true,
) {
    val isVideo: Boolean get() = source is EditorSource.Video
    val durationMs: Long
        get() = when (val src = source) {
            is EditorSource.Video -> src.info.durationMs
            is EditorSource.Image -> src.durationsMs.sumOf { it.toLong() }
        }
    /** true se si può scegliere un intervallo di tempo (video o sticker animato). */
    val hasTimeline: Boolean get() = isVideo || ((source as? EditorSource.Image)?.durationsMs?.size ?: 0) > 1
    val frameCount: Int
        get() = when (val src = source) {
            is EditorSource.Video -> ((endMs - startMs) * VideoFrameProducer.DEFAULT_FPS / 1000.0).toInt().coerceAtLeast(1)
            is EditorSource.Image -> TrimmedFrameProducer.startTimes(src.durationsMs).count { it in startMs..endMs }.coerceAtLeast(1)
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
    private val urlImporter = UrlImporter(library)

    val items: StateFlow<List<StickerItem>> = library.items
    val packs: StateFlow<List<StickerPack>> = app.packStore.packs

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _screen = MutableStateFlow(Screen.LIBRARY)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _linkState = MutableStateFlow<LinkState>(LinkState.Idle)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _editorState = MutableStateFlow<EditorState?>(null)
    val editorState: StateFlow<EditorState?> = _editorState.asStateFlow()

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
    private var previewJob: Job? = null
    private val editorDir = File(app.cacheDir, "editor")

    private val _captureUi = MutableStateFlow(CaptureUiState(notificationsRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU))
    val captureUi: StateFlow<CaptureUiState> = _captureUi.asStateFlow()

    init {
        viewModelScope.launch { CaptureService.running.collect { r -> _captureUi.update { it.copy(running = r) } } }
        viewModelScope.launch { CaptureService.status.collect { m -> _captureUi.update { it.copy(message = m) } } }
        viewModelScope.launch { CaptureService.createdIds.collect { ids -> _captureUi.update { it.copy(createdIds = ids) } } }
        refreshCaptureState()
    }

    // ------------------------------------------------------------ cattura automatica

    fun openCapture() {
        refreshCaptureState()
        _screen.value = Screen.CAPTURE
    }

    fun refreshCaptureState() {
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        _captureUi.update { it.copy(overlayGranted = Settings.canDrawOverlays(app), notificationsGranted = notifications) }
    }

    fun stopCapture() = CaptureService.stop(app)

    // ------------------------------------------------------------ navigazione

    fun navigate(screen: Screen) {
        _screen.value = screen
        if (screen == Screen.PACKS) refreshWhatsAppStatus()
    }

    fun back(): Boolean {
        when (_screen.value) {
            Screen.EDITOR -> { closeEditor(); return true }
            Screen.LIBRARY -> Unit
            else -> { _screen.value = Screen.LIBRARY; return true }
        }
        if (_selected.value.isNotEmpty()) {
            clearSelection()
            return true
        }
        return false
    }

    /** Richieste arrivate da [ShareReceiverActivity] (video da ritagliare o link da importare). */
    fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_VIDEO_PATH)?.let { path ->
            intent.removeExtra(EXTRA_VIDEO_PATH)
            openVideoEditorFromFile(File(path), intent.getStringExtra(EXTRA_VIDEO_NAME))
        }
        intent.getStringExtra(EXTRA_LINK)?.let { link ->
            intent.removeExtra(EXTRA_LINK)
            importFromLink(link)
        }
        intent.getStringArrayListExtra(EXTRA_SELECT_IDS)?.let { ids ->
            intent.removeExtra(EXTRA_SELECT_IDS)
            selectCaptured(ids)
        }
    }

    /** Mostra la libreria con gli sticker indicati selezionati (dalla notifica di cattura). */
    fun selectCaptured(ids: List<String>) {
        val existing = items.value.map { it.id }.toSet()
        val chosen = ids.filter { it in existing }.toSet()
        if (chosen.isEmpty()) return
        _selected.value = chosen
        _screen.value = Screen.LIBRARY
        notify(app.resources.getQuantityString(R.plurals.msg_captured_selected, chosen.size, chosen.size))
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
            _messages.emit(app.getString(R.string.msg_deleted, ids.size))
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
        parts.add(app.resources.getQuantityString(R.plurals.msg_imported, summary.added, summary.added))
        if (summary.duplicates > 0) parts.add(app.getString(R.string.msg_duplicates, summary.duplicates))
        if (summary.failed.isNotEmpty()) parts.add(app.getString(R.string.msg_failed, summary.failed.size, summary.failed.first().reason))
        return parts.joinToString(" · ")
    }

    // ------------------------------------------------------------ editor (video / ritaglio)

    fun openVideoEditor(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            _busy.value = true
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    editorDir.mkdirs()
                    val target = File(editorDir, "${UUID.randomUUID()}.mp4")
                    app.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: throw IllegalStateException("Impossibile leggere il video")
                    target
                }
            }
            _busy.value = false
            copied.onSuccess { openVideoEditorFromFile(it, displayName ?: uri.lastPathSegment) }
                .onFailure { _messages.emit(app.getString(R.string.msg_video_unreadable, it.message ?: "")) }
        }
    }

    private fun openVideoEditorFromFile(file: File, displayName: String?) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { VideoFrameProducer.readInfo(file) } }
            info.onFailure {
                file.delete()
                _messages.emit(app.getString(R.string.msg_video_unreadable, it.message ?: ""))
            }.onSuccess { videoInfo ->
                val end = minOf(videoInfo.durationMs, DEFAULT_CLIP_MS)
                _editorState.value = EditorState(
                    source = EditorSource.Video(file, videoInfo),
                    name = StickerLibrary.stripExtension(displayName ?: "sticker").take(60),
                    crop = CropSpec.DEFAULT,
                    startMs = 0,
                    endMs = end,
                    previewTimeMs = 0,
                    preview = null,
                )
                _screen.value = Screen.EDITOR
                requestPreview(0)
                detectStickers()
            }
        }
    }

    /** Analisi automatica della registrazione: trova gli sticker animati e applica il primo. */
    fun detectStickers() {
        val state = _editorState.value ?: return
        val video = state.source as? EditorSource.Video ?: return
        if (state.detecting) return
        _editorState.update { it?.copy(detecting = true, proposals = null, selectedProposal = null) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                runCatching { VideoAnalyzer.analyze(video.file, video.info) }.getOrDefault(emptyList())
            }
            val current = _editorState.value ?: return@launch
            if (current.source !== video) return@launch
            _editorState.value = current.copy(detecting = false, proposals = found)
            if (found.isNotEmpty()) applyProposal(0)
        }
    }

    fun applyProposal(index: Int) {
        val state = _editorState.value ?: return
        val proposal = state.proposals?.getOrNull(index) ?: return
        _editorState.value = state.copy(
            crop = proposal.crop,
            startMs = proposal.startMs,
            endMs = proposal.endMs,
            selectedProposal = index,
        )
        requestPreview(proposal.startMs)
    }

    /** Crea in blocco tutti gli sticker trovati automaticamente, senza passare dall'editor manuale. */
    fun createAllProposals() {
        val state = _editorState.value ?: return
        val video = state.source as? EditorSource.Video ?: return
        val proposals = state.proposals.orEmpty()
        if (proposals.isEmpty() || state.converting) return
        _editorState.value = state.copy(converting = true, progress = null, bulkProgress = 0 to proposals.size)
        viewModelScope.launch {
            val created = ArrayList<String>()
            val failures = ArrayList<String>()
            for ((i, proposal) in proposals.withIndex()) {
                _editorState.update { it?.copy(bulkProgress = i to proposals.size, progress = null) }
                val framesDir = File(editorDir, "frames-${UUID.randomUUID()}")
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        val producer = VideoFrameProducer(
                            file = video.file, startMs = proposal.startMs, endMs = proposal.endMs,
                            fps = VideoFrameProducer.DEFAULT_FPS, crop = proposal.crop, cacheDir = framesDir,
                        ) { done, total -> _editorState.update { it?.copy(progress = done to total) } }
                        val converted = StickerConverter.convert(producer, forceStatic = false)
                        val name = if (proposals.size > 1) "${state.name.ifBlank { "sticker" }} ${i + 1}" else state.name.ifBlank { "sticker" }
                        library.importBytes(converted.bytes, name, "video")
                    }
                }
                framesDir.deleteRecursively()
                result.onSuccess { imported ->
                    when (imported) {
                        is StickerLibrary.ImportResult.Added -> created.add(imported.item.id)
                        is StickerLibrary.ImportResult.Duplicate -> created.add(imported.item.id)
                        is StickerLibrary.ImportResult.Failed -> failures.add(imported.reason)
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    failures.add(e.message ?: e.javaClass.simpleName)
                }
            }
            if (created.isNotEmpty()) _selected.value = created.toSet()
            _messages.emit(
                if (failures.isEmpty()) app.resources.getQuantityString(R.plurals.msg_stickers_created, created.size, created.size)
                else app.getString(R.string.msg_stickers_partial, created.size, failures.size, failures.first()),
            )
            closeEditor()
        }
    }

    /** Apre l'editor su uno sticker già in libreria (screenshot da ritagliare, GIF da rifilare...). */
    fun openImageEditor(itemId: String) {
        val item = library.find(itemId) ?: return
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = library.file(item).readBytes()
                    val producer = FrameProducer.open(bytes)
                    val durations = if (producer.info.frameCount > 1) producer.info.durationsMs else emptyList()
                    val first = FrameProducer.firstFrame(bytes)
                    Triple(bytes, first, durations)
                }
            }
            loaded.onFailure { _messages.emit(app.getString(R.string.msg_failed, 1, it.message ?: "")) }
                .onSuccess { (bytes, first, durations) ->
                    val total = durations.sumOf { it.toLong() }
                    _editorState.value = EditorState(
                        source = EditorSource.Image(bytes, first.width, first.height, durations.size > 1, replaceItemId = item.id, durationsMs = durations),
                        name = item.displayName,
                        crop = CropSpec.FULL,
                        startMs = 0,
                        endMs = total,
                        previewTimeMs = 0,
                        preview = first,
                        // uno sticker gia' ritagliato (cattura, video) si corregge sostituendolo;
                        // uno screenshot o un'immagine importata potrebbe contenere altri sticker
                        keepOriginal = item.source !in setOf("capture", "video", "crop"),
                    )
                    _screen.value = Screen.EDITOR
                }
        }
    }

    fun updateCrop(crop: CropSpec) {
        _editorState.update { it?.copy(crop = crop.normalized(), selectedProposal = null) }
    }

    fun updateEditorName(name: String) {
        _editorState.update { it?.copy(name = name.take(60)) }
    }

    fun updateKeepOriginal(keep: Boolean) {
        _editorState.update { it?.copy(keepOriginal = keep) }
    }

    /** Aggiorna l'intervallo (max 10 s) e mostra l'anteprima del cursore che si è mosso. */
    fun updateRange(startMs: Long, endMs: Long) {
        val state = _editorState.value ?: return
        var start = startMs.coerceIn(0, state.durationMs)
        var end = endMs.coerceIn(0, state.durationMs)
        if (end - start < MIN_CLIP_MS) {
            if (start != state.startMs) start = (end - MIN_CLIP_MS).coerceAtLeast(0) else end = (start + MIN_CLIP_MS).coerceAtMost(state.durationMs)
        }
        if (end - start > StickerConverter.MAX_DURATION_MS) {
            if (start != state.startMs) end = start + StickerConverter.MAX_DURATION_MS else start = end - StickerConverter.MAX_DURATION_MS
        }
        val movedEnd = end != state.endMs && start == state.startMs
        _editorState.value = state.copy(startMs = start, endMs = end, selectedProposal = null)
        requestPreview(if (movedEnd) end else start)
    }

    fun requestPreview(timeMs: Long) {
        val state = _editorState.value ?: return
        if (!state.hasTimeline) return
        val source = state.source
        _editorState.update { it?.copy(previewTimeMs = timeMs) }
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(120) // debounce mentre si trascina il cursore
            val bitmap = withContext(Dispatchers.IO) {
                when (source) {
                    is EditorSource.Video -> VideoFrameProducer.previewFrame(source.file, timeMs)
                    is EditorSource.Image -> runCatching { animatedFrameAt(source, timeMs) }.getOrNull()
                }
            }
            if (bitmap != null) _editorState.update { it?.takeIf { s -> s.previewTimeMs == timeMs }?.copy(preview = bitmap) ?: it }
        }
    }

    /** Fotogramma di uno sticker animato all'istante dato (decodifica sequenziale fino a lì). */
    private fun animatedFrameAt(source: EditorSource.Image, timeMs: Long): Bitmap? {
        val starts = TrimmedFrameProducer.startTimes(source.durationsMs)
        val target = starts.indexOfLast { it <= timeMs }.coerceAtLeast(0)
        var result: Bitmap? = null
        FrameProducer.open(source.bytes).produce { index, frame ->
            if (index == target) { result = frame; false } else { frame.recycle(); true }
        }
        return result
    }

    fun createStickerFromEditor() {
        val state = _editorState.value ?: return
        if (state.converting) return
        _editorState.value = state.copy(converting = true, progress = null)
        viewModelScope.launch {
            val framesDir = File(editorDir, "frames-${UUID.randomUUID()}")
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val producer = when (val src = state.source) {
                        is EditorSource.Video -> VideoFrameProducer(
                            file = src.file, startMs = state.startMs, endMs = state.endMs,
                            fps = VideoFrameProducer.DEFAULT_FPS, crop = state.crop, cacheDir = framesDir,
                        ) { done, total -> _editorState.update { it?.copy(progress = done to total) } }
                        is EditorSource.Image -> {
                            val cropped = CroppedFrameProducer(FrameProducer.open(src.bytes), state.crop)
                            if (state.hasTimeline) TrimmedFrameProducer(cropped, state.startMs, state.endMs) else cropped
                        }
                    }
                    val converted = StickerConverter.convert(producer, forceStatic = false)
                    val source = if (state.isVideo) "video" else "crop"
                    val imported = library.importBytes(converted.bytes, state.name.ifBlank { "sticker" }, source)
                    val replace = (state.source as? EditorSource.Image)?.replaceItemId
                    if (replace != null && !state.keepOriginal && imported is StickerLibrary.ImportResult.Added) library.delete(setOf(replace))
                    imported
                }
            }
            framesDir.deleteRecursively()
            result.onFailure { e ->
                if (e is CancellationException) throw e
                _editorState.update { it?.copy(converting = false, progress = null) }
                _messages.emit(app.getString(R.string.msg_sticker_failed, e.message ?: e.javaClass.simpleName))
            }.onSuccess { imported ->
                when (imported) {
                    is StickerLibrary.ImportResult.Added -> {
                        _selected.value = setOf(imported.item.id)
                        _messages.emit(app.getString(R.string.msg_sticker_created))
                    }
                    is StickerLibrary.ImportResult.Duplicate -> {
                        _selected.value = setOf(imported.item.id)
                        _messages.emit(app.getString(R.string.msg_duplicates, 1))
                    }
                    is StickerLibrary.ImportResult.Failed -> _messages.emit(app.getString(R.string.msg_sticker_failed, imported.reason))
                }
                closeEditor()
            }
        }
    }

    fun closeEditor() {
        previewJob?.cancel()
        val state = _editorState.value
        _editorState.value = null
        _screen.value = Screen.LIBRARY
        (state?.source as? EditorSource.Video)?.file?.let { f -> viewModelScope.launch(Dispatchers.IO) { f.delete() } }
    }

    // ------------------------------------------------------------ link

    fun importFromLink(text: String) {
        viewModelScope.launch {
            _linkState.value = LinkState.Loading
            _busy.value = true
            when (val fetched = urlImporter.fetch(text)) {
                is UrlImporter.Fetched.Image -> {
                    val summary = when (val r = library.importBytes(fetched.bytes, fetched.name, "link")) {
                        is StickerLibrary.ImportResult.Added -> StickerLibrary.ImportSummary(1, 0, emptyList())
                        is StickerLibrary.ImportResult.Duplicate -> StickerLibrary.ImportSummary(0, 1, emptyList())
                        is StickerLibrary.ImportResult.Failed -> StickerLibrary.ImportSummary(0, 0, listOf(r))
                    }
                    _linkState.value = LinkState.Idle
                    _messages.emit(summaryMessage(summary))
                }
                is UrlImporter.Fetched.Page -> {
                    if (fetched.candidates.isEmpty()) {
                        _linkState.value = LinkState.Idle
                        _messages.emit(app.getString(R.string.link_none_found))
                    } else {
                        val stickers = fetched.candidates.filter { it.looksSticker }
                        _linkState.value = LinkState.Results(
                            pageUrl = fetched.pageUrl,
                            candidates = fetched.candidates,
                            selected = stickers.map { it.url }.toSet(),
                            onlyStickers = stickers.isNotEmpty(),
                        )
                        _screen.value = Screen.LINK_RESULTS
                    }
                }
                is UrlImporter.Fetched.Error -> {
                    _linkState.value = LinkState.Idle
                    _messages.emit(app.getString(R.string.link_error, fetched.message))
                }
            }
            _busy.value = false
        }
    }

    fun toggleLinkCandidate(url: String) {
        _linkState.update { s ->
            if (s is LinkState.Results) s.copy(selected = if (url in s.selected) s.selected - url else s.selected + url) else s
        }
    }

    fun setLinkFilter(onlyStickers: Boolean) {
        _linkState.update { s -> if (s is LinkState.Results) s.copy(onlyStickers = onlyStickers) else s }
    }

    fun linkSelectAllVisible(select: Boolean) {
        _linkState.update { s ->
            if (s is LinkState.Results) {
                val visible = s.visible.map { it.url }.toSet()
                s.copy(selected = if (select) s.selected + visible else s.selected - visible)
            } else s
        }
    }

    fun importLinkSelection() {
        val state = _linkState.value as? LinkState.Results ?: return
        val chosen = state.candidates.filter { it.url in state.selected }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            val summary = urlImporter.importCandidates(chosen)
            _busy.value = false
            _messages.emit(summaryMessage(summary))
            _linkState.value = LinkState.Idle
            _screen.value = Screen.LIBRARY
        }
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
            baseName = app.getString(R.string.default_pack_name),
            publisher = app.getString(R.string.default_publisher),
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
        val staticTarget = packs.value.firstOrNull { it.identifier == config.staticTargetId }?.toTarget()
        val animatedTarget = packs.value.firstOrNull { it.identifier == config.animatedTargetId }?.toTarget()
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
                if (e is CancellationException) throw e
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
            _messages.emit(app.getString(R.string.msg_pack_deleted))
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
        const val EXTRA_VIDEO_PATH = "com.whatik.extra.VIDEO_PATH"
        const val EXTRA_VIDEO_NAME = "com.whatik.extra.VIDEO_NAME"
        const val EXTRA_LINK = "com.whatik.extra.LINK"
        const val EXTRA_SELECT_IDS = MainActivity.EXTRA_SELECT_IDS
        const val DEFAULT_CLIP_MS = 3000L
        const val MIN_CLIP_MS = 200L

        /** WhatsApp richiede da 1 a 3 emoji per sticker: li usa per la ricerca. */
        val DEFAULT_EMOJIS = listOf("🎵", "😀")
    }
}
