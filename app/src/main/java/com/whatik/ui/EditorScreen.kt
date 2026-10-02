package com.whatik.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.whatik.R
import com.whatik.image.CropSpec

/**
 * Editor per ricavare uno sticker da una registrazione dello schermo (ritaglio + intervallo)
 * o per ritagliare un'immagine già in libreria.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorState,
    snackbarHost: @Composable () -> Unit,
    onBack: () -> Unit,
    onCropChange: (CropSpec) -> Unit,
    onRangeChange: (Long, Long) -> Unit,
    onNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onApplyProposal: (Int) -> Unit,
    onCreateAll: () -> Unit,
    onDetectAgain: () -> Unit,
    onKeepOriginalChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_editor)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.converting) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = snackbarHost,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // L'anteprima sta fuori dall'area scorrevole: i trascinamenti servono al ritaglio, non allo scroll.
            CropPreview(state, onCropChange, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                if (state.isVideo) {
                    ProposalsSection(state, onApplyProposal, onCreateAll, onDetectAgain)
                }
                Text(
                    stringResource(
                        when {
                            state.isVideo -> R.string.editor_hint_video
                            state.hasTimeline -> R.string.editor_hint_animated
                            else -> R.string.editor_hint_image
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.editor_crop_size), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = 1f - state.crop.size,
                    onValueChange = { onCropChange(state.crop.copy(size = 1f - it)) },
                    valueRange = 0f..(1f - CropSpec.MIN_SIZE),
                    enabled = !state.converting,
                )
                if (state.hasTimeline) {
                    val durationSec = state.durationMs / 1000f
                    Text(stringResource(R.string.editor_range), style = MaterialTheme.typography.labelLarge)
                    RangeSlider(
                        value = (state.startMs / 1000f)..(state.endMs / 1000f),
                        onValueChange = { range -> onRangeChange((range.start * 1000).toLong(), (range.endInclusive * 1000).toLong()) },
                        valueRange = 0f..durationSec.coerceAtLeast(0.2f),
                        enabled = !state.converting,
                    )
                    Text(
                        stringResource(R.string.editor_duration, (state.endMs - state.startMs) / 1000f, state.frameCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.editor_name)) },
                    singleLine = true,
                    enabled = !state.converting,
                    modifier = Modifier.fillMaxWidth(),
                )
                if ((state.source as? EditorSource.Image)?.replaceItemId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.keepOriginal,
                            onCheckedChange = onKeepOriginalChange,
                            enabled = !state.converting,
                        )
                        Text(stringResource(R.string.editor_keep_original), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (state.converting) {
                    val progress = state.progress
                    if (progress != null && progress.second > 0) {
                        LinearProgressIndicator(progress = { progress.first.toFloat() / progress.second }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.editor_progress, progress.first, progress.second), style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.editor_converting), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth(), enabled = state.preview != null) {
                        Text(stringResource(R.string.editor_create))
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ProposalsSection(
    state: EditorState,
    onApplyProposal: (Int) -> Unit,
    onCreateAll: () -> Unit,
    onDetectAgain: () -> Unit,
) {
    val proposals = state.proposals
    when {
        state.detecting -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.editor_detecting), style = MaterialTheme.typography.bodySmall)
            }
        }
        proposals == null -> Unit
        proposals.isEmpty() -> {
            Text(stringResource(R.string.editor_no_proposals), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDetectAgain, enabled = !state.converting) { Text(stringResource(R.string.editor_detect_again)) }
            Spacer(Modifier.height(8.dp))
        }
        else -> {
            Text(
                pluralStringResource(R.plurals.editor_proposals_title, proposals.size, proposals.size),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(proposals) { index, proposal ->
                    val selected = state.selectedProposal == index
                    val shape = RoundedCornerShape(12.dp)
                    Image(
                        bitmap = proposal.preview.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                            .clickable(enabled = !state.converting) { onApplyProposal(index) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val bulk = state.bulkProgress
            if (state.converting && bulk != null) {
                LinearProgressIndicator(progress = { bulk.first.toFloat() / bulk.second.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.editor_bulk_progress, bulk.first + 1, bulk.second), style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = onCreateAll, modifier = Modifier.fillMaxWidth(), enabled = !state.converting) {
                    Text(stringResource(R.string.editor_create_all, proposals.size))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.editor_manual_title), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Riquadro di ritaglio fisso al centro; sotto scorre l'immagine: un dito la sposta, due dita
 * la ingrandiscono (come nei ritaglia-foto). Il riquadro vale sempre un quadrato dell'immagine.
 */
@Composable
private fun CropPreview(state: EditorState, onCropChange: (CropSpec) -> Unit, modifier: Modifier = Modifier) {
    val imgW = state.source.width.coerceAtLeast(1)
    val imgH = state.source.height.coerceAtLeast(1)
    val crop = state.crop.effective(imgW, imgH)
    val latestCrop = rememberUpdatedState(crop)
    val preview = state.preview
    val imageBitmap = remember(preview) { preview?.asImageBitmap() }
    Box(
        modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .pointerInput(state.converting, imgW, imgH) {
                if (state.converting) return@pointerInput
                detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                    val current = latestCrop.value
                    val frame = frameSide(size.width.toFloat(), size.height.toFloat())
                    val cropSidePx = current.size * minOf(imgW, imgH)
                    val k = frame / cropSidePx // pixel di vista per pixel di immagine
                    onCropChange(
                        CropSpec(
                            cx = current.cx - pan.x / (k * imgW),
                            cy = current.cy - pan.y / (k * imgH),
                            size = current.size / zoom,
                        ).effective(imgW, imgH),
                    )
                }
            }
            .drawWithContent {
                val bw = size.width
                val bh = size.height
                val frame = frameSide(bw, bh)
                val cropSidePx = crop.size * minOf(imgW, imgH)
                val k = frame / cropSidePx
                val originX = bw / 2f - crop.cx * imgW * k
                val originY = bh / 2f - crop.cy * imgH * k
                if (imageBitmap != null) {
                    drawImage(
                        image = imageBitmap,
                        dstOffset = IntOffset(originX.roundToInt(), originY.roundToInt()),
                        dstSize = IntSize((imgW * k).roundToInt().coerceAtLeast(1), (imgH * k).roundToInt().coerceAtLeast(1)),
                    )
                }
                drawContent()
                val left = (bw - frame) / 2f
                val top = (bh - frame) / 2f
                val dim = Color.Black.copy(alpha = 0.55f)
                drawRect(dim, Offset(0f, 0f), Size(bw, top))
                drawRect(dim, Offset(0f, top + frame), Size(bw, bh - top - frame))
                drawRect(dim, Offset(0f, top), Size(left, frame))
                drawRect(dim, Offset(left + frame, top), Size(bw - left - frame, frame))
                drawRect(Color.White, Offset(left, top), Size(frame, frame), style = Stroke(width = 3.dp.toPx()))
                val handle = 18.dp.toPx()
                val strokeWidth = 5.dp.toPx()
                for ((hx, hy) in listOf(left to top, left + frame to top, left to top + frame, left + frame to top + frame)) {
                    val dx = if (hx == left) handle else -handle
                    val dy = if (hy == top) handle else -handle
                    drawLine(Color.White, Offset(hx, hy), Offset(hx + dx, hy), strokeWidth)
                    drawLine(Color.White, Offset(hx, hy), Offset(hx, hy + dy), strokeWidth)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (preview == null) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/** Lato del riquadro fisso: il 72% del lato minore dell'area di anteprima. */
private fun frameSide(boxWidth: Float, boxHeight: Float): Float = 0.72f * minOf(boxWidth, boxHeight)
