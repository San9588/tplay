package dev.tplay.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.tplay.R

val Mono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    Font(R.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
)

private fun mono(
    size: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Float = 0f,
) = TextStyle(
    fontFamily = Mono,
    fontSize = size.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
)

val TuiTypography = androidx.compose.material3.Typography(
    displayLarge = mono(28, FontWeight.Bold),
    headlineMedium = mono(18, FontWeight.Bold),
    titleLarge = mono(16, FontWeight.Bold),
    titleMedium = mono(14, FontWeight.Bold),
    bodyLarge = mono(14),
    bodyMedium = mono(13),
    bodySmall = mono(11, tracking = 0.3f),
    labelLarge = mono(13, FontWeight.Bold, tracking = 1f),
    labelMedium = mono(12, tracking = 1.2f),
    labelSmall = mono(10, tracking = 1.4f),
)
