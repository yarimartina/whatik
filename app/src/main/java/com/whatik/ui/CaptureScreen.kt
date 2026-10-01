package com.whatik.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whatik.R

data class CaptureUiState(
    val overlayGranted: Boolean = false,
    val notificationsGranted: Boolean = true,
    val notificationsRequired: Boolean = false,
    val running: Boolean = false,
    val message: String? = null,
)

/** Schermata di avvio della cattura automatica con bolla sopra TikTok. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    state: CaptureUiState,
    snackbarHost: @Composable () -> Unit,
    onBack: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantNotifications: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenTikTok: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_capture)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(stringResource(R.string.capture_intro), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            PermissionRow(
                label = stringResource(R.string.capture_step_overlay),
                granted = state.overlayGranted,
                onGrant = onGrantOverlay,
            )
            if (state.notificationsRequired) {
                PermissionRow(
                    label = stringResource(R.string.capture_step_notifications),
                    granted = state.notificationsGranted,
                    onGrant = onGrantNotifications,
                )
            }
            Spacer(Modifier.height(16.dp))
            if (state.running) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.capture_running), fontWeight = FontWeight.SemiBold)
                        state.message?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenTikTok, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.capture_open_tiktok)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.capture_stop)) }
            } else {
                Button(onClick = onStart, enabled = state.overlayGranted, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.capture_start))
                }
                state.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.capture_tips), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Icon(
            if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (granted) {
            Text(stringResource(R.string.capture_granted), style = MaterialTheme.typography.labelMedium)
        } else {
            TextButton(onClick = onGrant) { Text(stringResource(R.string.capture_grant)) }
        }
    }
}
