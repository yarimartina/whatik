package com.whatik.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.whatik.R
import com.whatik.data.PackPlanner
import com.whatik.data.StickerItem
import com.whatik.data.StickerPack
import java.io.File

/** Dettaglio di un pack: sticker contenuti, aggiunta, rimozione, rinomina, unione con altri pack. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PackDetailScreen(
    pack: StickerPack,
    detail: PackDetailState,
    allPacks: List<StickerPack>,
    added: Boolean,
    stickerFileOf: (StickerPack, String) -> File,
    snackbarHost: @Composable () -> Unit,
    onBack: () -> Unit,
    onToggleSticker: (String) -> Unit,
    onClearSelection: () -> Unit,
    onRemoveSelected: () -> Unit,
    onAddStickers: () -> Unit,
    onOpenMerge: () -> Unit,
    onDismissMerge: () -> Unit,
    onToggleMergeSource: (String) -> Unit,
    onMergeResultAnimated: (Boolean) -> Unit,
    onMergeDeleteSources: (Boolean) -> Unit,
    onConfirmMerge: () -> Unit,
    onOpenRename: () -> Unit,
    onDismissRename: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: () -> Unit,
    onAddToWhatsApp: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selectionMode = detail.selected.isNotEmpty()
    val busy = detail.progress != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) stringResource(R.string.title_selected, detail.selected.size) else pack.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectionMode) onClearSelection() else onBack() }) {
                        Icon(
                            if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(if (selectionMode) R.string.action_clear_selection else R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = { confirmRemove = true }, enabled = !busy) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.pack_action_remove))
                        }
                    } else {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = null) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pack_action_rename)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { menu = false; onOpenRename() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pack_action_merge)) },
                                    leadingIcon = { Icon(Icons.Default.CallMerge, null) },
                                    onClick = { menu = false; onOpenMerge() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                                    onClick = { menu = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!selectionMode && !busy && pack.stickers.size < PackPlanner.MAX_STICKERS) {
                ExtendedFloatingActionButton(
                    onClick = onAddStickers,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.pack_action_add)) },
                )
            }
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    stringResource(
                        if (pack.animated) R.string.pack_count_animated else R.string.pack_count_static,
                        pack.stickers.size,
                    ) + " · " + stringResource(R.string.pack_free_slots, pack.stickers.size, PackPlanner.MAX_STICKERS),
                    style = MaterialTheme.typography.bodySmall,
                )
                val status = when {
                    !pack.isAddable -> stringResource(R.string.pack_status_too_small, pack.stickers.size, PackPlanner.MIN_STICKERS)
                    added -> stringResource(R.string.pack_status_added)
                    else -> stringResource(R.string.pack_status_not_added)
                }
                Text(status, style = MaterialTheme.typography.labelMedium, color = if (added && pack.isAddable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pack.isAddable) {
                        if (added) {
                            OutlinedButton(onClick = onAddToWhatsApp, enabled = !busy) { Text(stringResource(R.string.action_reopen_whatsapp)) }
                        } else {
                            Button(onClick = onAddToWhatsApp, enabled = !busy) { Text(stringResource(R.string.action_add_to_whatsapp)) }
                        }
                    }
                    OutlinedButton(onClick = onOpenMerge, enabled = !busy && allPacks.size > 1) {
                        Icon(Icons.Default.CallMerge, null)
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.pack_action_merge_short))
                    }
                }
                detail.progress?.let { (done, total) ->
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.pack_progress, done, total), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.pack_detail_hint), style = MaterialTheme.typography.bodySmall)
            }
            if (pack.stickers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.pack_empty), textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    contentPadding = PaddingValues(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(pack.stickers, key = { it.fileName }) { sticker ->
                        val selected = sticker.fileName in detail.selected
                        SelectableTile(
                            model = stickerFileOf(pack, sticker.fileName),
                            selected = selected,
                            badge = null,
                            onClick = { onToggleSticker(sticker.fileName) },
                        )
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(pluralStringResource(R.plurals.pack_remove_confirm, detail.selected.size, detail.selected.size)) },
            text = { Text(stringResource(R.string.pack_remove_body)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemoveSelected() }) { Text(stringResource(R.string.pack_action_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.pack_delete_confirm, pack.name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    detail.merge?.let { merge ->
        MergeDialog(
            target = pack,
            others = allPacks.filter { it.identifier != pack.identifier },
            state = merge,
            onToggleSource = onToggleMergeSource,
            onResultAnimated = onMergeResultAnimated,
            onDeleteSources = onMergeDeleteSources,
            onConfirm = onConfirmMerge,
            onDismiss = onDismissMerge,
        )
    }
    if (detail.renaming) {
        RenameDialog(pack = pack, onConfirm = onRename, onDismiss = onDismissRename)
    }
}

/** Griglia della libreria per scegliere gli sticker da aggiungere al pack. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPackScreen(
    pack: StickerPack,
    items: List<StickerItem>,
    selected: Set<String>,
    busy: Boolean,
    fileOf: (StickerItem) -> File,
    snackbarHost: @Composable () -> Unit,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val free = PackPlanner.MAX_STICKERS - pack.stickers.size
    val alreadyIn = pack.stickers.mapNotNull { it.sourceId }.toSet()
    val candidates = items.filter { it.id !in alreadyIn }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pack_add_title, pack.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (selected.isNotEmpty() && !busy) {
                ExtendedFloatingActionButton(
                    onClick = onConfirm,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.pack_add_fab, selected.size, free)) },
                )
            }
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                stringResource(R.string.pack_add_hint, free, if (pack.animated) stringResource(R.string.capture_kind_animated) else stringResource(R.string.capture_kind_static)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (candidates.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.pack_add_empty), textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 96.dp),
                    contentPadding = PaddingValues(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(candidates, key = { it.id }) { item ->
                        SelectableTile(
                            model = fileOf(item),
                            selected = item.id in selected,
                            badge = if (item.animated) stringResource(R.string.badge_animated) else null,
                            onClick = { onToggle(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableTile(model: Any, selected: Boolean, badge: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val borderModifier = if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(borderModifier)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(MaterialTheme.colorScheme.surface, CircleShape).size(24.dp),
            )
        }
    }
}

@Composable
private fun MergeDialog(
    target: StickerPack,
    others: List<StickerPack>,
    state: MergeState,
    onToggleSource: (String) -> Unit,
    onResultAnimated: (Boolean) -> Unit,
    onDeleteSources: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val chosen = others.filter { it.identifier in state.sourceIds }
    val mixed = (chosen.map { it.animated } + target.animated).toSet().size > 1
    val total = target.stickers.size + chosen.sumOf { it.stickers.size }
    val inTarget = minOf(total, PackPlanner.MAX_STICKERS)
    val overflow = (total - PackPlanner.MAX_STICKERS).coerceAtLeast(0)
    val overflowPacks = if (overflow == 0) 0 else (overflow + PackPlanner.MAX_STICKERS - 1) / PackPlanner.MAX_STICKERS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.merge_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.merge_hint, target.name), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (others.isEmpty()) {
                    Text(stringResource(R.string.merge_none), style = MaterialTheme.typography.bodySmall)
                }
                others.forEach { other ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onToggleSource(other.identifier) },
                    ) {
                        Checkbox(checked = other.identifier in state.sourceIds, onCheckedChange = { onToggleSource(other.identifier) })
                        Column {
                            Text(other.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(if (other.animated) R.string.pack_count_animated else R.string.pack_count_static, other.stickers.size),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (mixed) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.merge_result_type), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onResultAnimated(true) }) {
                        RadioButton(selected = state.resultAnimated, onClick = { onResultAnimated(true) })
                        Text(stringResource(R.string.merge_result_animated), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onResultAnimated(false) }) {
                        RadioButton(selected = !state.resultAnimated, onClick = { onResultAnimated(false) })
                        Text(stringResource(R.string.merge_result_static), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onDeleteSources(!state.deleteSources) }) {
                    Checkbox(checked = state.deleteSources, onCheckedChange = onDeleteSources)
                    Text(stringResource(R.string.merge_delete_sources), style = MaterialTheme.typography.bodySmall)
                }
                if (chosen.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.merge_summary, total, inTarget, overflow, overflowPacks), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = chosen.isNotEmpty()) { Text(stringResource(R.string.merge_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun RenameDialog(pack: StickerPack, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(pack.identifier) { mutableStateOf(pack.name) }
    var publisher by remember(pack.identifier) { mutableStateOf(pack.publisher) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it.take(128) }, label = { Text(stringResource(R.string.export_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = publisher, onValueChange = { publisher = it.take(128) }, label = { Text(stringResource(R.string.export_publisher)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, publisher) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
