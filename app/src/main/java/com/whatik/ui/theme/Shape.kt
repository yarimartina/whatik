package com.whatik.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * Forme (audit UX, 3.2.4):
 * - small (8): miniature 44–72 dp, chip di stato
 * - medium (12): tessere delle griglie, card, campi
 * - large (16): anteprima dell'editor
 * - extraLarge (28, solo angoli superiori): bottom sheet (ModalBottomSheet lo applica da solo)
 * - CircleShape: indicatore di selezione, bolla, pulsanti radiali, badge
 * - pillola (raggio 24 dp): pillole degli overlay (View classiche, @dimen/overlay_pill_radius)
 */
val WhatikShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
