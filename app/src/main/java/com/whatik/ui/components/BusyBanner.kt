package com.whatik.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.whatik.R
import com.whatik.ui.theme.Spacing
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews

/**
 * Banner di lavoro in corso sotto la barra superiore (audit UX 3.2.7): `LinearProgressIndicator` a tutta
 * larghezza (determinato se [progress] non è null, valore 0..1) e testo `bodyMedium`. Il testo è una
 * live region «educata»: TalkBack lo annuncia quando compare o cambia, senza interrompere.
 * La schermata sotto resta usabile.
 */
@Composable
fun BusyBanner(text: String, progress: Float? = null, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        if (progress != null) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = Spacing.l, vertical = Spacing.s)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@WhatikPreviews
@Composable
private fun BusyBannerPreview() {
    WhatikPreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
            BusyBanner(stringResource(R.string.library_busy))
            BusyBanner(stringResource(R.string.pack_progress, 3, 8), progress = 3f / 8f)
        }
    }
}
