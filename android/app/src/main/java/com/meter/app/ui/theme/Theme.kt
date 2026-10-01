package com.meter.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Design tokens, lifted 1:1 from the uploaded Meter design. They are the single source of truth
 * for both screens, so the app can never drift from the design.
 */
object MeterColors {
    val Bg = Color(0xFF0A0A0A)
    val Card = Color(0xFF131313)
    val CardLine = Color(0xFF212121)
    val LineSoft = Color(0xFF282828)
    val Track = Color(0xFF252525)

    val Green = Color(0xFF00E5A0)
    val GreenDim = Color(0xFF112821)
    val Blue = Color(0xFF4FC3F7)
    val Amber = Color(0xFFFFB020)
    val Red = Color(0xFFFF6B6B)
    val RedDim = Color(0xFF1A1212)
    val RedLine = Color(0xFF3A2323)

    val Ink1 = Color(0xFFFFFFFF)
    val Ink2 = Color(0xFFB5B5B5)
    val Ink3 = Color(0xFF9F9F9F)
    val Ink4 = Color(0xFF898989)
    val Ink5 = Color(0xFF7A7A7A)
    val Ink6 = Color(0xFF555555)
    val Ink7 = Color(0xFF777777)
}

/** Sizes measured from the design (px at 1x on a 390 dp wide phone). */
object MeterDims {
    const val SCREEN_PAD = 13
    const val CARD_GAP = 12
    const val RADIUS = 13
    const val RING = 122
    const val RING_STROKE = 12.5f
    const val BAR = 8
}

private val scheme = darkColorScheme(
    primary = MeterColors.Green,
    onPrimary = MeterColors.Bg,
    background = MeterColors.Bg,
    onBackground = MeterColors.Ink1,
    surface = MeterColors.Card,
    onSurface = MeterColors.Ink1,
    surfaceVariant = MeterColors.Card,
    outline = MeterColors.CardLine,
    error = MeterColors.Red
)

@Composable
fun MeterTheme(content: @Composable () -> Unit) {
    // The design is dark-only by definition.
    @Suppress("UNUSED_EXPRESSION") isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
