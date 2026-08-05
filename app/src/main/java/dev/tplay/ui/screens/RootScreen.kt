package dev.tplay.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.tplay.core.formatTime
import dev.tplay.ui.MainViewModel
import dev.tplay.ui.Tab
import dev.tplay.ui.components.BlinkingCursor
import dev.tplay.ui.components.TuiText
import dev.tplay.ui.theme.LocalTuiAccent
import dev.tplay.ui.theme.TuiBg
import dev.tplay.ui.theme.TuiDim
import dev.tplay.ui.theme.TuiFaint
import dev.tplay.ui.theme.TuiFg
import dev.tplay.ui.theme.TuiPanel

private val tabLabels = mapOf(
    Tab.LIBRARY to "LIB",
    Tab.YOUTUBE to "YT",
    Tab.PLAYLISTS to "PL",
    Tab.QUEUE to "QUE",
    Tab.SETTINGS to "CFG",
)

@Composable
fun RootScreen(vm: MainViewModel) {
    val st by vm.playerState.collectAsState()

    BackHandler(enabled = vm.showPlayer) {
        vm.closePlayer()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(TuiBg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.showPlayer) {
                PlayerScreen(vm)
            } else {
                when (vm.tab) {
                    Tab.LIBRARY -> LibraryScreen(vm)
                    Tab.YOUTUBE -> YouTubeScreen(vm)
                    Tab.PLAYLISTS -> PlaylistsScreen(vm)
                    Tab.QUEUE -> QueueScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }

        val current = st.currentSong
        if (current != null && !vm.showPlayer) {
            MiniPlayer(
                title = current.title,
                artist = current.artist,
                duration = st.durationMs,
                position = st.positionMs,
                isPlaying = st.isPlaying,
                onOpen = { vm.openPlayer() },
                onToggle = { vm.toggle() },
                onNext = { vm.next() },
            )
        }

        TabBar(
            current = if (vm.showPlayer) null else vm.tab,
            onSelect = { vm.selectTab(it) },
        )
    }
}

@Composable
private fun MiniPlayer(
    title: String,
    artist: String,
    duration: Long,
    position: Long,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    val accent = LocalTuiAccent.current
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val barWidth = 12
    val filled = (progress * barWidth).toInt()
    val bar = "\u2588".repeat(filled) + "\u00B7".repeat(barWidth - filled)

    Column(Modifier.fillMaxWidth().background(TuiPanel)) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(accent.copy(alpha = 0.6f))
                .padding(1.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TuiText(title, color = TuiFg, size = 12)
                        BlinkingCursor(color = accent, size = 12)
                    }
                    TuiText(artist, color = TuiDim, size = 10)
                }
            }
            TuiText("[$bar]", color = TuiDim, size = 10)
            Spacer(Modifier.padding(6.dp))
            TuiText(
                if (isPlaying) "||" else ">",
                modifier = Modifier.padding(horizontal = 4.dp).clickable { onToggle() },
                color = accent,
                size = 13,
            )
            TuiText(
                ">>|",
                modifier = Modifier.padding(horizontal = 6.dp).clickable { onNext() },
                color = TuiFg,
                size = 13,
            )
        }
    }
}

@Composable
private fun TabBar(current: Tab?, onSelect: (Tab) -> Unit) {
    val accent = LocalTuiAccent.current
    Column(Modifier.fillMaxWidth().background(TuiPanel)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(accent),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(TuiBg)
                .padding(vertical = 6.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
        ) {
            Tab.entries.forEach { tab ->
                val selected = tab == current
                TuiText(
                    tabLabels[tab] ?: "??",
                    modifier = Modifier
                        .clickable { onSelect(tab) }
                        .background(if (selected) TuiPanel else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (selected) accent else TuiDim,
                    size = 11,
                )
            }
        }
    }
}
