package com.whatik.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.whatik.R
import com.whatik.ui.theme.Spacing
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews
import com.whatik.ui.theme.WhatikTheme

/**
 * Stato vuoto di una schermata (audit UX 3.2.7): contenuto centrato largo al massimo 360 dp, icona
 * 64 dp nel colore del marchio (solo grafica), titolo `headlineSmall` (heading per TalkBack), corpo
 * `bodyLarge` `onSurfaceVariant` e, se [actionLabel] e [onAction] ci sono, un `Button` pieno.
 * Occupa tutto lo spazio che riceve e scorre se il testo non ci sta (scala del carattere 2,0).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val minHeight = if (constraints.hasBoundedHeight) maxHeight else 0.dp
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                Modifier.widthIn(max = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, contentDescription = null, tint = WhatikTheme.extended.brand, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(Spacing.l))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                if (body != null) {
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (actionLabel != null && onAction != null) {
                    Spacer(Modifier.height(Spacing.xl))
                    Button(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

@WhatikPreviews
@Composable
private fun EmptyStatePreview() {
    WhatikPreviewSurface {
        EmptyState(
            icon = Icons.Filled.EmojiEmotions,
            title = stringResource(R.string.empty_title),
            body = stringResource(R.string.empty_body),
            actionLabel = stringResource(R.string.empty_action_capture),
            onAction = {},
        )
    }
}
