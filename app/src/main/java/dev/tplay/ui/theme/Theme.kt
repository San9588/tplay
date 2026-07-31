package dev.tplay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TuiScheme = darkColorScheme(
    primary = TuiAccent,
    onPrimary = TuiBg,
    secondary = TuiGreen,
    onSecondary = TuiBg,
    tertiary = TuiCyan,
    background = TuiBg,
    onBackground = TuiFg,
    surface = TuiSurface,
    onSurface = TuiFg,
    surfaceVariant = TuiPanel,
    onSurfaceVariant = TuiDim,
    outline = TuiLine,
    outlineVariant = TuiLine,
    error = TuiRed,
    onError = TuiBg,
)

@Composable
fun TplayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TuiScheme,
        typography = TuiTypography,
        content = content,
    )
}
