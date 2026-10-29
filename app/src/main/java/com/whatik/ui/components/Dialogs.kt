package com.whatik.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import com.whatik.R
import com.whatik.ui.theme.Spacing
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews

/**
 * Fondo dei dialoghi dell'app: `surfaceContainerLow`, lo stesso dei bottom sheet. Il predefinito di
 * `AlertDialog` (`surfaceContainerHigh`) lascia ai pulsanti di testo `primary` solo 4,35:1 nel tema
 * chiaro (sotto il 4,5:1 richiesto per il testo); su `surfaceContainerLow` sono 4,83:1 (6,32:1 nello
 * scuro). Da passare come `containerColor` anche agli altri `AlertDialog` delle schermate.
 */
val DialogContainerColor: Color
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceContainerLow

/**
 * Conferma di un'azione (audit UX 3.2.7): `AlertDialog` su [DialogContainerColor] con [title], [body], pulsante di conferma
 * ([confirmLabel], in `error` se [destructive]) e «Annulla». [onConfirm] non chiude il dialogo da solo:
 * chi lo mostra lo nasconde (di solito `onConfirm = { show = false; azione() }`).
 * [onDismiss] = «Annulla», Indietro o tocco fuori.
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogContainerColor,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Dialogo di attesa che blocca l'interfaccia (audit UX 3.2.7): titolo, testo facoltativo e
 * `LinearProgressIndicator` determinato se [progress] (0..1) non è null, altrimenti indeterminato.
 * «Annulla» compare solo se c'è [onCancel]. Il tocco fuori non chiude; Indietro chiama
 * [onDismissRequest] (predefinito: niente, il dialogo resta finché chi lo mostra non lo toglie).
 */
@Composable
fun BlockingProgressDialog(
    title: String,
    body: String? = null,
    progress: Float? = null,
    onCancel: (() -> Unit)? = null,
    onDismissRequest: () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false),
        containerColor = DialogContainerColor,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                if (body != null) Text(body)
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@WhatikPreviews
@Composable
private fun ConfirmDialogPreview() {
    WhatikPreviewSurface {
        ConfirmDialog(
            title = pluralStringResource(R.plurals.delete_confirm_title, 3, 3),
            body = stringResource(R.string.delete_confirm_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {},
            onDismiss = {},
            destructive = true,
        )
    }
}

@WhatikPreviews
@Composable
private fun BlockingProgressDialogPreview() {
    WhatikPreviewSurface {
        BlockingProgressDialog(
            title = stringResource(R.string.progress_title),
            body = stringResource(R.string.progress_body, 3, 8, "Reazioni"),
            progress = 3f / 8f,
            onCancel = {},
        )
    }
}
