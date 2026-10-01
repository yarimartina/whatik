package com.whatik.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.whatik.R
import com.whatik.data.StickerItem
import java.io.File

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
    onImportGallery: () -> Unit,
    onImportFiles: () -> Unit,
    onImportFolder: () -> Unit,
    onScan: () -> Unit,
    onOpenPacks: () -> Unit,
    onExport: () -> Unit,
) {
    var importMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selectionMode = selected.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionMode) stringResource(R.string.title_selected, selected.size)
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
                    if (items.isNotEmpty() && selected.size < items.size) {
                        IconButton(onClick = onSelectAll) {
                            Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                        }
                    }
                    if (selectionMode) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
                        }
                    } else {
                        IconButton(onClick = onOpenPacks) {
                            Icon(Icons.Default.Collections, contentDescription = stringResource(R.string.action_packs))
                        }
                        Box {
                            IconButton(onClick = { importMenu = true }) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_import))
                            }
                            DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_import_gallery)) },
                                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                                    onClick = { importMenu = false; onImportGallery() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_import_files)) },
                                    leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) },
                                    onClick = { importMenu = false; onImportFiles() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_import_folder)) },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                                    onClick = { importMenu = false; onImportFolder() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_scan)) },
                                    leadingIcon = { Icon(Icons.Default.ImageSearch, null) },
                                    onClick = { importMenu = false; onScan() },
                                )
                            }
                        }
                    }
                },
            )
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
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (items.isEmpty()) {
                EmptyState(onImportGallery = onImportGallery, onScan = onScan, onImportFolder = onImportFolder)
            } else {
                StickerGrid(items = items, selected = selected, fileOf = fileOf, onToggle = onToggle)
            }
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.delete_confirm_title, selected.size, selected.size)) },
            text = { Text(stringResource(R.string.delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteSelected() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun StickerGrid(
    items: List<StickerItem>,
    selected: Set<String>,
    fileOf: (StickerItem) -> File,
    onToggle: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            StickerCell(
                item = item,
                file = fileOf(item),
                selected = item.id in selected,
                onToggle = { onToggle(item.id) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerCell(item: StickerItem, file: File, selected: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val borderModifier = if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(borderModifier)
            .combinedClickable(onClick = onToggle, onLongClick = onToggle),
    ) {
        AsyncImage(
            model = file,
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )
        if (item.animated) {
            Text(
                text = stringResource(R.string.badge_animated),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (selected) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .size(24.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(onImportGallery: () -> Unit, onScan: () -> Unit, onImportFolder: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            Icons.Default.EmojiEmotions,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.empty_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Step("1", stringResource(R.string.empty_step1))
            Step("2", stringResource(R.string.empty_step2))
            Step("3", stringResource(R.string.empty_step3))
            Step("4", stringResource(R.string.empty_step4))
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.ImageSearch, null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_scan))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImportGallery, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PhotoLibrary, null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_import_gallery))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImportFolder, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.FolderOpen, null)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.action_import_folder))
        }
    }
}

@Composable
private fun Step(number: String, text: String) {
    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Top) {
        Text(
            number,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
