package dev.ignorantshuffle.companion

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private fun ratio(a: Color, b: Color): Float {
        val x = a.luminance(); val y = b.luminance()
        return (maxOf(x, y) + .05f) / (minOf(x, y) + .05f)
    }
    @Test fun textRemainsReadableAcrossAllAccentsInBothThemes() {
        for (accent in Accent.entries) for (dark in listOf(false, true)) {
            val colors = appColors(accent, dark)
            listOf(colors.primary to colors.onPrimary, colors.primaryContainer to colors.onPrimaryContainer,
                colors.background to colors.onBackground, colors.surfaceContainerLow to colors.onSurface).forEach { (background, text) ->
                assertTrue("Insufficient contrast: $accent dark=$dark", ratio(background, text) >= 4.5f)
            }
        }
    }
}
