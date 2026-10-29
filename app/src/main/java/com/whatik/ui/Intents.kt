package com.whatik.ui

/**
 * Extra booleano per l'intent di MainActivity: se true l'app si apre sulla tab Cattura.
 * Lo mette il `contentIntent` della notifica di sessione (CaptureService); lo legge `handleIntent`.
 */
const val EXTRA_OPEN_CAPTURE = "com.whatik.extra.OPEN_CAPTURE"
