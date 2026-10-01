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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
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
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (state.isVideo) {
                ProposalsSection(state, onApplyProposal, onCreateAll, onDetectAgain)
            }
            CropPreview(state, onCropChange)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (state.isVideo) R.string.editor_hint_video else R.string.editor_hint_image),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.editor_crop_size), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = state.crop.size,
                onValueChange = { onCropChange(state.crop.copy(size = it)) },
                valueRange = CropSpec.MIN_SIZE..1f,
                enabled = !state.converting,
            )
            if (state.isVideo) {
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

@Composable
private fun CropPreview(state: EditorState, onCropChange: (CropSpec) -> Unit) {
    val aspect = state.source.width.toFloat() / state.source.height.coerceAtLeast(1)
    val preview = state.preview
    val crop = state.crop
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
            .background(Color.Black)
            .pointerInput(state.converting) {
                if (state.converting) return@pointerInput
                detectTransformGestures { _, pan, zoom, _ ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    onCropChange(
                        CropSpec(
                            cx = crop.cx + pan.x / w,
                            cy = crop.cy + pan.y / h,
                            size = crop.size / zoom,
                        ),
                    )
                }
            }
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                val n = crop.normalized()
                val side = n.size * minOf(w, h)
                val left = (n.cx * w - side / 2f).coerceIn(0f, w - side)
                val top = (n.cy * h - side / 2f).coerceIn(0f, h - side)
                val dim = Color.Black.copy(alpha = 0.55f)
                drawRect(dim, Offset(0f, 0f), Size(w, top))
                drawRect(dim, Offset(0f, top + side), Size(w, h - top - side))
                drawRect(dim, Offset(0f, top), Size(left, side))
                drawRect(dim, Offset(left + side, top), Size(w - left - side, side))
                drawRect(Color.White, Offset(left, top), Size(side, side), style = Stroke(width = 3.dp.toPx()))
                val handle = 18.dp.toPx()
                val stroke = Stroke(width = 5.dp.toPx())
                for ((hx, hy) in listOf(left to top, left + side to top, left to top + side, left + side to top + side)) {
                    val dx = if (hx == left) handle else -handle
                    val dy = if (hy == top) handle else -handle
                    drawLine(Color.White, Offset(hx, hy), Offset(hx + dx, hy), stroke.width)
                    drawLine(Color.White, Offset(hx, hy), Offset(hx, hy + dy), stroke.width)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}
