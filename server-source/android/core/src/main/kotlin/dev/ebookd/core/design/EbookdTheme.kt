package dev.ebookd.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's Material theme, kept in `:core` so screens in `:app` and any future surface share one
 * palette.
 *
 * It is deliberately plain for now: the reader's own palettes (night, sepia, pure black) are a
 * *layout* concern that belongs to the rendering milestone, and mixing them into the shell theme
 * would make the reader look like a settings screen. This one only has to be legible and quiet.
 */
private val Ink = Color(0xFF1F2933)
private val Paper = Color(0xFFFBF9F4)
private val Accent = Color(0xFF3D6B5C)
private val AccentDark = Color(0xFF9CC5B6)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF10231C),
    background = Color(0xFF12161A),
    onBackground = Color(0xFFE6E3DC),
    surface = Color(0xFF12161A),
    onSurface = Color(0xFFE6E3DC),
)

@Composable
fun EbookdTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
