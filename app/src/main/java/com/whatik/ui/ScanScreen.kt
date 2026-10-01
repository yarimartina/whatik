package com.whatik.ui

import android.net.Uri
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whatik.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    state: ScanState,
    busy: Boolean,
    snackbarHost: @Composable () -> Unit,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onRescan: () -> Unit,
    onToggle: (Uri) -> Unit,
    onFilter: (Boolean) -> Unit,
    onSelectAllVisible: (Boolean) -> Unit,
    onImport: () -> Unit,
) {
    val loaded = state as? ScanState.Loaded
    val selectedCount = loaded?.selected?.size ?: 0

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_scan)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (loaded != null) {
                        IconButton(onClick = onRescan) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_rescan))
                        }
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
            when (state) {
                is ScanState.Idle, is ScanState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ScanState.PermissionDenied -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.scan_permission_body), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onRequestPermission) { Text(stringResource(R.string.action_grant_permission)) }
                }
                is ScanState.Loaded -> LoadedContent(state, onToggle, onFilter, onSelectAllVisible)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun LoadedContent(
    state: ScanState.Loaded,
    onToggle: (Uri) -> Unit,
    onFilter: (Boolean) -> Unit,
    onSelectAllVisible: (Boolean) -> Unit,
) {
    val visible = state.visible
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.onlyTikTok,
                onClick = { onFilter(true) },
                label = { Text(stringResource(R.string.scan_filter_tiktok, state.tiktokCount)) },
            )
            FilterChip(
                selected = !state.onlyTikTok,
                onClick = { onFilter(false) },
                label = { Text(stringResource(R.string.scan_filter_all, state.candidates.size)) },
            )
        }
        Text(
            stringResource(R.string.scan_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            val allSelected = visible.isNotEmpty() && visible.all { it.uri in state.selected }
            TextButton(onClick = { onSelectAllVisible(!allSelected) }) {
                Text(stringResource(if (allSelected) R.string.action_clear_selection else R.string.action_select_all))
            }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.scan_empty), textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
                items(visible, key = { it.uri.toString() }) { candidate ->
                    SelectableRow(
                        model = candidate.uri,
                        title = candidate.displayName,
                        subtitle = candidate.path,
                        highlighted = candidate.looksTikTok,
                        checked = candidate.uri in state.selected,
                        onToggle = { onToggle(candidate.uri) },
                    )
                }
            }
        }
    }
}
