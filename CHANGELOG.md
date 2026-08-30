# Changelog

## 1.14 — 2026-08-29
- **Loopr now asks for notification permission instead of quietly going without it.** It was declared but never actually requested, so on a fresh install Android denied it by default — and that does more than hide the floating-windows notification: **Android throws away an app's on-screen messages entirely when its notifications are off**. Every explanation Loopr gives you — why a window closed, why a folder couldn't be read — was being discarded before it reached the screen. Loopr asks once, when you turn floating windows on, and explains why first; say no and it won't ask again.

## 1.13 — 2026-08-29
- **Fixed floating windows vanishing while you're in another app.** They weren't closing themselves — the whole of Loopr was being shut down. A floating window outlives the activity that opened it, so the only thing holding one is a background process, and when the system reclaims that process every window goes at once, in silence, taking the queue, the position and the A–B points with it because they only ever existed in memory. Loopr now writes the open windows down as you use them and **puts them back when the system restarts it**: same size, same place on screen, same video, carrying on from where it got to.
  - It gives up after three restores in quick succession and says so, rather than fighting the system for your battery — if something is determined to close Loopr, rebuilding the same process over and over is worse than leaving it shut.
  - Windows you close yourself stay closed; only ones taken away from you come back.
  - This covers the process dying for any reason — memory pressure, or a crash. Android shows you nothing in either case, which is why the two are indistinguishable from the outside.
- **A window that fails to go back up after a rebuild is now closed properly** instead of leaving a player running with nothing on screen — one more way a window could disappear without saying anything.
- **Every reason Loopr closes a window is now written to the log as well as shown.** Android suppresses an app's toasts when its notifications are turned off, so on a phone where that's the case Loopr's explanations were being swallowed and windows appeared to vanish for no reason. Turn the diagnostics on with `adb shell setprop log.tag.LooprQueue DEBUG`.

## 1.12 — 2026-08-18
- **Fixed the floating window that stops playing and turns black.** Root cause found, and it was never the graphics: when a video hits a playback error ExoPlayer parks itself in an idle state, and an idle player does not restart on its own. Loopr moved the queue on to the next video but never told the player to load it — so the window sat there with no picture, no sound, and a play button that did nothing, which is exactly what you had to close and reopen the window to escape. Every recovery path now reloads the video properly.
  - A video that fails is **retried where it stopped** first, because a momentary fault shouldn't cost you the video you were watching. Only if it fails a second time does Loopr move past it, and it says so and closes the window only once nothing in the queue will play.
  - A decoder taken back by the system — which is what happens when something in the foreground wants one — is now retried rather than treated as the device being out of decoders.
- **The stall watchdog can now see playback stopping.** Both of its existing signals only ran while the player claimed to be playing, so the state this failure actually left behind switched the whole watchdog off: no recovery, no message, and not one line in the log across two releases built to catch it. It now also watches for a player that has been told to play and isn't, and works back through reloading the video, reloading it where it was, and moving past it. The diagnostic names which of the three signals tripped — `signal=playback`, `signal=composite`, `signal=frames`.
- The same defect is fixed **full screen**: a video that failed mid-queue left a black screen with dead controls there too.
- The play button now recovers a stopped window instead of doing nothing.
- Covered by a test that reproduces the reported symptom — a window whose video genuinely fails must go on showing moving video — which fails against 1.11 and passes here.

## 1.11 — 2026-08-16
- A floating window that goes black is now noticed **even when the video is still being decoded perfectly**. 1.10 watched the frames the player handed to the window, which is the wrong question: those frames can keep flowing into a window that has stopped putting anything on screen, so the black window 1.10 was built to catch was exactly the one it couldn't see. Each window now also watches frames actually reaching the screen, and the two signals together cover both halves of the failure.
- Added a recovery step for that case. Handing the surface back doesn't help a window whose drawing is itself dead, so before giving up Loopr now takes the window down and puts it straight back up — rebuilt from scratch, with the video carrying on from where it was.
- The diagnostic now says **which** signal tripped (`signal=composite` for a dead picture under a healthy player, `signal=frames` for playback itself stopping), which is the detail that names the underlying bug. Turn it on with `adb shell setprop log.tag.LooprQueue DEBUG`.
- **Still a safety net, not a root-cause fix.** The failure has never been reproduced away from the reporting device — this release closes the blind spot that stopped 1.10 from seeing it, and makes the next occurrence say what it was.

## 1.10 — 2026-08-15
- A floating window that **goes black while the video carries on playing** now notices and fixes itself. Loopr already closed a window with a message whenever the player *reported* a failure, but a video surface can stop putting frames on screen without reporting anything at all — leaving a black rectangle floating with nothing to recover it. Each window now watches the frames actually being drawn rather than taking the player's word for it: if they stop for three seconds it hands the surface back, then re-loads the video at the same spot, and only if neither works does it close the window and say why.
  - Deliberately quiet about it — a window that recovers just carries on playing, since the whole point is not to interrupt you.
  - It leaves frames alone when they stop for a good reason: screen off, or the system hiding overlays over Settings and permission dialogs. Verified it stays out of the way through a **long A–B loop**, which seeks back every few seconds and is the case most likely to be mistaken for a stall.
  - When it does fire, it records whether the playback clock was still running, which is the detail that says whether the picture died or playback itself wedged. Turn the diagnostics on with `adb shell setprop log.tag.LooprQueue DEBUG`.

## 1.9 — 2026-08-14
- Added **floating windows**: up to **three** videos at once, each in its own draggable, pinch-resizable window over whatever else you're doing. Turn on **Floating windows** (overflow menu, or the **Float** chip in the player) and the window button — and pressing Home — hands the video to a floating window instead of the system mini window.
  - Each window carries its own **folder queue**, so ⏮ / ⏭ walk the folder exactly as they do full screen (⏮ restarts the video if you're more than 3 seconds in, steps back on a second press, and both are greyed out on a lone video).
  - **The A–B loop travels with the video.** Set A–B, float it, and the window keeps looping that segment — an **A–B** badge shows the loop is live, since there's no seek bar in a window that small. Tap ⛶ to go back to full screen and the queue, position, speed, mute, resize and A–B all come back with it.
  - Drag a window anywhere; it's kept on screen, and re-clamped when you rotate the phone. Pinch to resize, aspect ratio preserved, between 160dp wide and 60% of the screen; the size you settle on is reused for the next window you open.
  - One notification for the lot ("N floating videos") with **Close all**, and it stops itself when the last window closes.
  - **Honest limits:** a fourth window is refused with a message rather than silently ignored, and if the device runs out of video decoders that window closes with a reason instead of leaving a black rectangle floating.
  - Floating windows need Android's **"Display over other apps"** permission, which Loopr explains before sending you to the settings screen. Without it — or if it's withdrawn later — Loopr falls back to the **system mini window (PiP)** and says so. That zero-permission path is unchanged for anyone who doesn't turn floating windows on.

## 1.8 — 2026-08-05
- The floating (PiP) window now has **⏮ and ⏭** alongside play/pause, so you can skip through the folder without expanding back to full screen. They behave exactly as they do full screen — ⏮ restarts the current video if you're more than 3 seconds in, and steps back a video if you press it again. On a lone video with no folder to walk they're greyed out rather than hidden, so the controls don't shift about.

## 1.7 — 2026-08-05
- Fixed **multiple‑players mode being ignored for videos opened from another app**. With a video floating in the mini (PiP) window, opening a second one from a file manager pulled that window back to full screen and played the new video in it instead of starting a second player. The flags that give a video its own window can only be set by whoever launches the activity — the library grid set them, but a file manager has no idea the setting exists — so the incoming video landed in the player that was already open. Loopr now relaunches such a video into its own task itself, and the floating window is left alone. Turning the setting off is unchanged: one player, reused.
- Fixed a crash that took the **whole app** down — every open player with it — when a player instance finished before playback had been set up (a video opened with no data, or one handed on to its own window). `onUserLeaveHint`/`onStop` touched the player that instance never built.

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
