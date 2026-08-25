package com.aegismed.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val DeepNavy = Color(0xFF0E2A47)
private val Teal = Color(0xFF00796B)
private val SkyBlue = Color(0xFF4FC3F7)
private val AlertRed = Color(0xFFC62828)
private val Amber = Color(0xFFF9A825)

val TierCriticalColor = AlertRed
val TierStandardColor = Amber
val TierElectiveColor = Teal
val OkGreen = Color(0xFF2E7D32)
val AnchorBlue = Color(0xFF1565C0)

private val LightColors = lightColorScheme(
    primary = DeepNavy,
    secondary = Teal,
    tertiary = SkyBlue,
    error = AlertRed,
    surfaceTint = Color.Transparent
)

private val DarkColors = darkColorScheme(
    primary = SkyBlue,
    secondary = Teal,
    tertiary = Amber,
    error = Color(0xFFEF5350),
    surfaceTint = Color.Transparent
)

@Composable
fun AegisTheme(
    fontScale: Float = 1.15f,
    content: @Composable () -> Unit
) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * fontScale, base.fontScale * fontScale)
    ) {
        MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
    }
}
