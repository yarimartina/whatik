package com.whatik.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.whatik.R
import com.whatik.data.PackPlanner
import com.whatik.data.StickerPack
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacksScreen(
    packs: List<StickerPack>,
    addedToWhatsApp: Map<String, Boolean>,
    trayOf: (StickerPack) -> File,
    stickerFilesOf: (StickerPack) -> List<File>,
    snackbarHost: @Composable () -> Unit,
    onBack: () -> Unit,
    onAddToWhatsApp: (StickerPack) -> Unit,
    onDelete: (String) -> Unit,
) {
    var packToDelete by remember { mutableStateOf<StickerPack?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_packs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        if (packs.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.packs_empty), textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                items(packs, key = { it.identifier }) { pack ->
                    PackCard(
                        pack = pack,
                        added = addedToWhatsApp[pack.identifier] == true,
                        tray = trayOf(pack),
                        stickerFiles = stickerFilesOf(pack),
                        onAddToWhatsApp = { onAddToWhatsApp(pack) },
                        onDelete = { packToDelete = pack },
                    )
                }
            }
        }
    }

    packToDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { packToDelete = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.pack_delete_confirm, pack.name)) },
            confirmButton = {
                TextButton(onClick = { packToDelete = null; onDelete(pack.identifier) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { packToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun PackCard(
    pack: StickerPack,
    added: Boolean,
    tray: File,
    stickerFiles: List<File>,
    onAddToWhatsApp: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = tray,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            if (pack.animated) R.string.pack_count_animated else R.string.pack_count_static,
                            pack.stickers.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val status = when {
                        !pack.isAddable -> stringResource(R.string.pack_status_too_small, pack.stickers.size, PackPlanner.MIN_STICKERS)
                        added -> stringResource(R.string.pack_status_added)
                        else -> stringResource(R.string.pack_status_not_added)
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (added && pack.isAddable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (stickerFiles.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    stickerFiles.take(6).forEach { file ->
                        AsyncImage(
                            model = file,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                    if (stickerFiles.size > 6) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Text("+${stickerFiles.size - 6}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pack.isAddable) {
                    if (added) {
                        OutlinedButton(onClick = onAddToWhatsApp) { Text(stringResource(R.string.action_reopen_whatsapp)) }
                    } else {
                        Button(onClick = onAddToWhatsApp) { Text(stringResource(R.string.action_add_to_whatsapp)) }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
            }
        }
    }
}
