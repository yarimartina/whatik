package com.whatik.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.whatik.R
import com.whatik.ui.theme.Spacing
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews

/**
 * Intestazione di sezione (audit UX 3.2.7): `titleSmall` in `primary`, 8 dp sopra e sotto, heading per TalkBack.
 * Va sul fondo della schermata (`surface`), di un bottom sheet (`surfaceContainerLow`) o su `surfaceContainer`:
 * dentro una card `surfaceContainerHigh` il `primary` chiaro scende a 4,35:1, sotto il minimo per il testo.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(vertical = Spacing.s)
            .semantics { heading() },
    )
}

@WhatikPreviews
@Composable
private fun SectionHeaderPreview() {
    WhatikPreviewSurface {
        SectionHeader(stringResource(R.string.capture_live_title), Modifier.padding(horizontal = Spacing.l))
    }
}
