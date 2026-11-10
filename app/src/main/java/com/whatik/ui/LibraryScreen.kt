package com.whatik.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.whatik.R
import com.whatik.data.StickerItem
import com.whatik.ui.components.BarAction
import com.whatik.ui.components.BusyBanner
import com.whatik.ui.components.ConfirmDialog
import com.whatik.ui.components.DialogContainerColor
import com.whatik.ui.components.EmptyState
import com.whatik.ui.components.SelectionBottomBar
import com.whatik.ui.components.StickerGrid
import com.whatik.ui.components.StickerTile
import com.whatik.ui.theme.Spacing
import com.whatik.util.AppInfo
import java.io.File

/**
 * Libreria (P1). Senza selezione la barra in basso è lo slot [bottomBar] (la navigazione); in selezione
 * la sostituisce la [SelectionBottomBar] con Ritaglia ed Elimina. I parametri `onImport*`, [onScan] e
 * [onOpenPacks] non sono più usati qui (le azioni passano alla Cattura e alla navigazione): restano
 * nella firma fino a P10.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    items: List<StickerItem>,
    selected: Set<String>,
    busy: Boolean,
    fileOf: (StickerItem) -> File,
    snackbarHost: @Composable () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCropSelected: () -> Unit,
    onEdit: (String) -> Unit,
    onCapture: () -> Unit,
    onImportVideo: () -> Unit,
    onImportLink: () -> Unit,
    onImportGallery: () -> Unit,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onScan: () -> Unit,
    onOpenPacks: () -> Unit,
    onExport: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    var moreMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    val selectionMode = selected.isNotEmpty()
    val allSelected = items.isNotEmpty() && selected.size >= items.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) pluralStringResource(R.plurals.title_selected_count, selected.size, selected.size)
                        else stringResource(R.string.title_library),
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = onClearSelection) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_clear_selection))
                        }
                    }
                },
                actions = {
                    if (selectionMode) {
                        TextButton(onClick = if (allSelected) onClearSelection else onSelectAll) {
                            Text(stringResource(if (allSelected) R.string.action_deselect_all else R.string.action_select_all))
                        }
                    } else {
                        if (items.isNotEmpty()) {
                            IconButton(onClick = onSelectAll) {
                                Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                            }
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_info)) },
                                    onClick = { moreMenu = false; showInfo = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (selectionMode) {
                val cropLabel = stringResource(R.string.action_crop)
                val deleteLabel = stringResource(R.string.action_delete)
                val single = selected.size == 1
                SelectionBottomBar(
                    remember(single, cropLabel, deleteLabel, onCropSelected) {
                        listOf(
                            BarAction(Icons.Default.Crop, cropLabel, enabled = single, onClick = onCropSelected),
                            BarAction(Icons.Default.Delete, deleteLabel, destructive = true, onClick = { confirmDelete = true }),
                        )
                    },
                )
            } else {
                bottomBar()
            }
        },
        floatingActionButton = {
            if (selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = onExport,
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_export, selected.size)) },
                )
            }
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) BusyBanner(stringResource(R.string.library_busy))
            if (items.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.EmojiEmotions,
                    title = stringResource(R.string.empty_title),
                    body = stringResource(R.string.empty_body),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    actionLabel = stringResource(R.string.empty_action_capture),
                    onAction = onCapture,
                )
            } else {
                if (!selectionMode) {
                    Text(
                        stringResource(R.string.library_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                    )
                }
                val cropLabel = stringResource(R.string.action_crop)
                StickerGrid(Modifier.fillMaxWidth().weight(1f)) {
                    items(items, key = { it.id }) { item ->
                        val file = remember(item.fileName) { fileOf(item) }
                        StickerTile(
                            model = file,
                            contentDescription = item.displayName,
                            selected = item.id in selected,
                            animated = item.animated,
                            onClick = { onToggle(item.id) },
                            onLongClick = { onEdit(item.id) },
                            onLongClickLabel = cropLabel,
                        )
                    }
                }
            }
        }
    }

    if (showInfo) {
        InfoDialog(onDismiss = { showInfo = false })
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_confirm_title, selected.size, selected.size),
            body = stringResource(R.string.delete_confirm_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { confirmDelete = false; onDeleteSelected() },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
private fun InfoDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val info = remember { runCatching { AppInfo.read(context) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogContainerColor,
        title = { Text(stringResource(R.string.info_title)) },
        text = {
            Column {
                Text(stringResource(R.string.info_version, info?.versionName ?: "?", info?.versionCode ?: 0L), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Spacing.s))
                Text(
                    stringResource(R.string.info_signature, info?.signatureSha256 ?: "?"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(Spacing.m))
                Text(stringResource(R.string.info_update_hint), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
