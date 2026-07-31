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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import dev.tplay.ui.theme.TuiGreen

@Composable
fun QueueScreen(vm: MainViewModel) {
    val st = vm.playerState.value
    val queue = st.queue

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        AsciiBox(title = " QUEUE ") {
            val repeatLabel = when (st.repeatMode) {
                1 -> "REPEAT:ALL"
                2 -> "REPEAT:ONE"
                else -> "REPEAT:OFF"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TuiText("$repeatLabel  SHUFFLE:${if (st.shuffleEnabled) "ON" else "OFF"}", color = TuiFaint)
            }
        }

        if (queue.isEmpty()) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.padding(40.dp))
                TuiText("queue is empty", color = TuiFaint)
                TuiText("play something from LIBRARY or YOUTUBE", color = TuiDim, size = 10)
            }
            return@Column
        }

        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(queue.size, key = { index -> "q$index" }) { index ->
                val song = queue[index]
                val isCurrent = index == st.currentIndex
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectableRow(
                        text = song.title,
                        selected = isCurrent,
                        onClick = { vm.jumpTo(index) },
                        modifier = Modifier.weight(1f),
                        meta = formatTime(song.durationMs),
                    )
                    if (!isCurrent) {
                        TuiText(
                            "x",
                            modifier = Modifier
                                .padding(horizontal = 6.dp)
                                .clickable { vm.removeFromQueue(index) },
                            color = TuiDim,
                        )
                    }
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}
