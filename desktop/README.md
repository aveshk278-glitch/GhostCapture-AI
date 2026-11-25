# GhostCapture AI — Desktop Processor

Removes game HUD elements (joystick, fire button, minimap, …) from a
recorded gameplay video by inpainting the masked regions, and re-attaches
the original audio track. The mask profile is the JSON file saved by the
GhostCapture Android app — see the format below.

Free / open-source only. No paid APIs, keys or accounts.

## Install

Requires Python 3.9+.

```bash
# 1. Clone / copy this folder, then:
pip install -r requirements.txt

# 2. (Recommended) also install a system ffmpeg so audio handling uses it
#    first. Without it, the imageio-ffmpeg bundled binary is used instead.
#    Windows:  winget install ffmpeg
#    macOS:    brew install ffmpeg
#    Linux:    sudo apt install ffmpeg        # Debian/Ubuntu
```

## Mask profile format

```json
{
  "version": 1,
  "game": "Free Fire",
  "videoWidth": 1920,
  "videoHeight": 1080,
  "masks": [
    {"label": "joystick",    "x": 0.02, "y": 0.72, "w": 0.14, "h": 0.18},
    {"label": "fire_button", "x": 0.86, "y": 0.70, "w": 0.10, "h": 0.16}
  ]
}
```

`x/y/w/h` are fractions (0–1) of the frame size, so a profile recorded at
1920×1080 works on a 1280×720 recording of the same game layout. If
`"masks"` is empty the tool **errors out** instead of silently passing the
video through unchanged.

## Run

```bash
# Baseline: OpenCV Telea inpainting, CPU-only, works everywhere
python ghostcapture_process.py --input game.mp4 --masks profile.json --output game_clean.mp4

# Faster / softer (processes at half resolution, upscales back)
python ghostcapture_process.py --input game.mp4 --masks profile.json --output game_clean.mp4 --quality balanced

# Whole folder with one profile (outputs <name>_clean.mp4 into out/)
python ghostcapture_process.py --masks profile.json --batch ./recordings --output ./clean

# Help
python ghostcapture_process.py --help
```

## ProPainter method (optional, GPU, higher quality)

ProPainter (ICCV 2023) gives much better temporal consistency than Telea,
but needs an NVIDIA GPU + CUDA, PyTorch, and its weights:

```bash
git clone https://github.com/sczhou/ProPainter.git
cd ProPainter && pip install -r requirements.txt
# Download ProPainter.pth, recurrent_flow_completion.pth, raft-things.pth
# from the V0.1.0 release into ProPainter/weights/
#   https://github.com/sczhou/ProPainter/releases/tag/v0.1.0

python ghostcapture_process.py --input game.mp4 --masks profile.json \
    --output game_clean.mp4 --method propainter \
    --propainter-dir ./ProPainter
```

If anything ProPainter needs is missing, the tool **exits with an error
naming the missing piece** — it never silently runs Telea and pretends it
was ProPainter. Add `--fallback-telea` only if you explicitly want Telea
as a fallback (a loud warning is printed in that case).

> **Licence warning — read before using ProPainter:** ProPainter code and
> weights are released under the **NTU S-Lab License 1.0 for
> non-commercial use only**. Commercial use requires permission from the
> authors (contact in the repo README). Verified 2026-11-25 from
> https://github.com/sczhou/ProPainter. The Telea method has no such
> restriction (OpenCV is Apache-2.0).

## Audio

The original audio track is copied back with ffmpeg (system ffmpeg first,
otherwise the imageio-ffmpeg bundled binary). If no ffmpeg is available at
all, a **video-only** file is written and a clear warning is printed —
audio is never silently claimed to be preserved.

## Limitations (honest)

- Telea is a classic (non-AI) inpainting method: good on flat areas
  (buttons over sky/ground), weaker on busy textured backgrounds, and it
  works frame-by-frame, so fast camera motion can shimmer slightly.
- HUD masks are static rectangles. If a HUD element moves or the game
  layout changes mid-recording, the mask will not follow it.
- No speed numbers are quoted here: inpainting speed depends on your CPU /
  GPU, resolution and video length. Telea prints live fps and ETA while
  it runs so you can see it on your own machine.
- This tool was syntax-checked but not run end-to-end in the build VM
  (no OpenCV installed there); run the command above on a short test clip
  first.
