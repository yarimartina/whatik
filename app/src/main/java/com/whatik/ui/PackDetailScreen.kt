package com.whatik.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whatik.R
import com.whatik.data.PackPlanner
import com.whatik.data.StickerItem
import com.whatik.data.StickerPack
import com.whatik.ui.components.BarAction
import com.whatik.ui.components.BusyBanner
import com.whatik.ui.components.ConfirmDialog
import com.whatik.ui.components.DialogContainerColor
import com.whatik.ui.components.EmptyState
import com.whatik.ui.components.SelectionBottomBar
import com.whatik.ui.components.StickerGrid
import com.whatik.ui.components.StickerImage
import com.whatik.ui.components.StickerTile
import com.whatik.ui.theme.Spacing
import java.io.File

/**
 * Dettaglio di un pack (P3): intestazione con stato e pulsanti, griglia degli sticker, selezione con
 * [SelectionBottomBar] (Rimuovi), menu ⋮ con Rinomina ed Elimina; Unisci è un pulsante dell'intestazione.
 * È una schermata di secondo livello: nessuna barra di navigazione.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val total = pack.stickers.size

    BackHandler(enabled = selectionMode) { onClearSelection() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) pluralStringResource(R.plurals.title_selected_count, detail.selected.size, detail.selected.size) else pack.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = if (selectionMode) onClearSelection else onBack) {
                        Icon(
                            if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(if (selectionMode) R.string.action_clear_selection else R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (!selectionMode) {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pack_action_rename)) },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { menu = false; onOpenRename() },
                                )
                                val error = MaterialTheme.colorScheme.error
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.pack_action_delete), color = error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = error) },
                                    onClick = { menu = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selectionMode) {
                val removeLabel = stringResource(R.string.pack_action_remove)
                SelectionBottomBar(
                    remember(busy, removeLabel) {
                        listOf(
                            BarAction(Icons.Default.RemoveCircleOutline, removeLabel, enabled = !busy, destructive = true, onClick = { confirmRemove = true }),
                        )
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode && !busy && total < PackPlanner.MAX_STICKERS) {
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
            detail.progress?.let { (done, all) ->
                BusyBanner(stringResource(R.string.pack_progress, done, all), progress = if (all > 0) done.toFloat() / all else 0f)
            }
            val header: @Composable () -> Unit = {
                PackHeader(
                    pack = pack,
                    added = added,
                    tray = remember(pack.identifier, pack.trayFile, pack.imageDataVersion) { stickerFileOf(pack, pack.trayFile) },
                    busy = busy,
                    canMerge = allPacks.size > 1,
                    onAddToWhatsApp = onAddToWhatsApp,
                    onOpenMerge = onOpenMerge,
                )
            }
            if (pack.stickers.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    Box(Modifier.padding(horizontal = Spacing.l)) { header() }
                    EmptyState(
                        icon = Icons.Default.AddPhotoAlternate,
                        title = stringResource(R.string.pack_empty),
                        body = null,
                        modifier = Modifier.weight(1f),
                        actionLabel = stringResource(R.string.pack_action_add),
                        onAction = onAddStickers,
                    )
                }
            } else {
                StickerGrid(Modifier.fillMaxWidth().weight(1f)) {
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(horizontal = Spacing.xs)) {
                            header()
                            Text(
                                stringResource(R.string.pack_detail_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Spacing.s),
                            )
                        }
                    }
                    itemsIndexed(pack.stickers, key = { _, s -> s.fileName }) { index, sticker ->
                        val file = remember(sticker.fileName) { stickerFileOf(pack, sticker.fileName) }
                        val description = stringResource(R.string.a11y_pack_sticker, index + 1, total)
                        StickerTile(
                            model = file,
                            contentDescription = description,
                            selected = sticker.fileName in detail.selected,
                            onClick = { onToggleSticker(sticker.fileName) },
                        )
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.pack_remove_confirm, detail.selected.size, detail.selected.size),
            body = stringResource(R.string.pack_remove_body),
            confirmLabel = stringResource(R.string.pack_action_remove),
            onConfirm = { confirmRemove = false; onRemoveSelected() },
            onDismiss = { confirmRemove = false },
            destructive = true,
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.pack_delete_title),
            body = stringResource(R.string.pack_delete_confirm, pack.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
            destructive = true,
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

/** Icona 64 dp, «n/30 sticker · tipo», stato e pulsanti WhatsApp e Unisci. */
@Composable
private fun PackHeader(
    pack: StickerPack,
    added: Boolean,
    tray: File,
    busy: Boolean,
    canMerge: Boolean,
    onAddToWhatsApp: () -> Unit,
    onOpenMerge: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            StickerImage(
                model = tray,
                contentDescription = null,
                animate = false,
                modifier = Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                val kind = stringResource(if (pack.animated) R.string.kind_animated_pl else R.string.kind_still_pl)
                Text(
                    stringResource(R.string.pack_meta, pack.stickers.size, PackPlanner.MAX_STICKERS, kind),
                    style = MaterialTheme.typography.bodyLarge,
                )
                PackStatusChip(pack, added)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
            if (pack.isAddable) WhatsAppButton(added = added, onClick = onAddToWhatsApp, enabled = !busy)
            OutlinedButton(onClick = onOpenMerge, enabled = !busy && canMerge) {
                Icon(Icons.Default.CallMerge, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.s))
                Text(stringResource(R.string.pack_action_merge_short))
            }
        }
    }
}

/**
 * Griglia della libreria per scegliere gli sticker da aggiungere al pack. Quando la scelta riempie i posti
 * liberi le tessere non selezionate si disattivano (e TalkBack le annuncia come tali).
 * [progress] = (fatti, totale) della conversione in corso, null altrimenti.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPackScreen(
    pack: StickerPack,
    items: List<StickerItem>,
    selected: Set<String>,
    progress: Pair<Int, Int>?,
    fileOf: (StickerItem) -> File,
    snackbarHost: @Composable () -> Unit,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
) {
    val busy = progress != null
    val free = PackPlanner.MAX_STICKERS - pack.stickers.size
    val candidates = remember(pack.stickers, items) {
        val alreadyIn = pack.stickers.mapNotNull { it.sourceId }.toSet()
        items.filter { it.id !in alreadyIn }
    }
    val full = selected.size >= free
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
            progress?.let { (done, all) ->
                BusyBanner(stringResource(R.string.pack_progress, done, all), progress = if (all > 0) done.toFloat() / all else 0f)
            }
            if (candidates.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.AddPhotoAlternate,
                    title = stringResource(R.string.pack_add_empty),
                    body = null,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else {
                val kind = stringResource(if (pack.animated) R.string.capture_kind_animated else R.string.capture_kind_static)
                Text(
                    stringResource(R.string.pack_add_hint, free, kind),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
                if (full) {
                    Text(
                        stringResource(R.string.pack_add_full),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l).padding(bottom = Spacing.s),
                    )
                }
                StickerGrid(Modifier.fillMaxWidth().weight(1f)) {
                    items(candidates, key = { it.id }) { item ->
                        val file = remember(item.fileName) { fileOf(item) }
                        val isSelected = item.id in selected
                        StickerTile(
                            model = file,
                            contentDescription = item.displayName,
                            selected = isSelected,
                            animated = item.animated,
                            enabled = isSelected || !full,
                            onClick = { onToggle(item.id) },
                        )
                    }
                }
            }
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
        containerColor = DialogContainerColor,
        title = { Text(stringResource(R.string.merge_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.merge_hint, target.name), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Spacing.s))
                if (others.isEmpty()) {
                    Text(stringResource(R.string.merge_none), style = MaterialTheme.typography.bodyMedium)
                }
                others.forEach { other ->
                    val checked = other.identifier in state.sourceIds
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggleSource(other.identifier) }),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(other.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                packCountText(other),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (mixed) {
                    Spacer(Modifier.height(Spacing.s))
                    Text(stringResource(R.string.merge_result_type), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    MergeTypeRow(stringResource(R.string.merge_result_animated), state.resultAnimated) { onResultAnimated(true) }
                    MergeTypeRow(stringResource(R.string.merge_result_static), !state.resultAnimated) { onResultAnimated(false) }
                }
                Spacer(Modifier.height(Spacing.s))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(value = state.deleteSources, role = Role.Switch, onValueChange = onDeleteSources),
                ) {
                    Text(stringResource(R.string.merge_delete_sources), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = state.deleteSources, onCheckedChange = null)
                }
                if (state.deleteSources) {
                    Text(
                        stringResource(R.string.merge_delete_sources_help),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (chosen.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.m))
                    Text(stringResource(R.string.merge_summary, total, inTarget, overflow, overflowPacks), style = MaterialTheme.typography.bodyMedium)
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
private fun MergeTypeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RenameDialog(pack: StickerPack, onConfirm: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(pack.identifier) { mutableStateOf(pack.name) }
    var publisher by remember(pack.identifier) { mutableStateOf(pack.publisher) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogContainerColor,
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it.take(128) }, label = { Text(stringResource(R.string.export_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Spacing.s))
                OutlinedTextField(value = publisher, onValueChange = { publisher = it.take(128) }, label = { Text(stringResource(R.string.export_publisher)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, publisher) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
