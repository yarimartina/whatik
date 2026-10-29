package com.whatik.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.whatik.R
import com.whatik.ui.theme.Spacing
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews
import com.whatik.ui.theme.WhatikTheme

/** Tipo di [StatusChip]: positivo («Su WhatsApp», «Concesso»), neutro, da sistemare («Mancano 2 sticker…»). */
enum class StatusKind { Success, Neutral, Warning }

/**
 * Chip di stato non interattivo (audit UX 3.2.7): alto almeno 28 dp (cresce con la scala del
 * carattere), forma small, testo `labelMedium`, icona 16 dp.
 * - Success: `successContainer` + `CheckCircle`;
 * - Neutral: `surfaceContainerHighest` / `onSurfaceVariant`, senza icona;
 * - Warning: `errorContainer` + `ErrorOutline`.
 * Il significato è sempre nel testo, non solo nel colore.
 */
@Composable
fun StatusChip(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val extended = WhatikTheme.extended
    val (container, content, icon) = when (kind) {
        StatusKind.Success -> Triple(extended.successContainer, extended.onSuccessContainer, Icons.Filled.CheckCircle)
        StatusKind.Neutral -> Triple(colors.surfaceContainerHighest, colors.onSurfaceVariant, null as ImageVector?)
        StatusKind.Warning -> Triple(colors.errorContainer, colors.onErrorContainer, Icons.Outlined.ErrorOutline)
    }
    Row(
        modifier
            .heightIn(min = 28.dp)
            .background(container, MaterialTheme.shapes.small)
            .padding(start = if (icon != null) Spacing.s else 10.dp, end = 10.dp, top = Spacing.xxs, bottom = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@WhatikPreviews
@Composable
private fun StatusChipPreview() {
    WhatikPreviewSurface {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            StatusChip(stringResource(R.string.pack_status_added), StatusKind.Success)
            StatusChip(stringResource(R.string.pack_status_not_added), StatusKind.Neutral)
            StatusChip(pluralStringResource(R.plurals.pack_status_missing, 1, 1), StatusKind.Warning)
        }
    }
}
