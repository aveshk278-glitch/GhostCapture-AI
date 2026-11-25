# GhostCapture AI

GhostCapture captures Android gameplay with the game's original controls still visible and usable. Keep the original recording, then export a second copy with selected HUD regions reconstructed. Offline Android export estimates missing pixels from nearby pixels. The desktop and notebook pipeline can use neighboring frames and a neural model trained on that recording. Reconstructed pixels estimate the hidden image.

The Android manifest has no internet permission. Local recording and processing need no account, cloud service, credits, ads, or API key. Recording length depends on hardware, operating system behavior, and storage.

## Android installation

Use Android 10 or later. From an Android Studio or command-line setup, install JDK 17, Android SDK Platform 35, Build Tools 35.0.0, and Gradle 8.11.1. JDK 21 also works with the configured Java 17 bytecode target. Initial setup downloads dependencies from public repositories.

Open the `android` directory in Android Studio or run these commands from the repository root:

```sh
cd android
./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. With system Gradle use `gradle` in place of `./gradlew`. You can also transfer the debug APK to a phone, open it, and grant that file manager permission to install apps from its source if Android prompts you.

### Capture and edit

1. Set portrait or landscape, codec, resolution, frame rate, bitrate, and audio source. Size and rate options are based on hardware encoder capabilities. The offered rate is a target. Actual rates depend on the display, game, driver, and device load.
2. Optionally choose an installed game to launch when recording starts. Start recording and grant Android screen-sharing consent after the countdown. Use Android navigation to switch manually. Enable notification permission on Android 13 or later to access pause, resume, and stop outside the app.
3. Open the resulting recording from Gallery, or import a video in Editor. Scrub the frame, ask for static HUD suggestions, or draw rectangles around HUD elements. Undo unwanted selections. Import or export the rectangle profile as JSON. Coordinates are normalized to the video frame.
4. Set trim times and choose a trim or HUD fill export. Split at the preview position or append a second clip. Set export height, bitrate, and overall audio volume. Play either the original or the processed copy. Use **Save a copy** or **Save exported MP4** to write a file to a folder of your choice.

Files in Gallery reside in private application storage. Saving an external copy allows desktop transfer and backup before uninstalling. Interrupted sessions may leave a recovery file. Android MP4 files require finalization, so abrupt crashes or power loss can leave an unreadable file. Android's projection-stop callback normally gives the encoder an opportunity to finalize.

### Release APK and signing

Keep the signing key in a private directory or offline backup. Use the same signing key for every upgrade of the same app ID. These commands prompt for secrets interactively.

```sh
cd android
./gradlew assembleRelease
keytool -genkeypair -keystore /secure/path/ghostcapture.p12 -storetype PKCS12 \
  -alias ghostcapture -keyalg RSA -keysize 4096 -validity 10000
zipalign -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk GhostCapture-aligned.apk
apksigner sign --ks /secure/path/ghostcapture.p12 --ks-key-alias ghostcapture \
  --out GhostCapture-release.apk GhostCapture-aligned.apk
apksigner verify --verbose GhostCapture-release.apk
adb install -r GhostCapture-release.apk
```

Run `zipalign` and `apksigner` from the SDK Build Tools directory or add it to your PATH. Release and debug APKs normally have different signing keys. Back up private app files before switching signing keys. The repository's GitHub Actions workflow builds and uploads a debug APK and runs the desktop tests on the free runner where available. Local builds require no GitHub account.

## Desktop processing

Use Python 3.11 or later and FFmpeg with `ffprobe` and the `libx264` encoder on PATH. Create and activate a Python virtual environment using your platform's shell, then install the base requirements.

```sh
python -m pip install -r desktop/requirements.txt
python desktop/ghostcapture.py game.mp4 --preview-only --save-mask mask.png
python desktop/ghostcapture.py game.mp4 --mask mask.png
python desktop/ghostcapture.py game1.mp4 game2.mp4 --profile desktop/example-profile.json
```

White areas of a PNG mask define pixels to reconstruct. The example profile illustrates the format. Customize it for your game and orientation before processing. Automatic suggestions use persistent, high-contrast edges and may include stationary gameplay details or miss translucent and moving elements. Review the mask preview before export.

For optional self-supervised neural fill, install a free PyTorch build matching your CPU, NVIDIA CUDA, or Apple MPS environment. CUDA-compatible builds and platform commands are available from PyTorch. OpenCV, if installed, is used for the non-neural Telea fallback. [PyTorch installation](https://pytorch.org/get-started/locally/)

```sh
python -m pip install -r desktop/requirements-neural.txt
python desktop/ghostcapture.py game.mp4 --mask mask.png --neural-steps 150 --device auto
```

The desktop processor uses adjacent source frames with translation estimation and uses an optional neural or spatial fill for remaining pixels. It preserves unmasked pixels during reconstruction. MP4 encoding can change colors or details across the frame. It normalizes input video to constant frame rate (defaulting to the reported average), uses the first audio track, and stream-copies AAC where possible. Other audio codecs are converted to AAC. HDR preservation and multi-audio export are outside the current implementation. Use `--fps`, `--crf`, and `--chunk-frames` to control the output. Use `--clean-checkpoints` to remove intermediate data after a successful export.

Checkpoints save the normalized source, completed chunks, and trained model. To resume, use the same input bytes, mask, checkpoint directory, and processing options. If an exported file already exists, select another output directory or pass `--overwrite`. The neural step count affects training time and does not guarantee quality. There are no downloaded pretrained weights.

## Optional Kaggle and Colab notebooks

Open the relevant `notebooks/GhostCapture_*.ipynb` file, select a free GPU runtime, and attach or upload your own source ZIP, footage, and optional profile. Set the paths in the configuration cell. The notebook installs free dependencies, checks CUDA, writes mask previews, and runs batch MP4 export. Each detected CUDA GPU processes its own chunks and has its own memory budget. The code uses CUDA autocast for neural inference and a bounded rolling window for frame processing. The notebook can resume from a checkpoint archive you save and restore. Cloud use is optional and subject to the provider's account requirements, session limits, and GPU quotas.

## Troubleshooting and verification

- **Black or missing game content** — Android and the game can block capture of protected surfaces. Use capture where the platform permits it.
- **Silent game audio** — Playback capture requires the source application to allow it. Some devices restrict concurrent playback capture and microphone recording. Try game audio or microphone alone if combined capture fails.
- **Encoder rejected a setting** — Lower the bitrate, frame rate, or short edge, or switch to H.264. Encoder capability declarations can differ from actual device behavior.
- **HUD remains or the fill looks wrong** — Use a more precise profile or PNG mask. Android's spatial fill is intended for small regions. Desktop temporal fill is strongest when the hidden area becomes visible in an adjacent frame. Scene cuts, large HUD panels, and fast parallax can degrade results.
- **GPU runs out of memory** — Use a lower-resolution input, fewer concurrent workloads, or CPU mode. Reducing chunk length limits checkpoint size and retry cost. Per-frame memory primarily depends on resolution and processing method.
- **Resume files occupy storage** — Archive only if needed and delete the matching checkpoint directory or rerun successfully with `--clean-checkpoints`.

Run the desktop test suite from the repository root with `PYTHONPATH=desktop python -m unittest discover -s desktop/tests -v`. The Android test suite checks pause timestamp accounting and static HUD suggestions. [Device QA checklist](QA.md) · [Licenses and model review](LICENSES.md)

## Scope

This version implements Android recording, audio capture, private gallery, mask profiles, trim, split, merge, export adjustments, and offline rectangle fill, and the desktop and notebook workflows. Automatic suggestions are static and heuristic. Universal or dynamic HUD detection, an on-device neural model, real-time HUD removal, advanced pretrained temporal models, freehand and polygon editing, multitrack timeline audio controls, overlays, and the full set of requested screens remain incomplete. Production readiness also depends on the physical device and GPU tests listed in the QA checklist.
