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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onCropChange(CropSpec.FULL) }, enabled = !state.converting) {
                        Text(stringResource(R.string.editor_full_image))
                    }
                    TextButton(
                        onClick = {
                            val c = state.crop.normalized()
                            val w = state.source.width.coerceAtLeast(1)
                            val h = state.source.height.coerceAtLeast(1)
                            val sidePx = maxOf(c.width * w, c.height * h)
                            onCropChange(CropSpec.square(c.cx, c.cy, sidePx / minOf(w, h), w, h))
                        },
                        enabled = !state.converting,
                    ) { Text(stringResource(R.string.editor_make_square)) }
                }
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

/** Zona toccata del riquadro: interno (sposta), bordi e angoli (ridimensiona). */
private enum class Zone { MOVE, LEFT, RIGHT, TOP, BOTTOM, TL, TR, BL, BR }

/**
 * Ritaglio come in un editor di foto: l'immagine è adattata all'area, il riquadro si
 * ridimensiona trascinando bordi e angoli e si sposta trascinandone l'interno.
 * Due dita ingrandiscono la vista, un dito fuori dal riquadro la sposta quando è ingrandita.
 */
@Composable
private fun CropPreview(state: EditorState, onCropChange: (CropSpec) -> Unit, modifier: Modifier = Modifier) {
    val imgW = state.source.width.coerceAtLeast(1)
    val imgH = state.source.height.coerceAtLeast(1)
    val crop = state.crop.normalized()
    val latestCrop = rememberUpdatedState(crop)
    val preview = state.preview
    val imageBitmap = remember(preview) { preview?.asImageBitmap() }
    var viewZoom by remember(state.source) { mutableFloatStateOf(1f) }
    var viewPan by remember(state.source) { mutableStateOf(Offset.Zero) }
    val latestZoom = rememberUpdatedState(viewZoom)
    val latestPan = rememberUpdatedState(viewPan)
    var activeZone by remember { mutableStateOf<Zone?>(null) }
    val touchTolerance = with(LocalDensity.current) { 22.dp.toPx() }

    Box(
        modifier
            .fillMaxWidth()
            .height(380.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .pointerInput(state.converting, imgW, imgH) {
                if (state.converting) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val bw = size.width.toFloat()
                    val bh = size.height.toFloat()
                    val startRect = imageRect(bw, bh, imgW, imgH, latestZoom.value, latestPan.value)
                    val zone = hitZone(down.position, cropToView(latestCrop.value, startRect), touchTolerance)
                    activeZone = zone
                    var pinching = false
                    var lastDistance = 0f
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val centroid = (pressed[0].position + pressed[1].position) / 2f
                            val distance = (pressed[0].position - pressed[1].position).getDistance()
                            if (!pinching) {
                                pinching = true
                                activeZone = null
                            } else if (lastDistance > 0f) {
                                val oldZoom = latestZoom.value
                                val newZoom = (oldZoom * distance / lastDistance).coerceIn(1f, 8f)
                                val rect = imageRect(bw, bh, imgW, imgH, oldZoom, latestPan.value)
                                // il punto dell'immagine sotto le dita resta fermo
                                val fx = (centroid.x - rect.left) / rect.width
                                val fy = (centroid.y - rect.top) / rect.height
                                val s0 = minOf(bw / imgW, bh / imgH)
                                val newW = imgW * s0 * newZoom
                                val newH = imgH * s0 * newZoom
                                val newLeft = centroid.x - fx * newW
                                val newTop = centroid.y - fy * newH
                                viewZoom = newZoom
                                viewPan = clampPan(Offset(newLeft - (bw - newW) / 2f, newTop - (bh - newH) / 2f), bw, bh, newW, newH)
                            }
                            lastDistance = distance
                        } else if (pressed.size == 1 && !pinching) {
                            val change = pressed[0]
                            val delta = change.position - change.previousPosition
                            val rect = imageRect(bw, bh, imgW, imgH, latestZoom.value, latestPan.value)
                            if (zone == null) {
                                if (latestZoom.value > 1f) {
                                    viewPan = clampPan(latestPan.value + delta, bw, bh, rect.width, rect.height)
                                }
                            } else {
                                val dx = delta.x / rect.width
                                val dy = delta.y / rect.height
                                onCropChange(adjustCrop(latestCrop.value, zone, dx, dy))
                            }
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    activeZone = null
                }
            }
            .drawWithContent {
                val bw = size.width
                val bh = size.height
                val rect = imageRect(bw, bh, imgW, imgH, viewZoom, viewPan)
                if (imageBitmap != null) {
                    drawImage(
                        image = imageBitmap,
                        dstOffset = IntOffset(rect.left.roundToInt(), rect.top.roundToInt()),
                        dstSize = IntSize(rect.width.roundToInt().coerceAtLeast(1), rect.height.roundToInt().coerceAtLeast(1)),
                    )
                }
                drawContent()
                val c = cropToView(crop, rect)
                val dim = Color.Black.copy(alpha = 0.55f)
                drawRect(dim, Offset(0f, 0f), Size(bw, c.top.coerceAtLeast(0f)))
                drawRect(dim, Offset(0f, c.bottom), Size(bw, (bh - c.bottom).coerceAtLeast(0f)))
                drawRect(dim, Offset(0f, c.top), Size(c.left.coerceAtLeast(0f), c.height))
                drawRect(dim, Offset(c.right, c.top), Size((bw - c.right).coerceAtLeast(0f), c.height))
                drawRect(Color.White, Offset(c.left, c.top), Size(c.width, c.height), style = Stroke(width = 2.dp.toPx()))
                if (activeZone != null) {
                    val grid = Color.White.copy(alpha = 0.5f)
                    for (i in 1..2) {
                        val x = c.left + c.width * i / 3f
                        val y = c.top + c.height * i / 3f
                        drawLine(grid, Offset(x, c.top), Offset(x, c.bottom), 1.dp.toPx())
                        drawLine(grid, Offset(c.left, y), Offset(c.right, y), 1.dp.toPx())
                    }
                }
                // maniglie: angoli a "L" e barrette al centro dei bordi
                val handle = minOf(20.dp.toPx(), c.width / 3f, c.height / 3f).coerceAtLeast(4f)
                val thick = 5.dp.toPx()
                for ((hx, hy) in listOf(c.left to c.top, c.right to c.top, c.left to c.bottom, c.right to c.bottom)) {
                    val dx = if (hx == c.left) handle else -handle
                    val dy = if (hy == c.top) handle else -handle
                    drawLine(Color.White, Offset(hx, hy), Offset(hx + dx, hy), thick)
                    drawLine(Color.White, Offset(hx, hy), Offset(hx, hy + dy), thick)
                }
                val bar = minOf(24.dp.toPx(), c.width / 3f, c.height / 3f).coerceAtLeast(4f)
                val mx = (c.left + c.right) / 2f
                val my = (c.top + c.bottom) / 2f
                drawLine(Color.White, Offset(mx - bar / 2, c.top), Offset(mx + bar / 2, c.top), thick)
                drawLine(Color.White, Offset(mx - bar / 2, c.bottom), Offset(mx + bar / 2, c.bottom), thick)
                drawLine(Color.White, Offset(c.left, my - bar / 2), Offset(c.left, my + bar / 2), thick)
                drawLine(Color.White, Offset(c.right, my - bar / 2), Offset(c.right, my + bar / 2), thick)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (preview == null) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/** Rettangolo (in pixel dell'area) occupato dall'immagine adattata, con zoom e spostamento della vista. */
private fun imageRect(bw: Float, bh: Float, imgW: Int, imgH: Int, zoom: Float, pan: Offset): Rect {
    val s = minOf(bw / imgW, bh / imgH) * zoom
    val w = imgW * s
    val h = imgH * s
    val left = (bw - w) / 2f + pan.x
    val top = (bh - h) / 2f + pan.y
    return Rect(left, top, left + w, top + h)
}

/** Lo spostamento della vista non deve lasciare vuoti ai lati quando l'immagine è più grande dell'area. */
private fun clampPan(pan: Offset, bw: Float, bh: Float, imageW: Float, imageH: Float): Offset {
    val maxX = ((imageW - bw) / 2f).coerceAtLeast(0f)
    val maxY = ((imageH - bh) / 2f).coerceAtLeast(0f)
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

private fun cropToView(crop: CropSpec, rect: Rect): Rect = Rect(
    rect.left + crop.left * rect.width,
    rect.top + crop.top * rect.height,
    rect.left + crop.right * rect.width,
    rect.top + crop.bottom * rect.height,
)

private fun hitZone(p: Offset, c: Rect, tol: Float): Zone? {
    fun near(a: Float, b: Float) = kotlin.math.abs(a - b) <= tol
    val insideX = p.x >= c.left - tol && p.x <= c.right + tol
    val insideY = p.y >= c.top - tol && p.y <= c.bottom + tol
    if (!insideX || !insideY) return null
    val l = near(p.x, c.left)
    val r = near(p.x, c.right)
    val t = near(p.y, c.top)
    val b = near(p.y, c.bottom)
    return when {
        l && t -> Zone.TL
        r && t -> Zone.TR
        l && b -> Zone.BL
        r && b -> Zone.BR
        l -> Zone.LEFT
        r -> Zone.RIGHT
        t -> Zone.TOP
        b -> Zone.BOTTOM
        p.x > c.left && p.x < c.right && p.y > c.top && p.y < c.bottom -> Zone.MOVE
        else -> null
    }
}

/** Applica un trascinamento (in frazioni dell'immagine) alla zona indicata, tenendo il riquadro valido. */
private fun adjustCrop(crop: CropSpec, zone: Zone, dx: Float, dy: Float): CropSpec {
    val min = CropSpec.MIN_SIZE
    var l = crop.left
    var t = crop.top
    var r = crop.right
    var b = crop.bottom
    when (zone) {
        Zone.MOVE -> {
            val w = r - l
            val h = b - t
            l = (l + dx).coerceIn(0f, 1f - w)
            t = (t + dy).coerceIn(0f, 1f - h)
            r = l + w
            b = t + h
        }
        Zone.LEFT -> l = (l + dx).coerceIn(0f, r - min)
        Zone.RIGHT -> r = (r + dx).coerceIn(l + min, 1f)
        Zone.TOP -> t = (t + dy).coerceIn(0f, b - min)
        Zone.BOTTOM -> b = (b + dy).coerceIn(t + min, 1f)
        Zone.TL -> { l = (l + dx).coerceIn(0f, r - min); t = (t + dy).coerceIn(0f, b - min) }
        Zone.TR -> { r = (r + dx).coerceIn(l + min, 1f); t = (t + dy).coerceIn(0f, b - min) }
        Zone.BL -> { l = (l + dx).coerceIn(0f, r - min); b = (b + dy).coerceIn(t + min, 1f) }
        Zone.BR -> { r = (r + dx).coerceIn(l + min, 1f); b = (b + dy).coerceIn(t + min, 1f) }
    }
    return CropSpec(l, t, r, b)
}
