package dev.wissam.sshterm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TerminalGreen = Color(0xFF3FB950)
private val TerminalGreenDim = Color(0xFF2EA043)
private val Bg = Color(0xFF0D1117)
private val Surface1 = Color(0xFF161B22)
private val Surface2 = Color(0xFF21262D)
private val TextPrimary = Color(0xFFE6EDF3)
private val ErrorRed = Color(0xFFF85149)

private val DarkScheme = darkColorScheme(
    primary = TerminalGreen,
    onPrimary = Color.Black,
    primaryContainer = TerminalGreenDim,
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFF58A6FF),
    onSecondary = Color.Black,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFF8B949E),
    error = ErrorRed,
    onError = Color.Black,
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1A7F37),
    secondary = Color(0xFF0969DA),
)

@Composable
fun SshTermTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // Terminal app: dark by default regardless; light only if the system insists and we ever
    // decide to honor it. For v1 always dark — terminals are dark.
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
