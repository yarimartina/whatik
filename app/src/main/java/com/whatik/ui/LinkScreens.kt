package com.whatik.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whatik.R
import com.whatik.data.UrlImporter

/** Finestra per incollare il link di uno sticker o di una pagina. */
@Composable
fun LinkDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember {
        val fromClipboard = runCatching { clipboard.getText()?.text }.getOrNull().orEmpty()
        mutableStateOf(if (UrlImporter.extractUrl(fromClipboard) != null) fromClipboard else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_title)) },
        text = {
            Column {
                Text(stringResource(R.string.link_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.link_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = UrlImporter.extractUrl(text) != null) {
                Text(stringResource(R.string.link_search))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkResultsScreen(
    state: LinkState,
    busy: Boolean,
    snackbarHost: @Composable () -> Unit,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onFilter: (Boolean) -> Unit,
    onSelectAllVisible: (Boolean) -> Unit,
    onImport: () -> Unit,
) {
    val results = state as? LinkState.Results
    val selectedCount = results?.selected?.size ?: 0
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_link_results)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (selectedCount > 0 && !busy) {
                ExtendedFloatingActionButton(
                    onClick = onImport,
                    icon = { Icon(Icons.Default.Download, contentDescription = null) },
                    text = { Text(stringResource(R.string.action_import_count, selectedCount)) },
                )
            }
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (results == null) {
                Text(stringResource(R.string.link_none_found), modifier = Modifier.align(Alignment.Center).padding(32.dp), textAlign = TextAlign.Center)
            } else {
                val visible = results.visible
                Column(Modifier.fillMaxSize()) {
                    Text(
                        results.pageUrl,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = results.onlyStickers,
                            onClick = { onFilter(true) },
                            label = { Text(stringResource(R.string.link_filter_stickers, results.stickerCount)) },
                        )
                        FilterChip(
                            selected = !results.onlyStickers,
                            onClick = { onFilter(false) },
                            label = { Text(stringResource(R.string.link_filter_all, results.candidates.size)) },
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                        val allSelected = visible.isNotEmpty() && visible.all { it.url in results.selected }
                        TextButton(onClick = { onSelectAllVisible(!allSelected) }) {
                            Text(stringResource(if (allSelected) R.string.action_clear_selection else R.string.action_select_all))
                        }
                    }
                    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
                        items(visible, key = { it.url }) { candidate ->
                            SelectableRow(
                                model = candidate.url,
                                title = candidate.name,
                                subtitle = candidate.url,
                                highlighted = candidate.looksSticker,
                                checked = candidate.url in results.selected,
                                onToggle = { onToggle(candidate.url) },
                            )
                        }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}
