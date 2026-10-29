package com.whatik.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.whatik.R
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews

/**
 * Voce di una fonte di importazione (audit UX 3.2.7, 3.3.3): `ListItem` alto almeno 72 dp, icona 24 dp
 * in un cerchio 40 dp `secondaryContainer`, freccia decorativa a destra (si specchia in RTL), tutta la
 * riga cliccabile con `Role.Button`. Fondo trasparente: sta dentro una card.
 */
@Composable
fun SourceListItem(
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SourceListItem(rememberVectorPainter(icon), title, supporting, onClick, modifier)
}

/** Come l'altra [SourceListItem], con un'icona da risorsa (`painterResource(R.drawable.…)`). */
@Composable
fun SourceListItem(
    icon: Painter,
    title: String,
    supporting: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    ListItem(
        modifier = modifier
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 72.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                Modifier
                    .size(40.dp)
                    .background(colors.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(24.dp))
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
        },
    )
}

@WhatikPreviews
@Composable
private fun SourceListItemPreview() {
    WhatikPreviewSurface {
        Column {
            SourceListItem(
                Icons.Filled.Videocam,
                stringResource(R.string.source_video_title),
                stringResource(R.string.source_video_body),
                onClick = {},
            )
            HorizontalDivider()
            SourceListItem(
                Icons.Filled.PhotoLibrary,
                stringResource(R.string.source_gallery_title),
                stringResource(R.string.source_gallery_body),
                onClick = {},
            )
        }
    }
}
