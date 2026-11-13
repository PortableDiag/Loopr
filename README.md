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
- **Picture‑in‑Picture** — float the video over other apps, with the correct aspect ratio and **⏮ / play‑pause / ⏭** controls, so you can skip through the folder without expanding back to full screen. Auto‑enters PiP when you press Home mid‑playback.
- **Floating windows** (optional) — Android allows only **one** PiP window per device, so for several videos at once Loopr draws its own: up to **three** overlay windows, dragged where you want them and sized by the grip in the bottom‑right corner — up to 95% of the screen. **Pinch zooms the picture** inside a window (1×–5×, double‑tap for 2× or back), the way it does full screen, and while zoomed a drag moves the picture rather than the window. Each carries its own folder queue (⏮ / ⏭ traverse it) and **its own A–B loop**, so a segment you set before floating keeps looping in the window, marked with an **A–B** badge. Tap ⛶ and it goes back to full screen with the queue, position, speed, mute, resize and A–B intact. One notification covers the lot, with **Close all**. If a window ever stops showing video while the player carries on — a picture can die without anything reporting an error — Loopr notices and works back through it: it re‑seats the surface, then rebuilds the window from scratch, then reloads the video where it was, and closes the window with a reason only if none of that works. It watches three things: the frames being decoded, the frames actually reaching the screen, and **whether playback is running at all** — a window can fail in any of those three ways and the other two signals can't see it. If a video does fail, Loopr retries it where it stopped, then moves past it if it fails again, rather than leaving the window stranded. And because a floating window outlives the activity that opened it, the only thing holding one is a background process — so the windows are written down as you use them, and **put back automatically if the system kills Loopr**, at the size and place you left them and carrying on from where they got to. Every player Loopr opens reads ahead by at most 16 MB, because four of them (three windows plus full screen) share one app's heap and ExoPlayer's own default target — 128 MB each — puts a high‑bitrate video over the limit, which kills the process and every window with it. And because the read permission a file manager grants belongs to the activity that received it — which finishes the moment the video is floated — that permission is **passed on to the service** with the video, and back again on ⛶; without it a window fails the next time it re-opens a file, which looks like a folder bug and is one. Needs the "Display over other apps" permission; without it Loopr falls back to the system PiP window and says so.

### Gestures & controls
- **Tap** to show/hide controls (auto‑hide while playing).
- **Double‑tap** left/right edge to seek ∓10s; double‑tap centre to play/pause.
- **Vertical swipe** — brightness on the left half, volume on the right.
- **Horizontal swipe** to scrub, with a live position read‑out.

### Library & theming
- Grid of all device videos via **MediaStore**, with async thumbnails, durations and file sizes.
- Runtime media permission flow.
- **Material 3** UI with **light / dark / follow‑system** themes — **defaults to dark**.

### 3D models (VRM, GLB, glTF)
**⋮ › 3D models** opens VRM avatars (0.x and 1.0), GLB and glTF files in a full-screen viewer drawn by Google's Filament renderer — the rest pose with the model's own textures, alpha and materials (unlit/MToon fallback and PBR). Not drawn, as on Loopr Desktop: animation, spring bones, expressions, outlines.
- **Drag** turns it, **pinch** zooms, **two fingers** pan, **double tap** resets, a **tap** hides the controls. Front / Back / Side / Top, **Spin**, a **☀ Light** slider (lights the model, not the backdrop) and a Dark / Grey / Light background.
- **⏮ / ⏭ step through the folder**, in natural name order, like videos.
- Android's media permission doesn't reach model files, so Loopr sees them in three places: **its own folder** (`Android/data/com.loopr.player/files/Models` — `adb push` there, no permission needed), **folders you add** (*Add a folder*, Android's own picker; kept across restarts; searched four levels deep), and **files you open** (*Open a file*). A file manager can also open a `.vrm` / `.glb` / `.gltf` straight into the viewer.
- The grid shows each VRM's **own embedded thumbnail**.
- A `.gltf` with separate `.bin` / texture files needs them beside it in Loopr's folder or an added folder; a `.glb` or `.vrm` carries everything.

### Local API (off until you turn it on)
Scripts and agents on the phone can drive Loopr: browse the library, open videos full screen or floating, play, pause, seek, skip, set the A–B loop, change speed, mute, resize, zoom, move and resize floating windows, open and turn 3D models, **see what is on screen** (`loopr.screenshot`), and change settings — everything the app's own buttons do. The 14 tool names and the `loopr.model` actions match Loopr Desktop's, so one agent prompt drives both. **⋮ › Local API** switches it on and makes the keys.

- **This phone only.** It listens on `127.0.0.1:8793` and refuses any request whose `Host` is not the phone's own (so a web page can't reach it by DNS rebinding). From a computer: `adb forward tcp:8793 tcp:8793`.
- **A key per caller, every tool off until you switch it on.** A new key can do nothing. You tick what it may do — each of the 14 tools is its own switch — and can change that or revoke the key at any time; a change applies to the next call. Loopr keeps only a fingerprint of a key, so it is shown once.
- **Every key has a recent-calls list** and a count, and is capped at 240 calls a minute.
- **One gate, three ways in:** REST for scripts, MCP (`POST /mcp`) for agents, and `POST /api/tools/{name}` for either. All three run the same tool through the same check. Tool names are `loopr.*` (MCP spells them `loopr_*`) so they stay unambiguous when PhoneAPI lists every app's tools together.
- **A refusal says why and what's left** — e.g. floating needs Android's "Display over other apps", three windows is the most at once, B has to come after A. Only you can grant Android permissions; `GET /api/settings` says which Loopr holds.

```bash
KEY=lp_…                                                   # ⋮ › Local API › New key
curl -s -H "X-API-Key: $KEY" 127.0.0.1:8793/api/docs        # the full reference
curl -s -H "X-API-Key: $KEY" "127.0.0.1:8793/api/videos?q=cat"
curl -s -H "X-API-Key: $KEY" -X POST 127.0.0.1:8793/api/open -d '{"video_id":42,"queue":"folder","window":"floating"}'
curl -s -H "X-API-Key: $KEY" -X PUT 127.0.0.1:8793/api/players/1/ab -d '{"a_ms":5000,"b_ms":9000}'
curl -s -H "X-API-Key: $KEY" -X PATCH 127.0.0.1:8793/api/players/1 -d '{"speed":0.5}'
curl -s -H "X-API-Key: $KEY" "127.0.0.1:8793/api/players/1/screenshot?what=window" -o shot.jpg
# A VRM avatar: open with its folder as the queue, three-quarter view, slow spin
curl -s -H "X-API-Key: $KEY" -X POST 127.0.0.1:8793/api/open -d '{"path":"/sdcard/Android/data/com.loopr.player/files/Models/me.vrm"}'
curl -s -H "X-API-Key: $KEY" -X POST 127.0.0.1:8793/api/models/2 -d '{"action":"rotate_to","yaw":35,"pitch":10}'
curl -s -H "X-API-Key: $KEY" -X POST 127.0.0.1:8793/api/models/2 -d '{"action":"spin","speed":20}'
```

- **Models:** `GET /api/videos?kind=model` lists them (each library entry has a `kind`); `loopr.open` with a model's id or path opens the model view; `GET /api/models/{id}` reports its format, title, authors, licence, triangles, size in metres, camera, spin, light and background, with `loading` true until it is drawn. `loopr.model` actions: `rotate_to` / `rotate_by` (yaw 0 is the front, pitch + looks from above), `zoom` (1 frames it, 0.05–20), `pan` (model radii), `view`, `reset`, `spin`, `light` (0–3), `background`, `next` / `previous` / `jump`. A model view shares the players' ids; a video verb on it is a 400 that names `loopr.model`.
- **Screenshots:** `video` is the picture alone, `window` the screen as drawn, controls and all; only while it is on screen. Over MCP the result is an image block, so an agent sees it directly.
- **Differences from Loopr Desktop:** models open full screen only (no floating model windows); there is no frame step, volume level or brightness, because the phone app has none — the API does what the app does.

Port 8793 is clear of the other in‑house local APIs on the phone (Lull 8787, TextX 8788, DialX 8789, NotifX 8790, PermaFrost and Sift 8791, KJVBible 8794, ClipX 8795, ThereminX 8796, AppManager 8797, GhostUser and VolumePerApp 8799) and of TrellisBridge on 8792 on the computer, where `adb forward` needs the same number free. It can be changed on the same screen.

**PhoneAPI** asks for its own key on Loopr's screen *Let PhoneAPI use Loopr?*: the same tool switches, every one off, and only PhoneAPI's release build is answered. Allow mints a key named PhoneAPI (replacing an older one) and a proof key, and switches the API on; while PhoneAPI holds it, every answer to a request carrying `X-PhoneAPI-Challenge` carries `X-PhoneAPI-Proof`, so PhoneAPI knows it is talking to Loopr. Revoking the PhoneAPI key, uninstalling PhoneAPI, or PhoneAPI no longer being signed by its release key unlinks it.

Android 15 won't let an app start a foreground service from the background just because it holds "Display over other apps" — it also needs a window showing — so the API is switched on from its screen, and comes back by itself after a reboot if it was on.

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

### Instrumented tests
The floating windows are overlays drawn by a service, so no accessibility dump or `adb shell input`
can reach them — their drag and resize are covered by an on‑device test that injects real
multi‑pointer events and reads the window geometry back from `dumpsys window`:
```bash
adb shell appops set com.loopr.player SYSTEM_ALERT_WINDOW allow   # the test needs the permission
./gradlew connectedDebugAndroidTest
```

### Unit tests
The local API's routing, auth, tool switches, rate cap, MCP and error shapes run on the JVM against a
fake backend, including a walk of every route `GET /api/docs` advertises:
```bash
./gradlew testDebugUnitTest
```
A debug build can mint a key from adb (it logs the key, which is why release builds don't have it).
The broadcast arrives in the background, which Android 15 won't start a foreground service from, so
allowlist Loopr for a minute first:
```bash
adb shell cmd deviceidle tempwhitelist -d 60000 com.loopr.player
adb shell am broadcast -n com.loopr.player/.api.DebugApiKeyReceiver --es name test --es tools all
adb logcat -d -s Loopr.ApiDebug        # key, port, tools
```

---

## Project structure

```
app/src/main/
├── java/com/loopr/player/
│   ├── MainActivity.kt      # video library: MediaStore query, grid, permissions, theme
│   ├── PlayerActivity.kt    # ExoPlayer, custom controls, A–B loop, queue, PiP, gestures
│   ├── FloatingPlayerService.kt  # foreground service owning up to 3 floating windows
│   ├── FloatingWindow.kt    # one overlay window: own player, queue, A–B, drag/zoom/resize
│   ├── FloatingHandoff.kt   # state passed between the activity and a floating window
│   ├── FloatingState.kt     # the open windows on disk, so a killed process can restore them
│   ├── NotificationAccess.kt # notification permission — without it Android drops every toast too
│   ├── PlayerBuffers.kt     # every player's buffer cap: four of them share one 256 MB heap
│   ├── VideoAdapter.kt      # RecyclerView grid adapter
│   ├── VideoItem.kt         # video model
│   ├── ThumbnailLoader.kt   # async thumbnail loading + LRU cache
│   ├── AbSeekBar.kt         # SeekBar that draws the A/B markers and loop region
│   ├── ThemeManager.kt      # light/dark/system theme persistence
│   ├── VideoLibrary.kt      # the MediaStore query the grid and the API share
│   ├── Players.kt           # every open player, numbered, and what the API may ask of one
│   ├── Screenshot.kt        # loopr.screenshot: PixelCopy of a SurfaceView / window, or an overlay drawn by hand
│   ├── ModelsActivity.kt    # ⋮ › 3D models: the model grid, add a folder, open a file
│   ├── ModelViewerActivity.kt # one model full screen: gestures, controls, folder queue
│   ├── model/
│   │   ├── ModelMeta.kt     # what a VRM/GLB/glTF says about itself (pure Kotlin, JVM-tested)
│   │   ├── ModelCamera.kt   # the orbit camera in Loopr Desktop's units (JVM-tested)
│   │   ├── ModelScene.kt    # Filament: engine, lights, async texture load, render on change
│   │   ├── ModelLibrary.kt  # Loopr's folder, granted folders and files; thumbnails; natural order
│   │   └── Models.kt        # the open model views, for the API
│   └── api/                 # the local API — off unless you switch it on
│       ├── LocalApi.kt      # routes, auth, tool gates, rate cap, MCP, /api/docs (no Android types)
│       ├── Tools.kt         # the 14 tools, each its own switch
│       ├── AndroidBackend.kt # what each tool does on the phone
│       ├── ApiKey.kt · ApiKeys.kt # keys as hashes, with their tools and recent calls
│       ├── ApiService.kt    # the foreground service that keeps it listening; boot receiver
│       ├── ApiActivity.kt   # ⋮ › Local API
│       └── Http.kt · ApiServer.kt · MainThread.kt # the shared in-house HTTP layer
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
