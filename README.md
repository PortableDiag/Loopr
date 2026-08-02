# Loopr

A modern, dark‑first **video player for Android** built around one thing most players do badly: **looping**. Loop a whole video or a precise **A–B segment** with no noticeable gap, pop the video out into a **floating window**, and browse everything on your device from a clean Material 3 library.

> Package: `com.loopr.player` · minSdk 26 (Android 8.0) · targetSdk 35

---

## Features

### Looping (the point of the app)
- **Gapless single‑video loop** — repeats the current video seamlessly (`REPEAT_MODE_ONE`, same decoder, no re‑buffer at the seam).
- **Gapless A–B loop** — set point **A** and point **B** and Loopr loops just that segment, smoothly, using a clipped media source rather than a stop‑and‑seek. A/B markers and the looped region are drawn right on the seek bar.

### Playback & queue
- Plays your whole library as a **queue** with **⏮ / ⏭** previous/next.
- **Opening a video from a file manager or another app** enqueues the **whole containing folder** (sorted by name), so ⏮ / ⏭ traverse the folder instead of stopping at the single file — no matter how the launching app references the file (a MediaStore item, a Storage‑Access‑Framework/documents URI, or a file path). Falls back to single‑file playback when the source can't be resolved.
- **Repeat** mode cycles **Off → One → All**, and a **Shuffle** toggle — both remembered between sessions.
- Optional **multiple‑players** mode — open several videos in their own instances and play them at once (off by default).
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
- **A–B segment:** setting A and B replaces the current queue item with a `ClippingMediaSource` (via `MediaItem.ClippingConfiguration`) bounded to `[A, B]` and loops *that*. Because the clip is the looped unit, you don't get the stutter of a manual "seek back to A" on every cycle. Clearing A–B restores the full‑length item in place, preserving your spot in the queue.

---

## Roadmap ideas
- Per‑folder browsing in the library grid
- Playback position memory / resume
- Subtitle (SRT) support
- Background audio mode

---

## License

MIT — see [LICENSE](LICENSE).
