package `is`.siggi.nextpost.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// Flagg is reserved for exactly two things: the current target and the primary action.
// Basalt is body text and cairn stones. Jokull is surfaces. Mosi is secondary text and
// dividers. Aska is borders, disabled states, and unstacked cairn stones. See section 14.3.
private val LightColorScheme = lightColorScheme(
    primary = Flagg,
    onPrimary = Jokull,
    primaryContainer = Aska,
    onPrimaryContainer = Basalt,
    secondary = Mosi,
    onSecondary = Jokull,
    secondaryContainer = Aska,
    onSecondaryContainer = Basalt,
    tertiary = Mosi,
    onTertiary = Jokull,
    background = Jokull,
    onBackground = Basalt,
    surface = Jokull,
    onSurface = Basalt,
    surfaceVariant = Aska,
    onSurfaceVariant = Mosi,
    outline = Aska,
    outlineVariant = Aska,
)

private val DarkColorScheme = darkColorScheme(
    primary = Flagg,
    onPrimary = Jokull,
    primaryContainer = Basalt,
    onPrimaryContainer = Jokull,
    secondary = Mosi,
    onSecondary = Basalt,
    secondaryContainer = Basalt,
    onSecondaryContainer = Jokull,
    tertiary = Mosi,
    onTertiary = Basalt,
    background = Basalt,
    onBackground = Jokull,
    surface = Basalt,
    onSurface = Jokull,
    surfaceVariant = Basalt,
    onSurfaceVariant = Aska,
    outline = Mosi,
    outlineVariant = Mosi,
)

/**
 * Light is the default per section 14.1: outdoor use in daylight makes dark UI less
 * readable, even at full brightness. Dark mode is offered, never defaulted to.
 */
@Composable
fun NextpostTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}