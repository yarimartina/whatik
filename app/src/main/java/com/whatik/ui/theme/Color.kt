package com.whatik.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * Palette di Whatik (audit UX, 3.2.1). Colore dinamico disattivato: gli overlay sopra TikTok hanno
 * colori fissi e l'app deve parlare la stessa lingua. Ogni ruolo M3 e' esplicito, chiaro e scuro:
 * nessuno cade sulla baseline viola di Material 3.
 *
 * Il rosa del marchio #FE2C55 non porta mai testo (3,68:1 sul bianco): per i ruoli `primary` si usa
 * #D6003F (chiaro, 5,34:1 con il bianco) e #FF6B8A (scuro).
 */

/** Rosa TikTok: solo grafica (bolla, icone del marchio, illustrazioni degli stati vuoti), mai testo. */
val BrandPink = Color(0xFFFE2C55)

val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFD6003F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9DD),
    onPrimaryContainer = Color(0xFF3F0012),
    inversePrimary = Color(0xFFFFB2BB),
    secondary = Color(0xFF75565A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFCDDE0),
    onSecondaryContainer = Color(0xFF2C1518),
    tertiary = Color(0xFF00696B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF0F1),
    onTertiaryContainer = Color(0xFF002020),
    background = Color(0xFFFFF8F7),
    onBackground = Color(0xFF22191A),
    surface = Color(0xFFFFF8F7),
    onSurface = Color(0xFF22191A),
    surfaceVariant = Color(0xFFF3DDDF),
    onSurfaceVariant = Color(0xFF524345),
    surfaceTint = Color(0xFFD6003F),
    inverseSurface = Color(0xFF382E2F),
    inverseOnSurface = Color(0xFFFEEDEE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF857374),
    outlineVariant = Color(0xFFD7C1C3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F7),
    surfaceContainer = Color(0xFFFBEAEB),
    surfaceContainerHigh = Color(0xFFF5E4E5),
    surfaceContainerHighest = Color(0xFFEFDEDF),
    surfaceContainerLow = Color(0xFFFFF0F1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE7D6D7),
)

val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFFF6B8A),
    onPrimary = Color(0xFF3B0012),
    primaryContainer = Color(0xFF8E0030),
    onPrimaryContainer = Color(0xFFFFD9DD),
    inversePrimary = Color(0xFFB8003A),
    secondary = Color(0xFFE5BDC0),
    onSecondary = Color(0xFF43292C),
    secondaryContainer = Color(0xFF5C3F42),
    onSecondaryContainer = Color(0xFFFFD9DD),
    tertiary = Color(0xFF4FDADB),
    onTertiary = Color(0xFF003737),
    tertiaryContainer = Color(0xFF004F51),
    onTertiaryContainer = Color(0xFF6FF7F8),
    background = Color(0xFF1A1112),
    onBackground = Color(0xFFF0DEDF),
    surface = Color(0xFF1A1112),
    onSurface = Color(0xFFF0DEDF),
    surfaceVariant = Color(0xFF524345),
    onSurfaceVariant = Color(0xFFD7C1C3),
    surfaceTint = Color(0xFFFF6B8A),
    inverseSurface = Color(0xFFF0DEDF),
    inverseOnSurface = Color(0xFF382E2F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFFA08C8E),
    outlineVariant = Color(0xFF524345),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF423738),
    surfaceContainer = Color(0xFF271D1E),
    surfaceContainerHigh = Color(0xFF322828),
    surfaceContainerHighest = Color(0xFF3D3233),
    surfaceContainerLow = Color(0xFF22191A),
    surfaceContainerLowest = Color(0xFF140C0D),
    surfaceDim = Color(0xFF1A1112),
)

/**
 * Colori fuori dallo schema M3, letti con `WhatikTheme.extended`.
 * - [brand]: solo grafica (icone del marchio, illustrazioni degli stati vuoti), mai testo.
 * - [success]/[onSuccess]: puntino «sessione attiva».
 * - [successContainer]/[onSuccessContainer]: chip «Su WhatsApp», «Concesso».
 */
@Immutable
data class ExtendedColors(
    val brand: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
)

val LightExtendedColors = ExtendedColors(
    brand = BrandPink,
    success = Color(0xFF1E6B34),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFC9EFD0),
    onSuccessContainer = Color(0xFF00210B),
)

val DarkExtendedColors = ExtendedColors(
    brand = BrandPink,
    success = Color(0xFF8DD89D),
    onSuccess = Color(0xFF00391A),
    successContainer = Color(0xFF1E5130),
    onSuccessContainer = Color(0xFFA8F5B7),
)

/** Fornito da [WhatikTheme]; fuori dal tema vale la palette chiara. */
val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }
