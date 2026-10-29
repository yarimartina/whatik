package com.whatik.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/*
 * Scala tipografica (audit UX, 3.2.2): Roboto di sistema, nessun font esterno. I valori sono quelli
 * della scala M3 di base, dichiarati qui per fissarne gli usi:
 *
 * headlineSmall  24/32/400  titolo degli stati vuoti
 * titleLarge     22/28/400  barra superiore
 * titleMedium    16/24/500  titoli delle card, nome del pack
 * titleSmall     14/20/500  intestazioni di sezione (colore primary, heading())
 * bodyLarge      16/24/400  testo principale degli stati vuoti e della Cattura
 * bodyMedium     14/20/400  testi di supporto, suggerimenti, istruzioni
 * bodySmall      12/16/400  SOLO metadati (conteggi, cartelle, nomi file)
 * labelLarge     14/20/500  pulsanti
 * labelMedium    12/16/500  chip di stato, etichette della barra in basso
 * labelSmall     11/16/500  solo badge sulle tessere
 *
 * Regola: nessuna istruzione sotto i 14 sp.
 */

private val Base = Typography()

private fun TextStyle.sized(size: Int, lineHeight: Int, weight: FontWeight, tracking: TextUnit = letterSpacing) =
    copy(fontSize = size.sp, lineHeight = lineHeight.sp, fontWeight = weight, letterSpacing = tracking)

val WhatikTypography = Typography(
    displayLarge = Base.displayLarge,
    displayMedium = Base.displayMedium,
    displaySmall = Base.displaySmall,
    headlineLarge = Base.headlineLarge,
    headlineMedium = Base.headlineMedium,
    headlineSmall = Base.headlineSmall.sized(24, 32, FontWeight.Normal),
    titleLarge = Base.titleLarge.sized(22, 28, FontWeight.Normal),
    titleMedium = Base.titleMedium.sized(16, 24, FontWeight.Medium),
    titleSmall = Base.titleSmall.sized(14, 20, FontWeight.Medium),
    bodyLarge = Base.bodyLarge.sized(16, 24, FontWeight.Normal),
    bodyMedium = Base.bodyMedium.sized(14, 20, FontWeight.Normal),
    bodySmall = Base.bodySmall.sized(12, 16, FontWeight.Normal),
    labelLarge = Base.labelLarge.sized(14, 20, FontWeight.Medium),
    labelMedium = Base.labelMedium.sized(12, 16, FontWeight.Medium),
    labelSmall = Base.labelSmall.sized(11, 16, FontWeight.Medium),
)
