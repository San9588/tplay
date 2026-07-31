package dev.tplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tplay.ui.theme.TuiBg
import dev.tplay.ui.theme.TuiDim
import dev.tplay.ui.theme.TuiFg
import dev.tplay.ui.theme.TuiFaint
import dev.tplay.ui.theme.TuiPanel

@Composable
fun AsciiBox(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tl = "\u250C" // ┌
    val tr = "\u2510" // ┐
    val bl = "\u2514" // └
    val br = "\u2518" // ┘
    val h = "\u2500" // ─
    val v = "\u2502" // │

    Column(modifier = modifier) {
        Row {
            Text(tl, color = TuiFaint)
            Text(title, color = TuiDim)
            Text("$h$h$h$h$h", color = TuiFaint)
            Spacer(Modifier.weight(1f))
            Text(tr, color = TuiFaint)
        }
        Row(modifier = Modifier.background(TuiPanel).fillMaxWidth()) {
            Text(v, color = TuiFaint)
            Box(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) { content() }
            Text(v, color = TuiFaint)
        }
        Row {
            Text(bl, color = TuiFaint)
            Spacer(Modifier.weight(1f))
            Text(br, color = TuiFaint)
        }
    }
}

@Composable
fun TuiIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
) {
    val color = when {
        !enabled -> TuiFaint
        accent -> Color(0xFF7FA05F)
        else -> TuiFg
    }
    Text(
        text = label,
        modifier = modifier
            .clickable(enabled = enabled) { onClick() }
            .background(
                if (accent) Color(0x1F7FA05F) else TuiPanel,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

@Composable
fun TuiProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    width: Int = 28,
) {
    val filled = (progress.coerceIn(0f, 1f) * width).toInt()
    val bar = "\u2588".repeat(filled) + "\u00B7".repeat(width - filled)
    Text(
        "[$bar]",
        modifier = modifier,
        color = TuiFg,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
}

@Composable
fun TuiText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TuiFg,
    size: Int = 12,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = size.sp,
    )
}

@Composable
fun SelectableRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = TuiFg,
    meta: String? = null,
) {
    val marker = if (selected) "\u25B8 " else "  " // ▸
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(if (selected) TuiPanel else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuiText(marker, color = if (selected) Color(0xFFE8A33D) else TuiFaint)
        TuiText(
            text,
            modifier = Modifier.weight(1f),
            color = if (selected) Color(0xFFE8A33D) else color,
        )
        if (meta != null) {
            TuiText(meta, color = TuiDim)
        }
    }
}
