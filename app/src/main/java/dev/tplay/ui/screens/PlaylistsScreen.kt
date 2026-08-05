package dev.tplay.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tplay.core.formatTime
import dev.tplay.ui.MainViewModel
import dev.tplay.ui.components.AsciiBox
import dev.tplay.ui.components.SelectableRow
import dev.tplay.ui.components.TuiIconButton
import dev.tplay.ui.components.TuiText
import dev.tplay.ui.theme.TuiDim
import dev.tplay.ui.theme.TuiFaint
import dev.tplay.ui.theme.TuiFg

@Composable
fun PlaylistsScreen(vm: MainViewModel) {
    val playlists by vm.playlists.collectAsState()
    val openId = vm.currentPlaylistId

    if (openId != null) {
        PlaylistDetail(vm, openId)
        return
    }

    var newName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        AsciiBox(title = " PLAYLISTS ") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = TuiFg,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                    cursorBrush = SolidColor(TuiFg),
                )
                TuiIconButton(
                    label = "new",
                    onClick = {
                        if (newName.isNotBlank()) {
                            vm.createPlaylist(newName.trim())
                            newName = ""
                        }
                    },
                    accent = true,
                )
            }
        }

        if (playlists.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.padding(40.dp))
                TuiText("no playlists yet", color = TuiFaint)
                TuiText("type a name above and hit [new]", color = TuiDim, size = 10)
            }
            return@Column
        }

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(playlists.size, key = { playlists[it].id }) { index ->
                val pl = playlists[index]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectableRow(
                        text = pl.name,
                        selected = false,
                        onClick = { vm.openPlaylist(pl.id) },
                        modifier = Modifier.weight(1f),
                    )
                    TuiText(
                        "del",
                        modifier = Modifier.padding(horizontal = 8.dp).clickable { vm.deletePlaylist(pl.id) },
                        color = TuiDim,
                        size = 10,
                    )
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun PlaylistDetail(vm: MainViewModel, playlistId: Long) {
    var songs by remember { mutableStateOf<List<dev.tplay.data.model.Song>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(playlistId) {
        songs = vm.playlistSongs(playlistId)
        loaded = true
    }

    val nowPlayingId = vm.playerState.value.currentSong?.id

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        AsciiBox(title = " PLAYLIST ") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TuiIconButton("<- back", onClick = { vm.closePlaylist() })
                Spacer(Modifier.padding(4.dp))
                TuiIconButton("play", onClick = { vm.playPlaylist(playlistId) }, accent = true)
                Spacer(Modifier.padding(4.dp))
                TuiText("${songs.size} tracks", color = TuiFaint)
            }
        }

        if (!loaded) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.padding(40.dp))
                TuiText("loading...", color = TuiFaint)
            }
            return@Column
        }

        if (songs.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.padding(40.dp))
                TuiText("empty playlist", color = TuiFaint)
            }
            return@Column
        }

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(songs.size, key = { index -> "s$index" }) { index ->
                val song = songs[index]
                SelectableRow(
                    text = song.title,
                    selected = song.id == nowPlayingId,
                    onClick = { vm.playPlaylist(playlistId) },
                    meta = formatTime(song.durationMs),
                )
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}
