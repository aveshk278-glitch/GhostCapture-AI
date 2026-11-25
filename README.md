# GhostCapture AI

**Record Everything. Reveal Only Gameplay.**

A free, open-source Android screen recorder for gamers — with honest,
post-processing HUD removal. No ads, no watermark, no subscription, no
account, no internet permission.

## What it does

- **Records the screen** with Android MediaProjection + MediaRecorder
  (H.264 MP4), 720p / 1080p, 30 / 60 FPS, microphone audio on/off.
- **No tap indicators**: the app never draws touch circles, and it warns
  you if Android's "Show touches" developer option is on (those circles
  are drawn by the system and *will* appear in any recording until you
  turn that setting off).
- **No recorder UI in the video**: there is no floating button while
  recording. Stop / Pause / Resume live only in the recording
  notification.
- **Saves to Movies/GhostCapture** with a built-in gallery (play, share,
  delete).
- **Manual HUD mask editor**: draw rectangles over HUD elements
  (joystick, fire button, minimap, health bar…) on a video frame and
  save them as a per-game profile (JSON). Export the profile for the
  Kaggle notebook or the desktop tool.

## How HUD removal actually works (read this first)

A screen recorder **cannot** hide a game's HUD while recording — the HUD
is drawn by the game itself, and the recorder only captures the finished
screen image. Any app that claims live HUD hiding is misleading you.

GhostCapture's real workflow is **record first, remove after**:

1. **Record** your gameplay normally (HUD visible while you play).
2. **Mask** the HUD: open the video in the Gallery → "Mask HUD", drag
   rectangles over the HUD elements, save the profile for that game.
3. **Process** the video with the free Kaggle GPU notebook
   (`kaggle/GhostCapture_Kaggle_GPU.ipynb`, ProPainter video inpainting)
   or the desktop CLI (`desktop/ghostcapture_process.py`, OpenCV
   inpainting). They reconstruct the gameplay behind the masked areas
   and export a clean MP4 with the original audio.

## Honest limits

- **No live AI HUD removal on the phone.** Real-time video inpainting
  needs a GPU the phone does not have for this workload. The working
  path is record → process on Kaggle GPU / desktop. The app never
  claims otherwise.
- **No universal HUD-detection model exists.** Automatic detection is
  best-effort (templates + your manual masks). The reliable path is
  manual masks per game.
- **Reconstructed pixels are an AI guess**, not the original hidden
  game environment. Quality depends on the model, the masks, and the
  scene.
- **Device (internal game) audio is NOT in v1.** It needs a MediaCodec
  + AudioPlaybackCapture pipeline (planned Phase 3). Microphone audio
  works. This is stated in the app's Settings screen too.
- **ProPainter is non-commercial licensed** (NTU S-Lab License 1.0).
  E2FGVI is CC BY-NC 4.0. Do not use them commercially without
  permission. See `kaggle/` and `desktop/` docs.
- **Kaggle free GPU has quotas** (GPU hours/week, session limits). The
  notebook respects them and never tries to bypass them.
- On-device install/record behaviour is verified by the user on their
  phone — GitHub Actions only proves the project compiles.

## Screens

Home (record + status), Gallery, HUD Profiles, Settings, Mask Editor.
Futuristic dark AMOLED UI (cyan + purple).

## Build (GitHub Actions — the only compiler for this project)

Push to `main` (or Run workflow manually) and the workflow builds a
debug APK and uploads it as the `app-debug-apk` artifact (30-day
retention). JDK 17, Gradle 8.7, AGP 8.5.2, Kotlin 2.0.20.

```text
gradle assembleDebug
```

The APK is debug-signed. Install it on your phone, grant screen-capture
permission when asked, and record.

## Project layout

```text
app/            Android app (Kotlin + Jetpack Compose, minSdk 29)
  src/main/java/com/ghostcapture/app/
    MainActivity.kt        Navigation, permissions, countdown, record flow
    data/                  Settings (DataStore), HUD profiles (JSON), gallery
    recorder/              Foreground service: MediaProjection + MediaRecorder
    ui/                    Theme + screens (Home, Gallery, Settings,
                           Profiles, Mask Editor)
  src/test/                Unit tests (HUD mask normalisation)
.github/workflows/  Build APK workflow
kaggle/            Free Kaggle GPU notebook (ProPainter HUD removal)
desktop/           Python CLI for local GPU/CPU processing (OpenCV)
```

## HUD profile JSON format (shared by app, notebook, desktop tool)

```json
{
  "version": 1,
  "game": "Free Fire",
  "videoWidth": 1920,
  "videoHeight": 1080,
  "masks": [
    {"label": "joystick", "x": 0.02, "y": 0.72, "w": 0.14, "h": 0.18}
  ]
}
```

`x/y/w/h` are fractions (0..1) of the frame. Empty `masks` is an error —
the tools refuse to pass a video through unchanged.

## Privacy

- No internet permission, no account, no tracking, no ads.
- Recordings stay on the device unless you share them.
- The app never accesses game memory, never injects code, never modifies
  games, and never touches anti-cheat systems.

## License

MIT (see `LICENSE`). Third-party models in `kaggle/` and `desktop/`
carry their own licenses — read them before use.
