package com.whatik.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.whatik.ui.theme.Spacing
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews

/** Padding predefinito delle griglie: 12 dp ai lati, 4 sopra, 88 sotto (spazio per il FAB). */
val StickerGridPadding = PaddingValues(start = Spacing.m, top = Spacing.xs, end = Spacing.m, bottom = Spacing.fabClearance)

/**
 * L'unica griglia dell'app (audit UX 3.2.7): colonne adattive di almeno [minCellSize], 8 dp fra le celle.
 * Il contenuto sono di solito `items(list, key = { it.id }) { StickerTile(...) }`: la chiave stabile
 * mantiene stato e animazioni della tessera quando la lista cambia.
 * [state] serve solo a chi deve leggere o comandare lo scorrimento.
 */
@Composable
fun StickerGrid(
    modifier: Modifier = Modifier,
    minCellSize: Dp = 104.dp,
    contentPadding: PaddingValues = StickerGridPadding,
    state: LazyGridState = rememberLazyGridState(),
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = minCellSize),
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
        content = content,
    )
}

@WhatikPreviews
@Composable
private fun StickerGridPreview() {
    WhatikPreviewSurface {
        val names = listOf("Sorriso", "Cuore", "Risata", "Gatto", "Stella", "Va bene")
        StickerGrid {
            items(names, key = { it }) { name ->
                StickerTile(
                    model = null,
                    contentDescription = name,
                    selected = name == "Cuore",
                    animated = name == "Risata",
                    onClick = {},
                )
            }
        }
    }
}
