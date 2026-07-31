# tplay

> a terminal-inspired music player for android. dear music, thanks.

`tplay` is a TUI/ASCII-styled Android music player that plays **offline songs**
from your device and **online songs streamed from YouTube**, all wrapped in a
hacker-terminal aesthetic (JetBrains Mono, box-drawing frames, ASCII-art album
covers, `[====>-----]` progress bars).

Inspired by the look and feel of the DMT player (`imjyotiraditya/dmt`).

## features

- **local library** — scans MediaStore for audio files (title/artist/album/duration)
- **youtube search & streaming** — search, resolve the best audio stream, play it
  (via NewPipeExtractor + Media3, no API keys)
- **ascii cover art** — album art (or YouTube thumbnail) is rendered as a
  colored ASCII grid with a light sweep while playing; tracks without art get a
  generated ASCII pattern
- **terminal UI** — monospace font, box-drawing panels, `>` prompt, status-line
  tabs (`LIB / YT / PL / QUE / CFG`), full-screen player
- **playlists** — create/delete playlists, save tracks (local + youtube), play them
- **queue** — view current queue, jump to track, remove items
- **full media controls** — shuffle, repeat (off/all/one), playback speed
  (0.75x–2x), sleep timer (15/30/60 min), seek
- **background playback** — Media3 `MediaSessionService`, lockscreen/media
  notification, audio-focus handling, resume on audio becoming noisy

## screenshots

```
┌─ LIBRARY ──────────────────────────┐
│  42 tracks                         │
│  ▸ Daft Punk - Get Lucky     3:30  │
│    Tool - Lateralus          9:24  │
│    ...                              │
└────────────────────────────────────┘
        [ LIB ] [ YT ] [ PL ] [ QUE ] [ CFG ]
```

## building

Requires JDK 17 and an Android SDK (compileSdk 35, minSdk 26).

```bash
./gradlew :app:assembleDebug
# or the optimized, minified release:
./gradlew :app:assembleRelease
```

The release build enables R8 minification + resource shrinking and lands at
~4 MB (the debug APK is ~16 MB). Without a `keystore.properties` the release
is signed with the debug key so it can be installed directly.

## stack

- Kotlin, Jetpack Compose (Material3, custom TUI theme)
- Media3 ExoPlayer + MediaSessionService (offline + streaming)
- NewPipeExtractor v0.24.8 (YouTube search / stream extraction, via JitPack)
- Room (playlists) · DataStore (settings) · OkHttp (extractor + artwork)

## optimizations

### playback / battery
- **audio offload** enabled (`TrackSelectionParameters.AudioOffloadPreferences`,
  `AUDIO_OFFLOAD_MODE_ENABLED`) so supported tracks decode on the DSP instead of
  the CPU
- tuned `DefaultLoadControl` (50 s min / 100 s max buffer, fast rebuffer resume)
- `WAKE_MODE_LOCAL` — wakelock only while actively playing
- `handleAudioBecomingNoisy` + audio-focus handling (pause on unplug/duck)
- sleep timer pauses playback (no idle CPU burn)
- video renderers disabled (`EXTENSION_RENDERER_MODE_OFF`), audio-only pipeline
  for streams

### app size / runtime
- R8 full minification + resource shrink → ~4 MB release APK
- no DI framework, no navigation library, no image-loading framework — minimal
  dependency surface

### UI / memory
- `LazyColumn` with stable keys everywhere; list state hoisted
- ASCII art + bitmaps cached in a size-bounded `LruCache`, scaled downsampled
  decode (never full-res), disk cache in `cacheDir/art`
- all I/O (scan, extraction, art) off the main thread; single `StateFlow` for
  player state (single state + actions, no magic)
- infinite ASCII "wave" animation only while playing (paused → static cover)

## layout

```
app/src/main/java/dev/tplay/
├── TermPlayApp.kt            Application + AppContainer wiring
├── MainActivity.kt
├── core/                     ASCII cover renderer, format utils, art cache
├── data/
│   ├── local/                MediaStore scanner, Room DB (playlists)
│   ├── prefs/                DataStore settings
│   ├── model/Song.kt         unified model for local + youtube tracks
│   └── youtube/              OkHttp downloader + NewPipeExtractor repo
├── player/
│   ├── PlayerService.kt      MediaSessionService + optimized ExoPlayer
│   └── PlayerConnection.kt   MediaController bridge + PlayerUiState flow
└── ui/
    ├── theme/                TUI colors, JetBrains Mono typography
    ├── components/           AsciiBox, TuiIconButton, TuiProgressBar, ...
    ├── MainViewModel.kt      single state + actions
    └── screens/              library / youtube / playlists / queue / player / settings
```

## notes

- YouTube playback resolves a fresh audio stream URL per track at play time
  (URLs expire) — a small "resolving stream..." indicator shows while this
  happens. Nothing is saved to disk; the library tab is your local files.
- streaming is best-effort and depends on public YouTube endpoints; if a track
  is age-restricted or unavailable, it is skipped gracefully.
- `youtube` search + streaming respects YouTube's Terms of Service as used by
  the NewPipe project.

## license

GPL-3.0
