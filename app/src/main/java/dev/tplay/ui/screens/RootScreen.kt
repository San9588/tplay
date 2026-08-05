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

        if (!vm.showPlayer) {
            MiniPlayer(vm = vm, onOpen = { vm.openPlayer() })
        }

        TabBar(
            current = if (vm.showPlayer) null else vm.tab,
            onSelect = { vm.selectTab(it) },
        )
    }
}

@Composable
private fun MiniPlayer(
    vm: MainViewModel,
    onOpen: () -> Unit,
) {
    // Collect state only here so the 500ms position ticker recomposes this tiny bar
    // instead of the whole screen tree (fixes list lag while a song is playing).
    val st by vm.playerState.collectAsState()
    val song = st.currentSong ?: return
    val accent = LocalTuiAccent.current

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
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TuiText(song.title, color = TuiFg, size = 12)
                    BlinkingCursor(color = accent, size = 12)
                }
                TuiText(song.artist, color = TuiDim, size = 9)
            }
            TuiText(formatTime(st.positionMs), color = TuiFaint, size = 9)
            Spacer(Modifier.padding(4.dp))
            TuiText(
                if (st.isPlaying) "||" else ">",
                modifier = Modifier.padding(horizontal = 4.dp).clickable { vm.toggle() },
                color = accent,
                size = 13,
            )
            TuiText(
                ">>|",
                modifier = Modifier.padding(horizontal = 6.dp).clickable { vm.next() },
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
