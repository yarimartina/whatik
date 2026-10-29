package com.whatik.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.whatik.R
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews
import com.whatik.ui.theme.WhatikTheme

/** Le tre destinazioni di primo livello, nell'ordine della pipeline: prendere, scegliere, mandare. */
enum class TopLevelDestination { CAPTURE, LIBRARY, PACKS }

@StringRes
private fun TopLevelDestination.label(): Int = when (this) {
    TopLevelDestination.CAPTURE -> R.string.nav_capture
    TopLevelDestination.LIBRARY -> R.string.nav_library
    TopLevelDestination.PACKS -> R.string.nav_packs
}

private fun TopLevelDestination.icon(selected: Boolean): ImageVector = when (this) {
    TopLevelDestination.CAPTURE -> if (selected) Icons.Filled.RadioButtonChecked else Icons.Outlined.RadioButtonChecked
    TopLevelDestination.LIBRARY -> if (selected) Icons.Filled.EmojiEmotions else Icons.Outlined.EmojiEmotions
    TopLevelDestination.PACKS -> if (selected) Icons.Filled.Collections else Icons.Outlined.Collections
}

/**
 * Barra di navigazione principale (audit UX 3.1): Cattura, Libreria, Pack, etichette sempre visibili
 * (quella selezionata in grassetto). Con [captureActive] la Cattura ha un puntino `success` e TalkBack
 * aggiunge «Sessione attiva». [onSelect] riceve anche la destinazione già selezionata.
 * Va nello slot `bottomBar` dello Scaffold; in modalità selezione la sostituisce [SelectionBottomBar].
 */
@Composable
fun WhatikNavigationBar(
    current: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
    captureActive: Boolean,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier) {
        TopLevelDestination.entries.forEach { destination ->
            val selected = destination == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(destination) },
                icon = {
                    val icon = destination.icon(selected)
                    if (destination == TopLevelDestination.CAPTURE && captureActive) {
                        val activeLabel = stringResource(R.string.nav_capture_active)
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = WhatikTheme.extended.success,
                                    contentColor = WhatikTheme.extended.onSuccess,
                                    modifier = Modifier.semantics { contentDescription = activeLabel },
                                )
                            },
                        ) {
                            Icon(icon, contentDescription = null)
                        }
                    } else {
                        Icon(icon, contentDescription = null)
                    }
                },
                label = {
                    Text(
                        stringResource(destination.label()),
                        fontWeight = if (selected) FontWeight.Bold else null,
                    )
                },
                alwaysShowLabel = true,
            )
        }
    }
}

@WhatikPreviews
@Composable
private fun WhatikNavigationBarPreview() {
    WhatikPreviewSurface {
        WhatikNavigationBar(current = TopLevelDestination.LIBRARY, onSelect = {}, captureActive = true)
    }
}
