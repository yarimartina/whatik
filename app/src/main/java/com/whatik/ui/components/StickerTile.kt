package com.whatik.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whatik.R
import com.whatik.ui.theme.Spacing
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews

/** Durata delle animazioni di selezione (audit UX 3.2.5). */
private const val SELECTION_ANIMATION_MS = 120

/** Distanza di indicatore e badge dal bordo della tessera. */
private val TileCornerInset = 6.dp

/**
 * Tessera quadrata di uno sticker, l'unica cella delle griglie dell'app (audit UX 3.2.7).
 *
 * Stati: **normale** (anello vuoto in alto a destra), **selezionato** (bordo 3 dp `primary`, contenuto
 * al 90 %, indicatore pieno, 120 ms), **disabilitato** (alfa 0,38, non cliccabile), **premuto** (ripple).
 * [animated] mostra il badge «Animato» e aggiunge «, animato» alla descrizione letta da TalkBack.
 * [caption]: una riga `bodySmall` sotto il quadrato (cartella, nome file).
 *
 * Accessibilità: un solo nodo (descrizione, eventuale didascalia), `Role.Checkbox`, `selected`,
 * `stateDescription` «Selezionato»/«Non selezionato», azioni «Seleziona»/«Deseleziona» e
 * [onLongClickLabel]; vibrazione alla pressione lunga.
 *
 * Prestazioni (audit P12): [model] è confrontato per identità, quindi va passato un oggetto stabile
 * fra le ricomposizioni (`remember(item.fileName) { fileOf(item) }`, l'Uri/URL del modello dati), e
 * [onClick]/[onLongClick] devono catturare solo valori stabili (id, lambda del ViewModel). Così, quando
 * cambia la selezione, si ricompone solo la tessera toccata.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StickerTile(
    model: Any?,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    animated: Boolean = false,
    enabled: Boolean = true,
    showSelectionIndicator: Boolean = true,
    caption: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    animate: Boolean = rememberAnimationsEnabled(),
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val motion = rememberAnimationsEnabled()
    val haptic = LocalHapticFeedback.current
    val scale = animateFloatAsState(
        targetValue = if (selected) 0.9f else 1f,
        animationSpec = if (motion) tween(SELECTION_ANIMATION_MS) else snap(),
        label = "stickerTileScale",
    )
    val description = if (animated) {
        contentDescription + ", " + stringResource(R.string.capture_kind_animated)
    } else {
        contentDescription
    }
    val stateText = stringResource(if (selected) R.string.state_selected else R.string.state_not_selected)
    val clickLabel = stringResource(if (selected) R.string.action_deselect else R.string.action_select)
    val longClick: (() -> Unit)? = onLongClick?.let { action ->
        {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            action()
        }
    }
    Column(
        modifier
            .alpha(if (enabled) 1f else 0.38f)
            .clip(shape)
            .combinedClickable(
                enabled = enabled,
                role = Role.Checkbox,
                onClickLabel = clickLabel,
                onLongClickLabel = onLongClickLabel,
                onLongClick = longClick,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = description
                this.selected = selected
                this.stateDescription = stateText
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(colors.surfaceContainerHigh)
                .then(if (selected) Modifier.border(3.dp, colors.primary, shape) else Modifier),
        ) {
            StickerImage(
                model = model,
                contentDescription = null,
                animate = animate,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                    }
                    .padding(Spacing.s),
            )
            if (animated) {
                AnimatedBadge(Modifier.align(Alignment.BottomStart).padding(TileCornerInset))
            }
            if (showSelectionIndicator) {
                SelectionIndicator(selected, Modifier.align(Alignment.TopEnd).padding(TileCornerInset))
            }
        }
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            )
        }
    }
}

/**
 * Indicatore di selezione: cerchio 24 dp. Vuoto = bordo 2 dp `outline` su `surface` al 70 %;
 * pieno = `primary` con spunta 16 dp `onPrimary`. Passaggio con dissolvenza di 120 ms (istantaneo senza
 * animazioni di sistema). Solo grafica: lo stato lo annuncia il contenitore (vedi [StickerTile]).
 */
@Composable
fun SelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberAnimationsEnabled()
    val progress = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (motion) tween(SELECTION_ANIMATION_MS) else snap(),
        label = "selectionIndicator",
    )
    val ring = colors.outline
    val ringFill = colors.surface
    val fill = colors.primary
    Box(
        modifier
            .size(24.dp)
            .drawBehind {
                val p = progress.value
                if (p < 1f) {
                    val stroke = 2.dp.toPx()
                    drawCircle(ringFill.copy(alpha = 0.7f * (1f - p)))
                    drawCircle(ring.copy(alpha = 1f - p), radius = (size.minDimension - stroke) / 2f, style = Stroke(stroke))
                }
                if (p > 0f) drawCircle(fill.copy(alpha = p))
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = colors.onPrimary,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { alpha = progress.value },
        )
    }
}

/**
 * Badge «Animato» in basso a sinistra della tessera: pillola nera al 60 % (fissa, sta sopra l'immagine),
 * testo `labelSmall` bianco; con scala del carattere oltre 1,3 diventa la sola icona `Animation` 14 dp.
 * Decorativo per TalkBack: «animato» è già nella descrizione della tessera.
 */
@Composable
fun AnimatedBadge(modifier: Modifier = Modifier) {
    val iconOnly = LocalDensity.current.fontScale > 1.3f
    Box(
        modifier
            .clearAndSetSemantics {}
            .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
            .padding(horizontal = if (iconOnly) Spacing.xs else 6.dp, vertical = if (iconOnly) Spacing.xs else Spacing.xxs),
    ) {
        if (iconOnly) {
            Icon(Icons.Filled.Animation, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        } else {
            Text(
                text = stringResource(R.string.badge_animated),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

@WhatikPreviews
@Composable
private fun StickerTilePreview() {
    WhatikPreviewSurface {
        Row(Modifier.padding(Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            StickerTile(model = null, contentDescription = "Sorriso", selected = false, onClick = {}, modifier = Modifier.width(96.dp))
            StickerTile(
                model = null,
                contentDescription = "Cuore",
                selected = true,
                animated = true,
                onClick = {},
                modifier = Modifier.width(96.dp),
            )
            StickerTile(
                model = null,
                contentDescription = "Gatto",
                selected = false,
                enabled = false,
                caption = "TikTok",
                onClick = {},
                modifier = Modifier.width(96.dp),
            )
        }
    }
}
