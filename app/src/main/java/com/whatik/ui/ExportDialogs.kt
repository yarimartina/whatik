package com.whatik.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.whatik.R
import com.whatik.data.PackPlanner
import com.whatik.data.StickerExporter
import com.whatik.data.StickerPack
import com.whatik.whatsapp.WhatsAppBridge

@Composable
fun ExportDialog(
    config: ExportState.Configuring,
    plan: PackPlanner.Plan,
    packs: List<StickerPack>,
    onChange: ((ExportState.Configuring) -> ExportState.Configuring) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val staticCount = plan.staticCount
    val animatedCount = plan.animatedCount
    val hasAnimatedSource = config.items.any { it.animated }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = config.baseName,
                    onValueChange = { v -> onChange { it.copy(baseName = v.take(128)) } },
                    label = { Text(stringResource(R.string.export_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = config.publisher,
                    onValueChange = { v -> onChange { it.copy(publisher = v.take(128)) } },
                    label = { Text(stringResource(R.string.export_publisher)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasAnimatedSource) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = config.convertAnimatedToStatic,
                            onCheckedChange = { v -> onChange { it.copy(convertAnimatedToStatic = v) } },
                        )
                        Text(stringResource(R.string.export_convert_animated), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (staticCount > 0) {
                    TargetSelector(
                        label = stringResource(R.string.export_group_static, staticCount),
                        animated = false,
                        packs = packs,
                        selectedId = config.staticTargetId,
                        onSelect = { id -> onChange { it.copy(staticTargetId = id) } },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (animatedCount > 0) {
                    TargetSelector(
                        label = stringResource(R.string.export_group_animated, animatedCount),
                        animated = true,
                        packs = packs,
                        selectedId = config.animatedTargetId,
                        onSelect = { id -> onChange { it.copy(animatedTargetId = id) } },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                val newCount = plan.newPackCount
                val existingCount = plan.packs.size - newCount
                if (newCount > 0) {
                    Text(pluralStringResource(R.plurals.export_summary_new, newCount, newCount), style = MaterialTheme.typography.bodySmall)
                }
                if (existingCount > 0) {
                    Text(pluralStringResource(R.plurals.export_summary_existing, existingCount, existingCount), style = MaterialTheme.typography.bodySmall)
                }
                val tooSmall = plan.tooSmall
                if (tooSmall.isNotEmpty()) {
                    val names = tooSmall.joinToString(", ") { p ->
                        p.existingIdentifier?.let { id -> packs.firstOrNull { it.identifier == id }?.name }
                            ?: PackPlanner.packName(config.baseName, plan, p)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.export_warning_small, names),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!WhatsAppBridge.isAnyWhatsAppInstalled(context)) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.export_no_whatsapp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = plan.packs.isNotEmpty()) { Text(stringResource(R.string.action_convert)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun TargetSelector(
    label: String,
    animated: Boolean,
    packs: List<StickerPack>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val compatible = packs.filter { it.animated == animated && it.stickers.size < PackPlanner.MAX_STICKERS }
    val current = compatible.firstOrNull { it.identifier == selectedId }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (current == null) stringResource(R.string.export_target_new)
                    else stringResource(R.string.export_target_existing, current.name, current.stickers.size),
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.export_target_new)) },
                    onClick = { expanded = false; onSelect(null) },
                )
                compatible.forEach { pack ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_target_existing, pack.name, pack.stickers.size)) },
                        onClick = { expanded = false; onSelect(pack.identifier) },
                    )
                }
            }
        }
    }
}

@Composable
fun ProgressDialog(state: ExportState.Running, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.progress_title)) },
        text = {
            Column {
                val fraction = if (state.total > 0) state.done.toFloat() / state.total else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.progress_body, state.done, state.total, state.currentName))
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun ResultDialog(
    result: StickerExporter.Result,
    addedToWhatsApp: Map<String, Boolean>,
    onAddToWhatsApp: (StickerPack) -> Unit,
    onOpenPacks: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.result_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                result.packs.forEach { pack ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(pack.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(
                                    if (pack.animated) R.string.pack_count_animated else R.string.pack_count_static,
                                    pack.stickers.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        when {
                            !pack.isAddable -> Text(stringResource(R.string.result_pack_too_small), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            addedToWhatsApp[pack.identifier] == true -> TextButton(onClick = { onAddToWhatsApp(pack) }) { Text(stringResource(R.string.action_reopen_whatsapp)) }
                            else -> TextButton(onClick = { onAddToWhatsApp(pack) }) { Text(stringResource(R.string.action_add_to_whatsapp)) }
                        }
                    }
                }
                if (result.failures.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.result_failures), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    result.failures.forEach { failure ->
                        Text("• ${failure.itemName}: ${failure.reason}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onOpenPacks) { Text(stringResource(R.string.action_packs)) }
        },
    )
}
