package com.whatik.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spaziature (audit UX, 3.2.3). Usi:
 * - margine laterale delle schermate: [l]; padding esterno delle griglie: [m] ai lati, [xs] sopra;
 * - spazio fra celle: [s]; fra card: [m]; fra sezioni: [xl];
 * - padding interno delle card: [l]; fondo di una lista con FAB: [fabClearance].
 * I valori 6, 10, 14 e 20 dp usati prima del redesign non si usano piu' nelle schermate.
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Spazio in fondo a liste e griglie sotto un FAB: 56 (FAB) + 16 + 16. */
    val fabClearance = 88.dp
}
