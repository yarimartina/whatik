package com.whatik.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview

/**
 * Tema dell'app: palette fissa (niente colore dinamico, audit UX 3.2.1), tipografia, forme e
 * colori estesi ([WhatikTheme.extended]). Fornisce anche se le animazioni di sistema sono attive
 * (letto da `rememberAnimationsEnabled()` in `ui/components/StickerImage.kt`).
 */
@Composable
fun WhatikTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme
    val extended = if (darkTheme) DarkExtendedColors else LightExtendedColors
    val animationsEnabled = observeSystemAnimationsEnabled()
    CompositionLocalProvider(
        LocalExtendedColors provides extended,
        LocalAnimationsEnabled provides animationsEnabled,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = WhatikTypography,
            shapes = WhatikShapes,
            content = content,
        )
    }
}

/** Accesso ai valori del tema che non stanno in `MaterialTheme`. */
object WhatikTheme {
    /** Colori estesi: brand, success, successContainer… (vedi [ExtendedColors]). */
    val extended: ExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExtendedColors.current
}

/**
 * true se le animazioni di sistema sono attive (`Settings.Global.ANIMATOR_DURATION_SCALE` > 0).
 * `null` fuori da [WhatikTheme]: in quel caso si legge l'impostazione direttamente.
 */
internal val LocalAnimationsEnabled = staticCompositionLocalOf<Boolean?> { null }

/** Legge `ANIMATOR_DURATION_SCALE`: 0 = l'utente ha tolto le animazioni (Opzioni sviluppatore, Accessibilità). */
internal fun systemAnimationsEnabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

/** Valore di [systemAnimationsEnabled] aggiornato se l'utente cambia l'impostazione con l'app aperta. */
@Composable
private fun observeSystemAnimationsEnabled(): Boolean {
    if (LocalInspectionMode.current) return true
    val context = LocalContext.current
    var enabled by remember(context) { mutableStateOf(systemAnimationsEnabled(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = systemAnimationsEnabled(context)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/**
 * Anteprime standard dei componenti: tema chiaro, tema scuro, scala del carattere 2,0.
 * Il contenuto va avvolto in [WhatikPreviewSurface].
 */
@Preview(name = "Chiaro", showBackground = true, backgroundColor = 0xFFFFF8F7)
@Preview(
    name = "Scuro",
    showBackground = true,
    backgroundColor = 0xFF1A1112,
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
)
@Preview(name = "Carattere 2,0", showBackground = true, backgroundColor = 0xFFFFF8F7, fontScale = 2f)
annotation class WhatikPreviews

/** Tema dell'app + superficie di fondo, per le anteprime. */
@Composable
fun WhatikPreviewSurface(content: @Composable () -> Unit) {
    WhatikTheme {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
