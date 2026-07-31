package dev.tplay.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tplay.core.formatTime
import dev.tplay.ui.MainViewModel
import dev.tplay.ui.components.AsciiBox
import dev.tplay.ui.components.SelectableRow
import dev.tplay.ui.components.TuiText
import dev.tplay.ui.theme.TuiDim
import dev.tplay.ui.theme.TuiFaint

@Composable
fun LibraryScreen(vm: MainViewModel) {
    val songs = vm.localSongs
    val nowPlayingId = vm.playerState.value.currentSong?.id

    LaunchedEffect(Unit) {
        vm.refreshLibrary()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        AsciiBox(title = " LIBRARY ") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val status = when {
                    vm.libraryLoading -> "scanning... "
                    songs.isEmpty() -> "0 tracks"
                    else -> "${songs.size} tracks"
                }
                TuiText(status, color = TuiFaint)
            }
        }

        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TuiText("no local tracks found", color = TuiDim)
                    TuiText("grant storage permission & rescan", color = TuiFaint, size = 10)
                }
            }
            return@Column
        }

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                    TuiText("${songs.size} files indexed", color = TuiDim, size = 10)
                }
            }
            items(songs.size, key = { songs[it].id }) { index ->
                val song = songs[index]
                SelectableRow(
                    text = song.title,
                    selected = song.id == nowPlayingId,
                    onClick = { vm.playLibraryFrom(index) },
                    meta = formatTime(song.durationMs),
                )
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}
