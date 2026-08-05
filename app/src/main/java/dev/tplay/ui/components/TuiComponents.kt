package dev.tplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tplay.ui.theme.LocalTuiAccent
import dev.tplay.ui.theme.LocalTuiGreen
import dev.tplay.ui.theme.TuiBg
import dev.tplay.ui.theme.TuiDim
import dev.tplay.ui.theme.TuiFg
import dev.tplay.ui.theme.TuiFaint
import dev.tplay.ui.theme.TuiPanel
import dev.tplay.ui.theme.TuiSeekFill
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    val accentColor = LocalTuiGreen.current
    val color = when {
        !enabled -> TuiFaint
        accent -> accentColor
        else -> TuiFg
    }
    Text(
        text = label,
        modifier = modifier
            .clickable(enabled = enabled) { onClick() }
            .background(
                if (accent) accentColor.copy(alpha = 0.12f) else TuiPanel,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

@Composable
fun TuiSeekBar(
    progress: Float,
    modifier: Modifier = Modifier,
    onSeek: ((Float) -> Unit)? = null,
) {
    val accent = TuiSeekFill
    var widthPx by remember { mutableIntStateOf(1) }
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .height(18.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .then(
                if (onSeek != null) {
                    Modifier.pointerInput(widthPx) {
                        detectTapGestures { offset ->
                            onSeek((offset.x / widthPx).coerceIn(0f, 1f))
                        }
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(TuiDim.copy(alpha = 0.35f)),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
        Box(
            Modifier
                .offset {
                    IntOffset(
                        ((fraction * widthPx) - 6.dp.toPx()).roundToInt().coerceAtLeast(0),
                        0,
                    )
                }
                .size(12.dp)
                .clip(CircleShape)
                .background(accent),
        )
    }
}

@Composable
fun BlinkingCursor(
    modifier: Modifier = Modifier,
    color: Color = LocalTuiAccent.current,
    size: Int = 12,
) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            on = !on
            delay(530)
        }
    }
    // Always render "_" but toggle text color to Transparent when off so the layout width
    // never changes by even a fraction of a pixel and surrounding text never shifts.
    TuiText("_", modifier = modifier, color = if (on) color else Color.Transparent, size = size)
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
    val marker = if (selected) "> " else "  "
    val accent = LocalTuiAccent.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(if (selected) TuiPanel else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TuiText(marker, color = if (selected) accent else TuiFaint)
        TuiText(
            text,
            modifier = Modifier.weight(1f),
            color = if (selected) accent else color,
        )
        if (meta != null) {
            TuiText(meta, color = TuiDim)
        }
    }
}
