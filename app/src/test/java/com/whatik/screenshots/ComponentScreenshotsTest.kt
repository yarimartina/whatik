package com.whatik.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.whatik.R
import com.whatik.ui.components.BarAction
import com.whatik.ui.components.BlockingProgressDialog
import com.whatik.ui.components.BusyBanner
import com.whatik.ui.components.ConfirmDialog
import com.whatik.ui.components.EmptyState
import com.whatik.ui.components.PackThumbnailStrip
import com.whatik.ui.components.PermissionItem
import com.whatik.ui.components.SectionHeader
import com.whatik.ui.components.SelectionBottomBar
import com.whatik.ui.components.SourceListItem
import com.whatik.ui.components.StatusChip
import com.whatik.ui.components.StatusKind
import com.whatik.ui.components.StickerGrid
import com.whatik.ui.components.StickerTile
import com.whatik.ui.components.TopLevelDestination
import com.whatik.ui.components.WhatikNavigationBar
import com.whatik.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * I componenti condivisi di `ui/components` (passo P0 del redesign), in tema chiaro e scuro e con la
 * scala del carattere a 2,0 (file component-*-font2-*.png). Le tessere usano gli sticker finti di
 * [FakeData]; l'ultima punta a un file che non esiste e mostra lo stato di errore.
 */
abstract class ComponentScenarios(suffix: String) : ScreenshotTestBase(suffix) {

    /** Scala del carattere simulata: i dialoghi (finestre a parte) non la ricevono, per questo hanno solo la scala 1,0. */
    @Composable
    private fun FontScale(scale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale), content = content)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Grid(fontScale: Float = 1f) {
        FontScale(fontScale) {
            Scaffold(
                topBar = { TopAppBar(title = { Text(stringResource(R.string.title_library)) }) },
                bottomBar = { WhatikNavigationBar(TopLevelDestination.LIBRARY, onSelect = {}, captureActive = true) },
            ) { padding ->
                Column(Modifier.padding(padding)) {
                    BusyBanner(stringResource(R.string.library_busy), progress = 0.4f)
                    Text(
                        stringResource(R.string.library_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    )
                    val items = fake.items.take(11)
                    val selected = setOf(items[1].id, items[4].id)
                    StickerGrid {
                        items(items, key = { it.id }) { item ->
                            StickerTile(
                                model = fake.file(item),
                                contentDescription = item.displayName,
                                selected = item.id in selected,
                                animated = item.animated,
                                enabled = item.id != items[7].id,
                                onClick = {},
                                onLongClick = {},
                                onLongClickLabel = stringResource(R.string.action_crop),
                            )
                        }
                        item(key = "missing") {
                            StickerTile(model = File("/missing/sticker.webp"), contentDescription = "?", selected = false, onClick = {})
                        }
                    }
                }
            }
        }
    }

    @Test
    fun componentGrid() {
        show { Grid() }
        snap("component-grid")
    }

    @Test
    fun componentGridFont2() {
        show { Grid(fontScale = 2f) }
        snap("component-grid-font2")
    }

    @Composable
    private fun Selection(fontScale: Float = 1f) {
        FontScale(fontScale) {
            Scaffold(
                bottomBar = {
                    SelectionBottomBar(
                        listOf(
                            BarAction(Icons.Filled.Crop, stringResource(R.string.action_crop), enabled = false, onClick = {}),
                            BarAction(Icons.Outlined.Delete, stringResource(R.string.action_delete), destructive = true, onClick = {}),
                        ),
                    )
                },
            ) { padding ->
                // griglia con didascalie, come «Cerca sul telefono»
                val candidates = fake.scanCandidates
                StickerGrid(Modifier.padding(padding)) {
                    items(candidates, key = { it.uri.toString() }) { candidate ->
                        StickerTile(
                            model = candidate.uri,
                            contentDescription = candidate.displayName,
                            caption = candidate.path.trimEnd('/').substringAfterLast('/'),
                            selected = candidate.looksTikTok,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    fun componentSelection() {
        show { Selection() }
        snap("component-selection")
    }

    @Test
    fun componentSelectionFont2() {
        show { Selection(fontScale = 2f) }
        snap("component-selection-font2")
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Lists(fontScale: Float = 1f) {
        FontScale(fontScale) {
            Scaffold(
                bottomBar = { WhatikNavigationBar(TopLevelDestination.CAPTURE, onSelect = {}, captureActive = false) },
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.l),
                ) {
                    SectionHeader(stringResource(R.string.capture_permissions_title))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        PermissionItem(
                            title = stringResource(R.string.capture_step_overlay),
                            supporting = stringResource(R.string.capture_step_overlay_help),
                            granted = true,
                            required = true,
                            actionLabel = stringResource(R.string.capture_grant),
                            onAction = {},
                        )
                        PermissionItem(
                            title = stringResource(R.string.capture_step_notifications),
                            supporting = stringResource(R.string.capture_step_notifications_help),
                            granted = false,
                            required = false,
                            actionLabel = stringResource(R.string.capture_grant),
                            onAction = {},
                        )
                        PermissionItem(
                            title = stringResource(R.string.capture_step_notifications),
                            supporting = stringResource(R.string.capture_notifications_blocked),
                            granted = false,
                            required = false,
                            actionLabel = stringResource(R.string.action_open_settings),
                            onAction = {},
                        )
                    }
                    SectionHeader(stringResource(R.string.capture_sources_title), Modifier.padding(top = Spacing.l))
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        SourceListItem(Icons.Filled.Videocam, stringResource(R.string.source_video_title), stringResource(R.string.source_video_body), onClick = {})
                        HorizontalDivider()
                        SourceListItem(Icons.Filled.PhotoLibrary, stringResource(R.string.source_gallery_title), stringResource(R.string.source_gallery_body), onClick = {})
                        HorizontalDivider()
                        SourceListItem(Icons.Filled.ImageSearch, stringResource(R.string.source_scan_title), stringResource(R.string.source_scan_body), onClick = {})
                        HorizontalDivider()
                        SourceListItem(Icons.Filled.Link, stringResource(R.string.source_link_title), stringResource(R.string.source_link_body), onClick = {})
                        HorizontalDivider()
                        SourceListItem(painterResource(R.drawable.ic_motion), stringResource(R.string.radial_all), stringResource(R.string.capture_mode_all_body), onClick = {})
                        HorizontalDivider()
                        SourceListItem(Icons.Filled.FolderOpen, stringResource(R.string.source_folder_title), stringResource(R.string.source_folder_body), onClick = {})
                    }
                    SectionHeader(stringResource(R.string.title_packs), Modifier.padding(top = Spacing.l))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        StatusChip(stringResource(R.string.pack_status_added), StatusKind.Success)
                        StatusChip(stringResource(R.string.pack_status_not_added), StatusKind.Neutral)
                        StatusChip(pluralStringResource(R.plurals.pack_status_missing, 1, 1), StatusKind.Warning)
                    }
                    val big = fake.packs[0]
                    val small = fake.packs[2]
                    PackThumbnailStrip(big.stickers.map { fake.stickerFile(big, it.fileName) }, modifier = Modifier.padding(top = Spacing.m))
                    PackThumbnailStrip(small.stickers.map { fake.stickerFile(small, it.fileName) }, modifier = Modifier.padding(top = Spacing.m))
                    BusyBanner(stringResource(R.string.pack_progress, 3, 8), progress = 3f / 8f, modifier = Modifier.padding(vertical = Spacing.l))
                }
            }
        }
    }

    @Test
    fun componentLists() {
        show { Lists() }
        snap("component-lists")
    }

    @Test
    fun componentListsFont2() {
        show { Lists(fontScale = 2f) }
        snap("component-lists-font2")
    }

    @Composable
    private fun Empty(fontScale: Float = 1f) {
        FontScale(fontScale) {
            Scaffold(
                bottomBar = { WhatikNavigationBar(TopLevelDestination.PACKS, onSelect = {}, captureActive = true) },
            ) { padding ->
                EmptyState(
                    icon = Icons.Filled.Collections,
                    title = stringResource(R.string.packs_empty_title),
                    body = stringResource(R.string.packs_empty),
                    modifier = Modifier.padding(padding),
                    actionLabel = stringResource(R.string.packs_empty_action),
                    onAction = {},
                )
            }
        }
    }

    @Test
    fun componentEmpty() {
        show { Empty() }
        snap("component-empty")
    }

    @Test
    fun componentEmptyFont2() {
        show { Empty(fontScale = 2f) }
        snap("component-empty-font2")
    }

    @Test
    fun componentConfirmDialog() {
        show(dimmed = true) {
            Grid()
            ConfirmDialog(
                title = pluralStringResource(R.plurals.delete_confirm_title, 2, 2),
                body = stringResource(R.string.delete_confirm_body),
                confirmLabel = stringResource(R.string.action_delete),
                onConfirm = {},
                onDismiss = {},
                destructive = true,
            )
        }
        snapScreen("component-confirm-dialog")
    }

    @Test
    fun componentProgressDialog() {
        show(dimmed = true) {
            Lists()
            BlockingProgressDialog(
                title = stringResource(R.string.progress_title),
                body = stringResource(R.string.progress_body, 3, 8, "Reazioni TikTok"),
                progress = 3f / 8f,
                onCancel = {},
            )
        }
        snapScreen("component-progress-dialog")
    }

    @Test
    fun componentNarrowStrip() {
        // la striscia in una riga stretta: mostra meno posti e il «+n» li conta
        show {
            Column(Modifier.fillMaxWidth(0.5f).padding(Spacing.l)) {
                val big = fake.packs[0]
                PackThumbnailStrip(big.stickers.map { fake.stickerFile(big, it.fileName) })
            }
        }
        snap("component-strip-narrow")
    }

    /** Quello che TalkBack legge di una tessera (audit UX 3.3.2, criterio di P1): nessuno scatto, solo verifiche. */
    @Test
    fun componentTileSemantics() {
        show {
            StickerTile(
                model = null,
                contentDescription = "Sorriso",
                selected = false,
                animated = true,
                onClick = {},
                onLongClick = {},
                onLongClickLabel = "Ritaglia",
                modifier = Modifier.size(104.dp),
            )
        }
        val config = compose.onNodeWithContentDescription("Sorriso, animato").fetchSemanticsNode().config
        assertEquals(Role.Checkbox, config[SemanticsProperties.Role])
        assertEquals(false, config[SemanticsProperties.Selected])
        assertEquals("Non selezionato", config[SemanticsProperties.StateDescription])
        assertEquals("Seleziona", config[SemanticsActions.OnClick].label)
        assertEquals("Ritaglia", config[SemanticsActions.OnLongClick].label)
    }
}

/** Componenti in tema chiaro (file component-*-light.png). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = Phone.LIGHT)
class LightComponentScreenshotsTest : ComponentScenarios("light")

/** Componenti in tema scuro (file component-*-dark.png). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = Phone.DARK)
class DarkComponentScreenshotsTest : ComponentScenarios("dark")
