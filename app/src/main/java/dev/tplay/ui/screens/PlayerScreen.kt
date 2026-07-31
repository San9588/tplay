package dev.tplay.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tplay.core.AsciiCover
import dev.tplay.core.formatTime
import dev.tplay.ui.MainViewModel
import dev.tplay.ui.components.AsciiBox
import dev.tplay.ui.components.TuiIconButton
import dev.tplay.ui.components.TuiProgressBar
import dev.tplay.ui.components.TuiText
import dev.tplay.ui.theme.TuiAccent
import dev.tplay.ui.theme.TuiBg
import dev.tplay.ui.theme.TuiDim
import dev.tplay.ui.theme.TuiFg
import dev.tplay.ui.theme.TuiFaint
import dev.tplay.ui.theme.TuiGreen

@Composable
fun PlayerScreen(vm: MainViewModel) {
    val st by vm.playerState.collectAsState()
    val cover = vm.asciiCover
    val song = st.currentSong

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TuiIconButton("[x]", onClick = { vm.closePlayer() })
            Spacer(Modifier.weight(1f))
            TuiText("NOW PLAYING", color = TuiDim, size = 10)
        }

        if (song == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TuiText("nothing playing", color = TuiFaint)
            }
            return@Column
        }

        Spacer(Modifier.padding(8.dp))

        AsciiCover(
            cover = cover,
            playing = st.isPlaying && !st.isBuffering,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clipToBounds(),
        )

        Spacer(Modifier.padding(10.dp))

        TuiText(
            song.title,
            color = TuiFg,
            size = 14,
        )
        TuiText(
            song.artist,
            color = TuiDim,
            size = 12,
        )

        Spacer(Modifier.padding(8.dp))

        if (st.isLoading) {
            TuiText("resolving stream...", color = TuiGreen, size = 11)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            TuiText(formatTime(st.positionMs), color = TuiDim, size = 10)
            Spacer(Modifier.padding(4.dp))
            TuiProgressBar(st.progress, Modifier.width(140.dp))
            Spacer(Modifier.padding(4.dp))
            TuiText(formatTime(st.durationMs), color = TuiDim, size = 10)
        }

        Spacer(Modifier.padding(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TuiIconButton("|<<", onClick = { vm.previous() })
            val playLabel = when {
                st.isBuffering -> "buffering"
                st.isPlaying -> "||"
                else -> ">"
            }
            TuiIconButton(playLabel, onClick = { vm.toggle() }, accent = true)
            TuiIconButton(">>|", onClick = { vm.next() })
        }

        Spacer(Modifier.padding(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            val repeatLabel = when (st.repeatMode) {
                1 -> "RPT:A"
                2 -> "RPT:1"
                else -> "RPT:0"
            }
            TuiIconButton(repeatLabel, onClick = { vm.cycleRepeat() }, accent = st.repeatMode != 0)
            Spacer(Modifier.padding(4.dp))
            TuiIconButton(
                if (st.shuffleEnabled) "SHUF:ON" else "SHUF:OFF",
                onClick = { vm.toggleShuffle() },
                accent = st.shuffleEnabled,
            )
            Spacer(Modifier.padding(4.dp))
            if (st.sleepRemainingMs > 0) {
                TuiText("Zz ${st.sleepRemainingMs / 60_000}m", color = TuiGreen, size = 10)
            }
        }

        Spacer(Modifier.padding(14.dp))

        AsciiBox(title = " QUEUE NEXT ") {
            val nextSong = st.queue.getOrNull(st.currentIndex + 1)
            if (nextSong != null) {
                TuiText("> ${nextSong.title}", color = TuiDim, size = 11)
                TuiText("  ${nextSong.artist}", color = TuiFaint, size = 10)
            } else {
                TuiText("end of queue", color = TuiFaint, size = 11)
            }
        }

        Spacer(Modifier.padding(16.dp))
    }
}
