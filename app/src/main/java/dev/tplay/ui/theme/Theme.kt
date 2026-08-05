package dev.tplay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

@Composable
fun TplayTheme(
    systemAccent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var accent = TuiAccent
    var green = TuiGreen
    if (systemAccent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamic = runCatching { dynamicDarkColorScheme(context) }.getOrNull()
        if (dynamic != null) {
            accent = dynamic.primary
            green = dynamic.secondary
        }
    }
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = TuiBg,
        secondary = green,
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
    CompositionLocalProvider(
        LocalTuiAccent provides accent,
        LocalTuiGreen provides green,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = TuiTypography,
            content = content,
        )
    }
}
