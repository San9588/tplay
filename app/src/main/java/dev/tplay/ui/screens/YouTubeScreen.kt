package dev.tplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tplay.core.formatTime
import dev.tplay.data.model.YtStage
import dev.tplay.ui.MainViewModel
import dev.tplay.ui.components.AsciiBox
import dev.tplay.ui.components.BlinkingCursor
import dev.tplay.ui.components.SelectableRow
import dev.tplay.ui.components.TuiIconButton
import dev.tplay.ui.components.TuiText
import dev.tplay.ui.theme.LocalTuiAccent
import dev.tplay.ui.theme.LocalTuiGreen
import dev.tplay.ui.theme.TuiBg
import dev.tplay.ui.theme.TuiDim
import dev.tplay.ui.theme.TuiFg
import dev.tplay.ui.theme.TuiFaint
import kotlinx.coroutines.flow.map

@Composable
fun YouTubeScreen(vm: MainViewModel) {
    var query by remember { mutableStateOf(vm.searchQuery) }
    val results = vm.searchResults
    val loading = vm.searchLoading
    val resolving = vm.resolvingId
    val youtubeError = vm.youtubeError
    val nowPlayingId by remember { vm.playerState.map { it.currentSong?.id } }
        .collectAsState(initial = null)
    val green = LocalTuiGreen.current
    val accent = LocalTuiAccent.current

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        AsciiBox(title = " YOUTUBE SEARCH ") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TuiText("> ", color = green)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it; vm.updateSearchQuery(it) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = TuiFg,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                    cursorBrush = SolidColor(TuiFg),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                )
                Spacer(Modifier.padding(2.dp))
                TuiIconButton(
                    label = if (loading) "..." else "search",
                    onClick = { vm.search() },
                    accent = true,
                )
            }
        }

        // live pipeline status — one strip, active stage blinks
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StageChip("fetch", vm.ytStage == YtStage.FETCH, accent)
            StageChip("parse", vm.ytStage == YtStage.PARSE, accent)
            StageChip("ready", vm.ytStage == YtStage.READY, accent)
            StageChip("playing", vm.ytStage == YtStage.PLAYING, accent)
        }

        if (resolving != null) {
            Row(Modifier.padding(vertical = 4.dp)) {
                TuiText("resolving stream...", color = TuiDim, size = 10)
            }
        }

        if (youtubeError != null) {
            Row(Modifier.padding(vertical = 4.dp)) {
                TuiText("error: $youtubeError", color = accent, size = 10)
            }
        }

        if (results.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TuiText("type a query + [enter]", color = TuiFaint)
            }
            return@Column
        }

        if (loading && results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TuiText("fetching...", color = TuiDim)
            }
            return@Column
        }

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TuiText("${results.size} results", color = TuiDim, size = 10)
                    Spacer(Modifier.weight(1f))
                    TuiIconButton("play all", onClick = { vm.playYoutubeQueue(0) }, accent = true)
                }
            }
            items(results.size, key = { results[it].id }) { index ->
                val song = results[index]
                val isCurrent = song.id == nowPlayingId
                SelectableRow(
                    text = song.title,
                    selected = isCurrent,
                    onClick = { vm.playYoutube(song) },
                    meta = formatTime(song.durationMs),
                )
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}

/** One status chip, e.g. `[fetch_]` — the underscore blinks while the stage is active. */
@Composable
private fun StageChip(label: String, active: Boolean, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TuiText("[", color = if (active) accent else TuiFaint, size = 10)
        TuiText(label, color = if (active) accent else TuiFaint, size = 10)
        if (active) {
            BlinkingCursor(color = accent, size = 10)
        } else {
            TuiText("_", color = TuiFaint, size = 10)
        }
        TuiText("]", color = if (active) accent else TuiFaint, size = 10)
    }
}
