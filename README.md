# Loopr

A modern, dark‑first **video player for Android** built around one thing most players do badly: **looping**. Loop a whole video seamlessly, or a precise **A–B segment**, pop the video out into a **floating window**, and browse everything on your device from a clean Material 3 library.

> Package: `com.loopr.player` · minSdk 26 (Android 8.0) · targetSdk 35

---

## Features

### Looping (the point of the app)
- **Gapless single‑video loop** — repeats the current video seamlessly (`REPEAT_MODE_ONE`, same decoder, no re‑buffer at the seam).
- **A–B loop** — set point **A** and point **B** and Loopr loops just that segment, jumping back to A as soon as playback passes B. A/B markers and the looped region are drawn right on the seek bar.

### Playback & queue
- Plays your whole library as a **queue** with **⏮ / ⏭** previous/next.
- **Opening a video from a file manager or another app** enqueues the **whole containing folder** (sorted by name), so ⏮ / ⏭ traverse the folder instead of stopping at the single file — no matter how the launching app references the file (a MediaStore item, a Storage‑Access‑Framework/documents URI, the app's own FileProvider, or a file path). A launching app can also hand the folder over directly by attaching it as intent `ClipData`, which covers folders MediaStore doesn't index at all (e.g. under a `.nomedia`) and needs no media permission. Loopr plays exactly the list it is handed, so a sending app must attach the file's **real containing folder** — never a cache or staging directory it copied the file into, whose other entries aren't siblings at all. Falls back to single‑file playback — and says why — when the folder can't be read.
- **Repeat** mode cycles **Off → One → All**, and a **Shuffle** toggle — both remembered between sessions.
- Optional **multiple‑players** mode — open several videos in their own instances and play them at once (off by default). Applies to videos opened from other apps too: with the mode on, a video handed over by a file manager gets its own window instead of taking over the player already running, so a video floating in PiP keeps playing.
- Variable **speed** (0.25×–2×), **mute**, **resize** (Fit / Crop / Stretch) and **rotate**.

### Window mode
- **Picture‑in‑Picture** — float the video over other apps, with the correct aspect ratio and a play/pause action. Auto‑enters PiP when you press Home mid‑playback.

### Gestures & controls
- **Tap** to show/hide controls (auto‑hide while playing).
- **Double‑tap** left/right edge to seek ∓10s; double‑tap centre to play/pause.
- **Vertical swipe** — brightness on the left half, volume on the right.
- **Horizontal swipe** to scrub, with a live position read‑out.

### Library & theming
- Grid of all device videos via **MediaStore**, with async thumbnails, durations and file sizes.
- Runtime media permission flow.
- **Material 3** UI with **light / dark / follow‑system** themes — **defaults to dark**.

---

## Screenshots

_Add screenshots here (`docs/` folder) — library grid, player with A–B markers, and PiP window._

---

## Tech stack

| Area | Choice |
|------|--------|
| Language | Kotlin |
| Media | [AndroidX Media3 / ExoPlayer](https://developer.android.com/media/media3) `1.4.1` |
| UI | Material 3 (Views + ViewBinding), ConstraintLayout, RecyclerView |
| Async | Kotlin Coroutines |
| Build | Gradle 8.11.1, Android Gradle Plugin 8.7.3, JDK 17 |

No third‑party analytics, ads, or network calls — Loopr only reads the videos already on your device.

---

## Building

### Requirements
- JDK 17
- Android SDK with **platform 35** and **build‑tools 34.0.0**
- A `local.properties` pointing at your SDK:
  ```properties
  sdk.dir=/path/to/Android/Sdk
  ```

### Debug build (no signing needed)
```bash
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

### Signed release build
1. Generate a keystore:
   ```bash
   keytool -genkeypair -v -keystore loopr-release.jks -alias loopr \
       -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Copy `keystore.properties.example` to `keystore.properties` and fill in your passwords.
3. Build:
   ```bash
   ./gradlew assembleRelease
   # output: app/build/outputs/apk/release/app-release.apk
   ```

> `keystore.properties` and `*.jks` are git‑ignored on purpose — keep your signing secrets out of version control.

### Install
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## Project structure

```
app/src/main/
├── java/com/loopr/player/
│   ├── MainActivity.kt      # video library: MediaStore query, grid, permissions, theme
│   ├── PlayerActivity.kt    # ExoPlayer, custom controls, A–B loop, queue, PiP, gestures
│   ├── VideoAdapter.kt      # RecyclerView grid adapter
│   ├── VideoItem.kt         # video model
│   ├── ThumbnailLoader.kt   # async thumbnail loading + LRU cache
│   ├── AbSeekBar.kt         # SeekBar that draws the A/B markers and loop region
│   └── ThemeManager.kt      # light/dark/system theme persistence
└── res/                     # layouts, Material 3 themes, vector icons, adaptive launcher
```

---

## How looping works

- **Whole video:** the Repeat chip sets ExoPlayer's `REPEAT_MODE_ONE`, which loops the item without tearing down the decoder — so the seam is effectively gapless.
- **A–B segment:** setting A and B starts a watcher that polls the playback position every 30 ms and seeks back to A the moment it passes B (or the video ends). The queue item itself is left alone — nothing is rebuilt or replaced — so clearing A–B is instant and your place in the queue is never disturbed. The poll pauses while you're scrubbing. A–B belongs to the video it was set on: moving to another one clears it, and B must come after A.

---

## Roadmap ideas
- Per‑folder browsing in the library grid
- Playback position memory / resume
- Subtitle (SRT) support
- Background audio mode

---

## License

MIT — see [LICENSE](LICENSE).
