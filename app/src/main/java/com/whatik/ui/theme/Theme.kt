package com.whatik.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val TikTokPink = Color(0xFFFE2C55)
private val TikTokCyan = Color(0xFF25F4EE)

private val LightColors = lightColorScheme(
    primary = TikTokPink,
    onPrimary = Color.White,
    secondary = Color(0xFF00897B),
    tertiary = TikTokCyan,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF6B8A),
    onPrimary = Color(0xFF3B0012),
    secondary = Color(0xFF4DB6AC),
    tertiary = TikTokCyan,
)

@Composable
fun WhatikTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
