# Changelog

## 1.6 — 2026-08-03
- Fixed ⏭ / ⏮ **still** dead-ending on a single video when opened from a file manager. Two causes, both of which left a one-item queue that felt like being stuck on repeat:
  - The file manager's own `content://…fileprovider/…` URI answers neither a MediaStore id nor a file path, so Loopr never learned which folder the video was in and skipped folder queueing entirely. It now reads the real path back from the opened file descriptor, which works for any provider serving a real file.
  - Choosing **"Allow limited access"** for the media permission (Android 14+) still reports as granted, but MediaStore then only exposes the handful of hand-picked items and blanks their paths — so no folder could be enumerated, not even the one playing.
- A launching app can now hand over the folder directly (as intent `ClipData`), so ⏭ / ⏮ traverse folders MediaStore doesn't index **at all** — anything under a `.nomedia`, or files copied in since the last scan. This path needs no media permission whatsoever.
- ⏭ / ⏮ on a folder that couldn't be read now say so instead of silently restarting the same video.

## 1.5 — 2026-08-02
- Fixed ⏭ / ⏮ dead-ending on a single video when opened from a **file manager** (Sift, Files by Google, etc.). Folder queueing only recognised plain MediaStore and `file://` launches; the Storage-Access-Framework "documents" URIs that most file managers actually send fell through to a one-item queue — which, looping under Repeat, felt like being **stuck on repeat-one** with no way out. Loopr now decodes those URIs (ExternalStorageProvider volume paths, the Downloads provider's `raw:` paths, and the media-documents `video:id` form) so ⏭ / ⏮ traverse the whole folder.
- Added a path-based fallback: when the opened file isn't in MediaStore yet, its folder siblings are found by directory and the file is spliced in by name, so it still plays and navigates. Sibling matching escapes `LIKE` metacharacters and normalises `/sdcard`-style path aliases so it can't spill into similarly-named folders.
- Hardened folder resolution against wrong-folder matches: the opened file is now located by its strong keys (MediaStore id, then absolute path) before falling back to a display-name match, and that name match is only trusted when it's **unambiguous** — so a file manager's temporary copy of a streamed/remote file (SMB/FTP) can't coincidentally resolve to a same-named local video and queue the wrong folder. Ambiguous or unlocatable launches play as a single file.

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
