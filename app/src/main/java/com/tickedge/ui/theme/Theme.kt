package com.tickedge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = VividYellow,
    onPrimary = Color(0xFF101216),
    primaryContainer = Color(0xFF2C2812),
    onPrimaryContainer = VividYellow,
    secondary = VividYellowDark,
    onSecondary = Color(0xFF101216),
    secondaryContainer = Color(0xFF262A36),
    onSecondaryContainer = Color(0xFFE2E4EB),
    tertiary = StatGreen,
    onTertiary = Color(0xFF101216),
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceDarkCard,
    onSurfaceVariant = TextSecondary,
    outline = BorderDark,
    outlineVariant = Color(0xFF212530),
    error = StatRed,
    onError = Color.White
)

@Composable
fun TickEdgeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
