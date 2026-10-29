package com.whatik.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.BitmapFactoryDecoder
import coil.request.ImageRequest
import com.whatik.ui.theme.LocalAnimationsEnabled
import com.whatik.ui.theme.Spacing
import com.whatik.ui.theme.WhatikPreviewSurface
import com.whatik.ui.theme.WhatikPreviews
import com.whatik.ui.theme.systemAnimationsEnabled

/**
 * true se le animazioni di sistema sono attive (`Settings.Global.ANIMATOR_DURATION_SCALE` > 0).
 * Dentro `WhatikTheme` il valore è letto una volta per tutta l'app e si aggiorna se l'utente cambia
 * l'impostazione; fuori dal tema si legge l'impostazione direttamente.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    LocalAnimationsEnabled.current?.let { return it }
    val context = LocalContext.current
    return remember(context) { systemAnimationsEnabled(context) }
}

/** Parametro di Coil che distingue in memoria il primo fotogramma dalla versione animata dello stesso file. */
private const val FIRST_FRAME_PARAMETER = "whatik.firstFrame"

/**
 * Anteprima di uno sticker: unico punto dell'app che carica le immagini degli sticker (audit UX 3.2.6).
 *
 * - [model]: tutto ciò che Coil accetta (File, Uri, String con percorso, URI o URL).
 * - [animate]: true = GIF e WebP animati si muovono (da Android 9; su 8–8.1 i WebP animati restano
 *   fermi); false = solo il primo fotogramma, decodificato con BitmapFactory (niente
 *   `AnimatedImageDrawable`, entra nella cache in memoria e costa quasi zero a riposo, audit prestazioni P6).
 *   Il valore predefinito segue le animazioni di sistema: con le animazioni tolte non si anima nulla.
 *   Usare sempre `animate = false` per le immagini piccole e numerose (striscia dei pack, icona del
 *   pack, anteprime della sessione nella Cattura).
 * - Caricamento: segnaposto `surfaceContainerHighest`; errore: icona `BrokenImage` 24 dp `onSurfaceVariant`.
 *
 * Prestazioni (audit P12): con lo strong skipping un `model` di tipo instabile si confronta per identità.
 * Passare un oggetto che sopravvive alla ricomposizione (`remember(item.fileName) { fileOf(item) }`,
 * l'Uri o l'URL già contenuti nel modello dati), non un `File(...)` creato a ogni ricomposizione. In ogni
 * caso la richiesta Coil è ricreata solo se [model] cambia per `equals`.
 */
@Composable
fun StickerImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    animate: Boolean = rememberAnimationsEnabled(),
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    val request = remember(model, animate, context) {
        ImageRequest.Builder(context)
            .data(model)
            .apply {
                if (!animate) {
                    decoderFactory(BitmapFactoryDecoder.Factory())
                    // senza questo parametro la cache in memoria restituirebbe il fotogramma fermo anche
                    // a chi chiede la versione animata dello stesso file (la chiave non include il decoder)
                    setParameter(FIRST_FRAME_PARAMETER, true)
                }
            }
            .build()
    }
    val colors = MaterialTheme.colorScheme
    val placeholder = remember(colors.surfaceContainerHighest) { ColorPainter(colors.surfaceContainerHighest) }
    val brokenVector = rememberVectorPainter(Icons.Outlined.BrokenImage)
    val iconSizePx = with(LocalDensity.current) { 24.dp.toPx() }
    val error = remember(brokenVector, colors.onSurfaceVariant, iconSizePx) {
        CenteredIconPainter(brokenVector, iconSizePx, colors.onSurfaceVariant)
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        placeholder = placeholder,
        error = error,
        fallback = error,
        contentScale = contentScale,
    )
}

/**
 * Disegna un'icona di misura fissa al centro dello spazio disponibile, qualunque sia il [ContentScale]
 * dell'immagine (dimensione intrinseca non specificata = Coil la disegna a tutta area).
 */
private class CenteredIconPainter(
    private val icon: Painter,
    private val iconSizePx: Float,
    tint: Color,
) : Painter() {
    private val filter = ColorFilter.tint(tint)

    override val intrinsicSize: Size = Size.Unspecified

    override fun DrawScope.onDraw() {
        val side = minOf(iconSizePx, size.minDimension)
        translate(left = (size.width - side) / 2f, top = (size.height - side) / 2f) {
            with(icon) { draw(Size(side, side), colorFilter = filter) }
        }
    }
}

@WhatikPreviews
@Composable
private fun StickerImagePreview() {
    WhatikPreviewSurface {
        Row(Modifier.padding(Spacing.l)) {
            // in anteprima Coil mostra solo il segnaposto; senza modello compare l'icona di errore
            Box(Modifier.size(72.dp).clip(MaterialTheme.shapes.small)) {
                StickerImage(model = "file:///sticker.webp", contentDescription = null, modifier = Modifier.size(72.dp), animate = false)
            }
            Box(Modifier.padding(start = Spacing.s).size(72.dp).clip(MaterialTheme.shapes.small)) {
                StickerImage(model = null, contentDescription = null, modifier = Modifier.size(72.dp))
            }
        }
    }
}
