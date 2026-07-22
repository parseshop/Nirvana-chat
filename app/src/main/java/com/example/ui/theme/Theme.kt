package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Standard Dark / Light Color Schemes
private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80,
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color(0xFFE3E3E3),
    onSurface = Color(0xFFE3E3E3)
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
    background = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F)
)

// Nirvana Premium Themes
private val LavenderColorScheme = darkColorScheme(
    primary = LavenderPrimary,
    secondary = LavenderSecondary,
    tertiary = LavenderTertiary,
    background = LavenderBackground,
    surface = LavenderSurface,
    onPrimary = LavenderOnPrimary,
    onBackground = LavenderOnBackground,
    onSurface = LavenderOnSurface
)

private val OceanicColorScheme = darkColorScheme(
    primary = OceanicPrimary,
    secondary = OceanicSecondary,
    tertiary = OceanicTertiary,
    background = OceanicBackground,
    surface = OceanicSurface,
    onPrimary = OceanicOnPrimary,
    onBackground = OceanicOnBackground,
    onSurface = OceanicOnSurface
)

private val RoyalColorScheme = darkColorScheme(
    primary = RoyalPrimary,
    secondary = RoyalSecondary,
    tertiary = RoyalTertiary,
    background = RoyalBackground,
    surface = RoyalSurface,
    onPrimary = RoyalOnPrimary,
    onBackground = RoyalOnBackground,
    onSurface = RoyalOnSurface
)

@Composable
fun NirvanaTheme(
    themeMode: String, // "system", "light", "dark", "lavender", "oceanic", "royal"
    content: @Composable () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    
    val colorScheme = when (themeMode) {
        "light" -> LightColorScheme
        "dark" -> DarkColorScheme
        "lavender" -> LavenderColorScheme
        "oceanic" -> OceanicColorScheme
        "royal" -> RoyalColorScheme
        else -> {
            if (darkTheme) DarkColorScheme else LightColorScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
