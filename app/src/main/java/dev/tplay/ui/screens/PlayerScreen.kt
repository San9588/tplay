package dev.tplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import dev.tplay.core.AsciiCover
import dev.tplay.core.formatTime
import dev.tplay.data.model.SongSource
import dev.tplay.data.lyrics.Lyrics
import dev.tplay.ui.MainViewModel
import dev.tplay.ui.components.AsciiBox
import dev.tplay.ui.components.BlinkingCursor
import dev.tplay.ui.components.TuiIconButton
import dev.tplay.ui.components.TuiSeekBar
import dev.tplay.ui.components.TuiText
import dev.tplay.ui.theme.LocalTuiAccent
import dev.tplay.ui.theme.LocalTuiGreen
import dev.tplay.ui.theme.TuiDim
import dev.tplay.ui.theme.TuiFg
import dev.tplay.ui.theme.TuiFaint

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(vm: MainViewModel) {
    val st by vm.playerState.collectAsState()
    val cover = vm.asciiCover
    val song = st.currentSong
    val accent = LocalTuiAccent.current
    val green = LocalTuiGreen.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            TuiText("NOW PLAYING", color = TuiDim, size = 10)
        }

        if (song == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TuiText("nothing playing", color = TuiFaint)
            }
            return@Column
        }

        Spacer(Modifier.padding(6.dp))

        AsciiCover(
            cover = cover,
            playing = st.isPlaying && !st.isBuffering,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clipToBounds(),
        )

        Spacer(Modifier.padding(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TuiText(
                song.title,
                color = TuiFg,
                size = 14,
            )
            BlinkingCursor(color = accent, size = 14)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TuiText(
                song.artist,
                color = TuiDim,
                size = 12,
            )
            TuiText(
                "[ ${st.audioFormatInfo} ]",
                color = accent,
                size = 10,
            )
        }

        Spacer(Modifier.padding(6.dp))

        // Only display YouTube live pipeline status ([fetch_]/[parse_]/[ready_]/[playing_])
        // on the music progress bar page when the song is streaming from YouTube (not local play).
        if (song.source == SongSource.YOUTUBE) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SingleStageChip(vm.ytStage, accent)
                if (st.isLoading) {
                    TuiText("resolving stream...", color = green, size = 10)
                }
            }
            Spacer(Modifier.padding(4.dp))
        } else if (st.isLoading) {
            TuiText("resolving stream...", color = green, size = 11)
            Spacer(Modifier.padding(4.dp))
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TuiText(formatTime(st.positionMs), color = TuiDim, size = 10)
            Spacer(Modifier.padding(4.dp))
            TuiSeekBar(
                progress = st.progress,
                modifier = Modifier.weight(1f),
                onSeek = { fraction ->
                    vm.seekTo((fraction * st.durationMs).toLong())
                },
            )
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

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            val repeatLabel = when (st.repeatMode) {
                1 -> "RPT:A"
                2 -> "RPT:1"
                else -> "RPT:0"
            }
            TuiIconButton(repeatLabel, onClick = { vm.cycleRepeat() }, accent = st.repeatMode != 0)
            TuiIconButton(
                if (st.shuffleEnabled) "SHUF:ON" else "SHUF:OFF",
                onClick = { vm.toggleShuffle() },
                accent = st.shuffleEnabled,
            )
            TuiIconButton(
                "LYRICS",
                onClick = { vm.showLyricsPanel() },
                accent = vm.showLyrics,
            )
            TuiIconButton(
                "QUEUE",
                onClick = { vm.showQueuePanel() },
                accent = !vm.showLyrics,
            )
            TuiIconButton(
                if (vm.hapticBassEnabled) "VBASS:ON" else "VBASS:OFF",
                onClick = { vm.toggleHapticBass() },
                accent = vm.hapticBassEnabled,
            )
            TuiIconButton(
                "VSTEP:${vm.hapticBassStep}",
                onClick = { vm.cycleHapticStep() },
                accent = vm.hapticBassEnabled,
            )
            if (st.sleepRemainingMs > 0) {
                TuiText("Zz ${st.sleepRemainingMs / 60_000}m", color = green, size = 10)
            }
        }

        Spacer(Modifier.padding(14.dp))

        if (vm.showLyrics) {
            AsciiBox(title = " LYRICS ") {
                LyricsPanel(vm, st.positionMs, accent)
            }
        } else {
            AsciiBox(title = " QUEUE NEXT ") {
                QueueNext(st.queue, st.currentIndex, accent)
            }
        }

        Spacer(Modifier.padding(16.dp))
    }
}

@Composable
private fun QueueNext(
    queue: List<dev.tplay.data.model.Song>,
    currentIndex: Int,
    accent: androidx.compose.ui.graphics.Color,
) {
    Column(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 520.dp)) {
        val nextSongs = queue.drop(currentIndex + 1).take(8)
        if (nextSongs.isEmpty()) {
            TuiText("end of queue", color = TuiFaint, size = 11)
        } else {
            nextSongs.forEachIndexed { i, s ->
                TuiText("${i + 1}. ${s.title}", color = if (i == 0) accent else TuiFg, size = 11)
                TuiText("   ${s.artist}", color = TuiFaint, size = 10)
            }
        }
    }
}

@Composable
private fun LyricsPanel(
    vm: MainViewModel,
    positionMs: Long,
    accent: androidx.compose.ui.graphics.Color,
) {
    val green = LocalTuiGreen.current
    if (vm.lyricsLoading) {
        TuiText("loading lyrics...", color = TuiDim, size = 11)
        return
    }
    val lyrics = vm.lyrics
    if (lyrics == null) {
        TuiText("no lyrics found", color = TuiFaint, size = 11)
        TuiText("place <title>.lrc next to the track", color = TuiFaint, size = 10)
        return
    }
    if (lyrics.isSynced) {
        SyncedLyrics(lyrics, positionMs, accent, green)
    } else {
        val lines = lyrics.lines
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            lines.forEach { line ->
                TuiText(line.text, color = TuiFg, size = 11)
            }
        }
    }
}

@Composable
private fun SyncedLyrics(
    lyrics: Lyrics,
    positionMs: Long,
    accent: androidx.compose.ui.graphics.Color,
    green: androidx.compose.ui.graphics.Color,
) {
    val idx = lyrics.currentIndex(positionMs)
    val start = (idx - 2).coerceAtLeast(0)
    val end = (idx + 8).coerceAtMost(lyrics.lines.size)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 520.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (idx < 0) {
            TuiText("...", color = TuiFaint, size = 11)
        }
        for (i in start until end) {
            val line = lyrics.lines[i]
            when {
                i == idx -> TuiText("> ${line.text}", color = accent, size = 11)
                i < idx -> TuiText("  ${line.text}", color = TuiFaint, size = 11)
                else -> TuiText("  ${line.text}", color = TuiDim, size = 11)
            }
        }
        if (end < lyrics.lines.size) {
            TuiText("...", color = TuiFaint, size = 11)
        }
    }
}
