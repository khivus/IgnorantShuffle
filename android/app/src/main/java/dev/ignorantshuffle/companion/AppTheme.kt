package dev.ignorantshuffle.companion

import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat

internal fun readableOn(color: Color): Color = if (color.luminance() > .179f) Color.Black else Color.White

internal fun appColors(accent: Accent, dark: Boolean): androidx.compose.material3.ColorScheme {
    val seed = Color(accent.argb)
    val primary = if (dark) lerp(seed, Color.White, .22f) else seed
    val container = if (dark) lerp(seed, Color(0xFF141211), .72f) else lerp(seed, Color.White, .86f)
    return if (dark) darkColorScheme(
        primary = primary, onPrimary = readableOn(primary), primaryContainer = container,
        onPrimaryContainer = Color(0xFFFFF4EC), secondary = primary, onSecondary = readableOn(primary),
        secondaryContainer = container, onSecondaryContainer = Color(0xFFFFF4EC),
        tertiary = primary, onTertiary = readableOn(primary),
        background = Color(0xFF111214), onBackground = Color(0xFFECEDEF),
        surface = Color(0xFF111214), onSurface = Color(0xFFECEDEF),
        surfaceContainerLow = Color(0xFF1A1B1E), surfaceContainer = Color(0xFF202125),
        surfaceContainerHigh = Color(0xFF292A2E), outlineVariant = Color(0xFF424348))
    else lightColorScheme(
        primary = primary, onPrimary = readableOn(primary), primaryContainer = container,
        onPrimaryContainer = Color(0xFF211B16), secondary = primary, onSecondary = readableOn(primary),
        secondaryContainer = container, onSecondaryContainer = Color(0xFF211B16),
        tertiary = primary, onTertiary = readableOn(primary),
        background = Color(0xFFF7F7F9), onBackground = Color(0xFF1B1C20),
        surface = Color(0xFFF7F7F9), onSurface = Color(0xFF1B1C20),
        surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFF0F0F3),
        surfaceContainerHigh = Color(0xFFE8E8ED), outlineVariant = Color(0xFFD8D9E0))
}

@Composable
internal fun ShuffleTheme(preferences: AppPreferences, window: Window? = null, content: @Composable () -> Unit) {
    val dark = when (preferences.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    SideEffect {
        if (window != null) WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = appColors(preferences.accent, dark), content = content)
}
