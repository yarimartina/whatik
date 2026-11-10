package com.whatik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whatik.R
import com.whatik.data.PackPlanner
import com.whatik.data.StickerPack
import com.whatik.ui.components.EmptyState
import com.whatik.ui.components.PackThumbnailStrip
import com.whatik.ui.components.StatusChip
import com.whatik.ui.components.StatusKind
import com.whatik.ui.components.StickerImage
import com.whatik.ui.theme.Spacing
import java.io.File

/**
 * Elenco dei pack (P3). Senza freccia: è una destinazione di primo livello e sotto c'è lo slot
 * [bottomBar] (la navigazione). L'eliminazione è nel dettaglio del pack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacksScreen(
    packs: List<StickerPack>,
    addedToWhatsApp: Map<String, Boolean>,
    trayOf: (StickerPack) -> File,
    stickerFilesOf: (StickerPack) -> List<File>,
    snackbarHost: @Composable () -> Unit,
    onAddToWhatsApp: (StickerPack) -> Unit,
    onOpen: (String) -> Unit,
    onGoToLibrary: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_packs)) }) },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
    ) { padding ->
        if (packs.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Collections,
                title = stringResource(R.string.packs_empty_title),
                body = stringResource(R.string.packs_empty),
                modifier = Modifier.padding(padding).fillMaxSize(),
                actionLabel = stringResource(R.string.packs_empty_action),
                onAction = onGoToLibrary,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(Spacing.l),
                verticalArrangement = Arrangement.spacedBy(Spacing.m),
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                items(packs, key = { it.identifier }) { pack ->
                    PackCard(
                        pack = pack,
                        added = addedToWhatsApp[pack.identifier] == true,
                        tray = remember(pack.identifier, pack.trayFile, pack.imageDataVersion) { trayOf(pack) },
                        stickerFiles = remember(pack) { stickerFilesOf(pack) },
                        onAddToWhatsApp = { onAddToWhatsApp(pack) },
                        onOpen = { onOpen(pack.identifier) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PackCard(
    pack: StickerPack,
    added: Boolean,
    tray: File,
    stickerFiles: List<File>,
    onAddToWhatsApp: () -> Unit,
    onOpen: () -> Unit,
) {
    val openLabel = stringResource(R.string.pack_open_a11y, pack.name)
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = openLabel },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StickerImage(
                    model = tray,
                    contentDescription = null,
                    animate = false,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Column(Modifier.weight(1f).padding(horizontal = Spacing.m)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        packCountText(pack),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PackStatusChip(pack, added)
            PackThumbnailStrip(stickerFiles)
            if (pack.isAddable) {
                WhatsAppButton(added = added, onClick = onAddToWhatsApp)
            }
        }
    }
}

@Composable
internal fun packCountText(pack: StickerPack): String = pluralStringResource(
    if (pack.animated) R.plurals.pack_count_animated_n else R.plurals.pack_count_still_n,
    pack.stickers.size,
    pack.stickers.size,
)

/** Stato del pack: incompleto (Warning), aggiunto (Success) o non ancora aggiunto (Neutral). */
@Composable
internal fun PackStatusChip(pack: StickerPack, added: Boolean, modifier: Modifier = Modifier) {
    when {
        !pack.isAddable -> {
            val missing = (PackPlanner.MIN_STICKERS - pack.stickers.size).coerceAtLeast(1)
            StatusChip(pluralStringResource(R.plurals.pack_status_missing, missing, missing), StatusKind.Warning, modifier)
        }
        added -> StatusChip(stringResource(R.string.pack_status_added), StatusKind.Success, modifier)
        else -> StatusChip(stringResource(R.string.pack_status_not_added), StatusKind.Neutral, modifier)
    }
}

/** «Aggiungi a WhatsApp», o «Apri in WhatsApp» (tonale) se il pack è già stato aggiunto. */
@Composable
internal fun WhatsAppButton(added: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    if (added) {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(stringResource(R.string.action_reopen_whatsapp)) }
    } else {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(stringResource(R.string.action_add_to_whatsapp)) }
    }
}
