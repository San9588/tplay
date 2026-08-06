package dev.tplay.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tplay.data.model.YtQuality
import dev.tplay.ui.MainViewModel
import dev.tplay.ui.components.AsciiBox
import dev.tplay.ui.components.SelectableRow
import dev.tplay.ui.components.TuiIconButton
import dev.tplay.ui.components.TuiText
import dev.tplay.ui.theme.LocalTuiGreen
import dev.tplay.ui.theme.TuiDim
import dev.tplay.ui.theme.TuiFaint
import dev.tplay.ui.theme.TuiFg

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val playerState by vm.playerState.collectAsState()
    val green = LocalTuiGreen.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
    ) {
        AsciiBox(title = " SETTINGS ") {
            TuiText("tplay v1.0", color = TuiDim)
        }

        Spacer(Modifier.padding(4.dp))

        AsciiBox(title = " PLAYBACK ") {
            val speedLabel = "%.2fx".format(playerState.speed)
            Column {
                TuiText("speed  [$speedLabel]", color = TuiFg)
                Row {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                        TuiIconButton(
                            label = if (s == playerState.speed) "[${s}x]" else "${s}x",
                            onClick = { vm.setSpeed(s) },
                            accent = s == playerState.speed,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.padding(4.dp))

        AsciiBox(title = " YT QUALITY ") {
            Column {
                TuiText("wifi   max quality", color = TuiFg)
                Row {
                    YtQuality.entries.forEach { q ->
                        TuiIconButton(
                            label = if (settings.wifiQuality == q.name) "[${q.label}]" else q.label,
                            onClick = { vm.setWifiQuality(q) },
                            accent = settings.wifiQuality == q.name,
                        )
                    }
                }
                Spacer(Modifier.padding(3.dp))
                TuiText("mobile max quality", color = TuiFg)
                Row {
                    YtQuality.entries.forEach { q ->
                        TuiIconButton(
                            label = if (settings.mobileQuality == q.name) "[${q.label}]" else q.label,
                            onClick = { vm.setMobileQuality(q) },
                            accent = settings.mobileQuality == q.name,
                        )
                    }
                }
                TuiText("falls back down, then up if missing", color = TuiFaint, size = 9)
            }
        }

        Spacer(Modifier.padding(4.dp))

        AsciiBox(title = " THEME ") {
            Column {
                TuiText("accent  [system]", color = TuiFg)
                TuiText("system uses the android wallpaper accent colour", color = TuiFaint, size = 10)
                Row {
                    TuiIconButton(
                        label = if (settings.systemAccent) "[system]" else "system",
                        onClick = { vm.setSystemAccent(true) },
                        accent = settings.systemAccent,
                    )
                    Spacer(Modifier.padding(4.dp))
                    TuiIconButton(
                        label = if (!settings.systemAccent) "[default]" else "default",
                        onClick = { vm.setSystemAccent(false) },
                        accent = !settings.systemAccent,
                    )
                }
            }
        }

        Spacer(Modifier.padding(4.dp))

        AsciiBox(title = " VBASS FREQUENCY ") {
            Row {
                listOf(80, 90, 100, 120).forEach { hz ->
                    TuiIconButton(
                        label = if (settings.vbassFreq == hz) "[${hz}Hz]" else "${hz}Hz",
                        onClick = { vm.setVbassFreq(hz) },
                        accent = settings.vbassFreq == hz,
                    )
                }
            }
        }

        Spacer(Modifier.padding(4.dp))

        AsciiBox(title = " COVER MODE ") {
            Row {
                listOf("ascii", "normal").forEach { mode ->
                    TuiIconButton(
                        label = if (settings.coverMode == mode) "[$mode]" else mode,
                        onClick = { vm.setCoverMode(mode) },
                        accent = settings.coverMode == mode,
                    )
                }
            }
        }

        Spacer(Modifier.padding(4.dp))

        AsciiBox(title = " SEARCH MODE ") {
            Row {
                listOf("mix", "vibe", "singer", "suggest").forEach { mode ->
                    TuiIconButton(
                        label = if (settings.searchMode == mode) "[$mode]" else mode,
                        onClick = { vm.setSearchMode(mode) },
                        accent = settings.searchMode == mode,
                    )
                }
            }
        }

        Spacer(Modifier.padding(4.dp))

        AsciiBox(title = " ASCII COLS ") {
            Row {
                listOf(88, 96, 120).forEach { c ->
                    TuiIconButton(
                        label = if (settings.asciiCols == c) "[$c]" else "$c",
                        onClick = { vm.setAsciiCols(c) },
                        accent = settings.asciiCols == c,
                    )
                }
            }
        }

        Spacer(Modifier.padding(4.dp))

        AsciiBox(title = " SLEEP TIMER ") {
            val remaining = playerState.sleepRemainingMs
            Column {
                if (remaining > 0) {
                    TuiText("sleeping in ${remaining / 60_000} min", color = green)
                }
                Row {
                    listOf(15, 30, 60).forEach { m ->
                        TuiIconButton("$m min", onClick = { vm.startSleep(m) })
                    }
                    TuiIconButton("off", onClick = { vm.cancelSleep() })
                }
            }
        }

        Spacer(Modifier.padding(4.dp))

        AsciiBox(title = " SOURCES ") {
            Column {
                TuiText("LIBRARY   local media store scan", color = TuiFg)
                TuiText("YOUTUBE   streamed via extractor", color = TuiFg)
                TuiText("", color = TuiFaint)
                TuiText("streams are not downloaded/cached to disk", color = TuiFaint, size = 10)
            }
        }

        Spacer(Modifier.padding(16.dp))
    }
}
