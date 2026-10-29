package com.whatik.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whatik.R
import com.whatik.ui.theme.Spacing
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews

/**
 * Azione della [SelectionBottomBar]. [destructive] = icona ed etichetta in `error` (Elimina, Rimuovi).
 * Contiene una lambda: costruire la lista con `remember(...)` se la barra deve saltare le ricomposizioni.
 */
data class BarAction(
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Barra in basso della modalità selezione (Libreria, Dettaglio pack), al posto della NavigationBar
 * (audit UX 3.1, 3.2.7): `BottomAppBar` con azioni etichettate distribuite sulla larghezza; ognuna ha
 * icona 24 dp sopra un'etichetta `labelMedium`, area minima 64 × 48 dp, `Role.Button`. Le azioni
 * disabilitate sono al 38 %. Il FAB principale resta nello slot del FAB dello Scaffold, sopra la barra.
 */
@Composable
fun SelectionBottomBar(actions: List<BarAction>, modifier: Modifier = Modifier) {
    BottomAppBar(modifier = modifier, contentPadding = PaddingValues(horizontal = Spacing.xl)) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action -> BarActionButton(action) }
        }
    }
}

@Composable
private fun BarActionButton(action: BarAction) {
    val color = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .widthIn(min = 64.dp)
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = action.enabled, role = Role.Button, onClick = action.onClick)
            .alpha(if (action.enabled) 1f else 0.38f)
            .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(action.icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@WhatikPreviews
@Composable
private fun SelectionBottomBarPreview() {
    WhatikPreviewSurface {
        SelectionBottomBar(
            listOf(
                BarAction(Icons.Filled.Crop, stringResource(R.string.action_crop), enabled = false, onClick = {}),
                BarAction(Icons.Outlined.Delete, stringResource(R.string.action_delete), destructive = true, onClick = {}),
            ),
        )
    }
}
