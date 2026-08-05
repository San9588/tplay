package dev.tplay.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val TuiBg = Color(0xFF000000)
val TuiSurface = Color(0xFF0E0F10)
val TuiPanel = Color(0xFF131415)
val TuiFg = Color(0xFFC6CBCC)
val TuiBright = Color(0xFFDFE4E5)
val TuiDim = Color(0xFF767D80)
val TuiFaint = Color(0xFF3C4245)
val TuiLine = Color(0xFF1E2122)
val TuiAccent = Color(0xFFE8A33D)
val TuiGreen = Color(0xFF7FA05F)
val TuiSeekFill = Color(0xFF60B860)
val TuiRed = Color(0xFFB85C50)
val TuiBlue = Color(0xFF5F87A0)
val TuiCyan = Color(0xFF5FB3A0)

val LocalTuiAccent = staticCompositionLocalOf { TuiAccent }
val LocalTuiGreen = staticCompositionLocalOf { TuiGreen }
