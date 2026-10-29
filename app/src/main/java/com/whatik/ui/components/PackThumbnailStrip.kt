package com.whatik.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whatik.ui.theme.Spacing
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews
import java.io.File

/** Spazio fra le miniature della striscia. */
private val ThumbnailGap = 6.dp

/**
 * Striscia di miniature di un pack (audit UX 3.2.7, 3.3.4): fino a [max] posti quadrati di [size]
 * (forma small, fondo `surfaceContainerHighest`), sempre al primo fotogramma. Se gli sticker sono di
 * più, l'ultimo posto mostra «+n» (`labelMedium`). Se la riga è più stretta di [max] posti, se ne
 * mostrano meno (il «+n» tiene conto anche di quelli). Decorativa per TalkBack: il conteggio è già nei
 * testi della card.
 *
 * Prestazioni (audit P12): [files] è confrontata per identità; chi chiama la ricorda,
 * per esempio `val files = remember(pack) { stickerFilesOf(pack) }`.
 */
@Composable
fun PackThumbnailStrip(
    files: List<File>,
    max: Int = 6,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty() || max <= 0) return
    BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics {}) {
        val fitting = ((maxWidth + ThumbnailGap) / (size + ThumbnailGap)).toInt().coerceAtLeast(1)
        val slots = minOf(max, fitting)
        val overflow = files.size > slots
        val shown = if (overflow) slots - 1 else files.size
        val shape = MaterialTheme.shapes.small
        val background = MaterialTheme.colorScheme.surfaceContainerHighest
        Row(horizontalArrangement = Arrangement.spacedBy(ThumbnailGap)) {
            for (i in 0 until shown) {
                StickerImage(
                    model = files[i],
                    contentDescription = null,
                    animate = false,
                    modifier = Modifier
                        .size(size)
                        .clip(shape)
                        .background(background)
                        .padding(Spacing.xs),
                )
            }
            if (overflow) {
                Box(
                    Modifier
                        .size(size)
                        .clip(shape)
                        .background(background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "+${files.size - shown}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

@WhatikPreviews
@Composable
private fun PackThumbnailStripPreview() {
    WhatikPreviewSurface {
        PackThumbnailStrip(
            files = List(12) { File("sticker_$it.webp") },
            modifier = Modifier.padding(Spacing.l),
        )
    }
}
