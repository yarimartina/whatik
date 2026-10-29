package com.whatik.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.whatik.R
import com.whatik.ui.theme.Spacing
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews

/** Sotto questa larghezza il testo non sta più accanto al pulsante: il pulsante va sotto il testo. */
private val MinTextWidth = 160.dp

/**
 * Riga di un permesso della Cattura (audit UX 3.2.7, 3.3.3), con le misure di un `ListItem` M3 su fondo
 * trasparente (sta dentro la card della sessione):
 * - icona `CheckCircle` (`primary`) se concesso, `RadioButtonUnchecked` (`onSurfaceVariant`) se no;
 * - sopratitolo «Obbligatorio» / «Consigliato» ([required]) in `labelMedium`, titolo `bodyLarge`;
 * - [supporting] (`bodyMedium`) solo finché il permesso non è concesso;
 * - a destra il chip «Concesso» oppure un `FilledTonalButton` con [actionLabel] («Concedi»,
 *   «Apri impostazioni» se il permesso è bloccato).
 * Se accanto al pulsante restano meno di 160 dp per il testo (etichetta lunga, scala del carattere
 * grande, schermo stretto) il pulsante va sotto il testo invece di schiacciarlo.
 * Per TalkBack i testi sono un solo elemento, il pulsante un secondo.
 */
@Composable
fun PermissionItem(
    title: String,
    supporting: String?,
    granted: Boolean,
    required: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val showSupporting = !granted && supporting != null
    Layout(
        modifier = modifier
            .heightIn(min = 72.dp)
            .padding(start = Spacing.l, end = Spacing.xl, top = Spacing.m, bottom = Spacing.m),
        content = {
            Icon(
                if (granted) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (granted) colors.primary else colors.onSurfaceVariant,
            )
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    stringResource(if (required) R.string.capture_required else R.string.capture_recommended),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                if (showSupporting && supporting != null) {
                    Text(supporting, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            Box {
                if (granted) {
                    StatusChip(stringResource(R.string.capture_granted), StatusKind.Success)
                } else {
                    FilledTonalButton(onClick = onAction) { Text(actionLabel) }
                }
            }
        },
    ) { measurables, constraints ->
        val gap = Spacing.l.roundToPx()
        val width = constraints.maxWidth
        val loose = Constraints(maxWidth = width)
        val icon = measurables[0].measure(loose)
        val textMax = (width - icon.width - gap).coerceAtLeast(0)
        val trailing = measurables[2].measure(Constraints(maxWidth = textMax))
        val besideWidth = textMax - gap - trailing.width
        val stacked = besideWidth < MinTextWidth.roundToPx()
        val text = measurables[1].measure(Constraints(maxWidth = if (stacked) textMax else besideWidth.coerceAtLeast(0)))
        val textX = icon.width + gap
        // allineamento come il ListItem: al centro con una o due righe, in alto se c'è il testo di supporto
        val centered = !showSupporting
        if (stacked) {
            val actionTop = text.height + Spacing.s.roundToPx()
            val height = maxOf(icon.height, actionTop + trailing.height).coerceAtLeast(constraints.minHeight)
            layout(width, height) {
                icon.placeRelative(0, 0)
                text.placeRelative(textX, 0)
                trailing.placeRelative(textX, actionTop)
            }
        } else {
            val height = maxOf(icon.height, text.height, trailing.height).coerceAtLeast(constraints.minHeight)
            fun top(h: Int) = if (centered) (height - h) / 2 else 0
            layout(width, height) {
                icon.placeRelative(0, top(icon.height))
                text.placeRelative(textX, top(text.height))
                trailing.placeRelative(width - trailing.width, top(trailing.height))
            }
        }
    }
}

@WhatikPreviews
@Composable
private fun PermissionItemPreview() {
    WhatikPreviewSurface {
        Column {
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
    }
}
