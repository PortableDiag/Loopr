# Changelog

## 1.4 — 2026-07-10
- Fixed ⏭ / ⏮ dead-ending on a single file when a video is **opened from another app** (file manager, Downloads, etc.). Folder queueing needs media access, which an externally‑launched app usually hasn't been granted — so it now **requests that permission on launch** and, once granted, upgrades the queue to the whole folder in place (keeping the current file and position). Without the grant it still plays the single file as before.

## 1.3 — 2026-07-08
- Fixed folder queueing when a video is opened from **Downloads** (or any Storage‑Access‑Framework source): the file is now located by display name, so ⏮ / ⏭ traverse the folder instead of falling back to a single‑item queue.

## 1.2 — 2026-07-08
- Opening a video from a file manager or another app now enqueues the **whole folder** (sorted by name), so ⏮ / ⏭ move through the other videos in that folder instead of dead‑ending on a single file. Falls back to just the opened file when media permission hasn't been granted or the source can't be located.
- **Repeat One** now restarts the current video on ⏮ / ⏭, matching how the mode already behaves at the end of a video.

## 1.1 — 2026-06-29
- Added an optional **multiple‑players** mode: each picked video can open in its own instance so several videos can play at once. Off by default; backgrounded players keep playing while it's on.

## 1.0 — 2026-06-28
First release.

- Material 3 video library (MediaStore) with async thumbnails, durations and sizes
- Light / dark / system theme, defaulting to dark
- ExoPlayer (Media3) playback with a custom control overlay
- Gapless single‑video loop and gapless **A–B segment loop** with on‑seekbar markers
- Library play **queue** with previous/next, **Repeat** (off/one/all) and **Shuffle**, both persisted
- **Picture‑in‑Picture** window mode with play/pause action and auto‑enter on Home
- Gestures: tap to toggle controls, double‑tap to seek, swipe for brightness/volume, swipe to scrub
- Speed, mute, resize (fit/crop/stretch) and rotate controls
