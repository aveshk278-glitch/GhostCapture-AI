#!/usr/bin/env python3
"""
GhostCapture AI — desktop post-processing tool.

Removes game HUD elements (joystick, fire button, minimap, ...) from a
recorded gameplay video by inpainting the masked regions, then re-attaches
the original audio track.

Mask profile format (JSON, written by the GhostCapture Android app):

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

x/y/w/h are fractions (0..1) of the frame width/height. They are scaled to
the actual frame size at runtime, so a profile recorded at 1920x1080 also
works on a 1280x720 recording of the same game layout.

Methods
-------
telea (default, CPU-only)
    OpenCV cv2.inpaint(INPAINT_TELEA). Works everywhere with only
    opencv-python-headless + numpy. Quality is decent for flat HUD areas
    (buttons over sky/ground) and weaker on busy textured backgrounds.

propainter (optional, GPU)
    ProPainter video inpainting (ICCV 2023, sczhou/ProPainter). Much better
    temporal consistency, but needs a CUDA GPU, PyTorch, a local clone of
    the ProPainter repo and its weights. See --propainter-dir and
    --weights-dir. If any of that is missing the tool ERRORS OUT instead of
    silently running Telea — pass --fallback-telea if you explicitly want
    Telea as a fallback (a loud warning is printed in that case).

    LICENSE WARNING: ProPainter code and weights are released under the
    NTU S-Lab License 1.0 for NON-COMMERCIAL use only. Commercial use
    requires permission from the authors. Do not ship ProPainter output
    in a commercial product without that permission. (Verified 2026-11-25
    from https://github.com/sczhou/ProPainter — see desktop/README.md.)

Audio
-----
The original audio track is copied back with ffmpeg (system ffmpeg if
present, otherwise the imageio-ffmpeg bundled binary). If no ffmpeg is
available at all, a video-only file is written and a clear warning is
printed — audio is never silently claimed to be preserved.

Zero-cost rule: only free / open-source components are used. No paid APIs,
keys or accounts are needed for either method.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

VERSION = "1.0.0"
VIDEO_EXTS = {".mp4", ".mov", ".mkv", ".avi", ".webm"}
# ProPainter weight files the official repo expects in its `weights/` folder.
PROPAINTER_WEIGHTS = ("ProPainter.pth", "recurrent_flow_completion.pth", "raft-things.pth")


# ---------------------------------------------------------------------------
# Mask profile
# ---------------------------------------------------------------------------

def load_mask_profile(path: Path) -> dict:
    """Load and validate a GhostCapture mask profile JSON.

    Raises SystemExit with a clear message on any problem — including an
    empty masks list, which must never silently pass a video through.
    """
    if not path.is_file():
        sys.exit(f"ERROR: mask profile not found: {path}")
    try:
        profile = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        sys.exit(f"ERROR: mask profile is not valid JSON ({path}): {exc}")
    if not isinstance(profile, dict):
        sys.exit(f"ERROR: mask profile must be a JSON object: {path}")
    if profile.get("version") != 1:
        sys.exit(
            f"ERROR: unsupported mask profile version "
            f"{profile.get('version')!r} (expected 1): {path}"
        )
    masks = profile.get("masks")
    if not isinstance(masks, list):
        sys.exit(f"ERROR: mask profile has no 'masks' list: {path}")
    if not masks:
        sys.exit(
            "ERROR: mask profile contains no masks (\"masks\": []).\n"
            "       Nothing to remove — refusing to pass the video through "
            "unchanged.\n"
            "       Create a mask profile in the GhostCapture Android app "
            "(tap each HUD element to mark it) and try again."
        )
    for i, m in enumerate(masks):
        if not isinstance(m, dict):
            sys.exit(f"ERROR: mask #{i} is not an object: {m!r}")
        for key in ("x", "y", "w", "h"):
            if key not in m:
                sys.exit(f"ERROR: mask #{i} ({m.get('label', '?')}) is missing '{key}'")
            try:
                v = float(m[key])
            except (TypeError, ValueError):
                sys.exit(f"ERROR: mask #{i} ({m.get('label', '?')}): '{key}' is not a number: {m[key]!r}")
            if not 0.0 <= v <= 1.0:
                sys.exit(
                    f"ERROR: mask #{i} ({m.get('label', '?')}): '{key}'={v} "
                    f"is outside 0..1 (fractions of frame size)"
                )
        if float(m["w"]) <= 0 or float(m["h"]) <= 0:
            sys.exit(f"ERROR: mask #{i} ({m.get('label', '?')}) has zero width/height")
    game = profile.get("game", "unknown game")
    print(f"[profile] {len(masks)} mask(s) for {game!r} from {path}")
    for m in masks:
        print(f"          - {m.get('label', '?')}: "
              f"x={m['x']} y={m['y']} w={m['w']} h={m['h']}")
    return profile


def build_mask_image(masks: list, width: int, height: int, dilate_px: int = 2):
    """Build a single-channel uint8 mask image (255 = inpaint here).

    The HUD is static, so one mask image is reused for every frame.
    Rectangles are clamped to the frame and dilated by a couple of pixels
    so inpainting covers anti-aliased HUD edges.
    """
    import cv2
    import numpy as np

    mask = np.zeros((height, width), dtype=np.uint8)
    for m in masks:
        x = int(round(float(m["x"]) * width))
        y = int(round(float(m["y"]) * height))
        w = int(round(float(m["w"]) * width))
        h = int(round(float(m["h"]) * height))
        x0, y0 = max(0, x), max(0, y)
        x1, y1 = min(width, x + w), min(height, y + h)
        if x1 <= x0 or y1 <= y0:
            print(f"[warn] mask {m.get('label', '?')} falls outside the frame — skipped")
            continue
        cv2.rectangle(mask, (x0, y0), (x1 - 1, y1 - 1), 255, thickness=-1)
    if dilate_px > 0:
        kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * dilate_px + 1, 2 * dilate_px + 1))
        mask = cv2.dilate(mask, kernel)
    covered = float((mask > 0).mean()) * 100
    print(f"[mask] built {width}x{height} mask, {covered:.1f}% of frame covered")
    return mask


# ---------------------------------------------------------------------------
# ffmpeg helpers (audio preservation)
# ---------------------------------------------------------------------------

def find_ffmpeg() -> str | None:
    """Return a usable ffmpeg executable path, or None.

    Prefers a system ffmpeg; falls back to the binary bundled with
    imageio-ffmpeg (pip-installable, no system package needed).
    """
    system = shutil.which("ffmpeg")
    if system:
        return system
    try:
        import imageio_ffmpeg
        bundled = imageio_ffmpeg.get_ffmpeg_exe()
        if bundled and os.path.isfile(bundled):
            print(f"[ffmpeg] using imageio-ffmpeg bundled binary: {bundled}")
            return bundled
    except Exception:
        pass
    return None


def has_audio_stream(ffmpeg: str, video: Path) -> bool:
    """True if ffprobe/ffmpeg can see an audio stream in the input."""
    ffprobe = shutil.which("ffprobe")
    if ffprobe:
        r = subprocess.run(
            [ffprobe, "-v", "error", "-select_streams", "a",
             "-show_entries", "stream=index", "-of", "csv=p=0", str(video)],
            capture_output=True, text=True, timeout=60,
        )
        return bool(r.stdout.strip())
    # No ffprobe: probe with ffmpeg itself.
    r = subprocess.run(
        [ffmpeg, "-hide_banner", "-i", str(video)],
        capture_output=True, text=True, timeout=60,
    )
    return "Audio:" in (r.stderr or "")


def mux_audio(ffmpeg: str, silent_video: Path, original: Path, output: Path) -> bool:
    """Copy the original audio track onto the processed (silent) video.

    Returns True on success. On failure the caller keeps the silent video
    and prints a warning — it never claims audio was preserved.
    """
    if not has_audio_stream(ffmpeg, original):
        print("[audio] input has no audio stream — video-only output is correct")
        shutil.copyfile(silent_video, output)
        return True
    cmd = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
           "-i", str(silent_video), "-i", str(original),
           "-map", "0:v:0", "-map", "1:a:0",
           "-c:v", "copy", "-c:a", "copy", "-shortest", str(output)]
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=3600)
    if r.returncode != 0:
        print(f"[warn] ffmpeg audio mux failed ({r.stderr.strip()[:300]})")
        return False
    return True


# ---------------------------------------------------------------------------
# Method: Telea (OpenCV, CPU)
# ---------------------------------------------------------------------------

def process_telea(input_path: Path, mask, output_silent: Path,
                  quality: str, radius: int) -> int:
    """Inpaint every frame with cv2.inpaint(INPAINT_TELEA). Returns frames written."""
    import cv2

    cap = cv2.VideoCapture(str(input_path))
    if not cap.isOpened():
        sys.exit(f"ERROR: cannot open video: {input_path}")
    fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    total = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    print(f"[video] {width}x{height} @ {fps:.2f} fps, "
          f"{total if total else '?'} frames, {input_path.name}")

    # 'balanced' processes at half resolution and upscales back — faster on
    # weak CPUs, slightly softer result. 'high' processes at full resolution.
    scale = 1.0
    if quality == "balanced" and max(width, height) > 720:
        scale = 0.5
        print("[quality] balanced: processing at 0.5x, upscaling back to full size")
    proc_w, proc_h = int(width * scale), int(height * scale)
    if scale != 1.0:
        mask = cv2.resize(mask, (proc_w, proc_h), interpolation=cv2.INTER_NEAREST)

    fourcc = cv2.VideoWriter_fourcc(*"mp4v")
    writer = cv2.VideoWriter(str(output_silent), fourcc, fps, (width, height))
    if not writer.isOpened():
        cap.release()
        sys.exit(f"ERROR: cannot create output video: {output_silent}")

    n = 0
    t0 = time.time()
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        if scale != 1.0:
            small = cv2.resize(frame, (proc_w, proc_h), interpolation=cv2.INTER_AREA)
            filled = cv2.inpaint(small, mask, radius, cv2.INPAINT_TELEA)
            frame = cv2.resize(filled, (width, height), interpolation=cv2.INTER_LINEAR)
        else:
            frame = cv2.inpaint(frame, mask, radius, cv2.INPAINT_TELEA)
        writer.write(frame)
        n += 1
        if n % 60 == 0 or (total and n == total):
            elapsed = time.time() - t0
            rate = n / elapsed if elapsed > 0 else 0
            eta = (total - n) / rate if total and rate > 0 else float("nan")
            eta_s = f", ETA {eta:.0f}s" if total and math.isfinite(eta) else ""
            print(f"[telea] frame {n}/{total if total else '?'} "
                  f"({rate:.1f} fps{eta_s})", flush=True)
    cap.release()
    writer.release()
    print(f"[telea] done: {n} frames in {time.time() - t0:.1f}s "
          f"({n / max(time.time() - t0, 1e-9):.1f} fps avg)")
    return n


# ---------------------------------------------------------------------------
# Method: ProPainter (GPU, optional)
# ---------------------------------------------------------------------------

def check_propainter(propainter_dir: Path | None, weights_dir: Path | None) -> tuple[Path, Path]:
    """Verify everything ProPainter needs is present. Exits with a clear
    error naming the missing piece — never silently falls back."""
    problems = []
    try:
        import torch  # noqa: F401
    except ImportError:
        problems.append("PyTorch is not installed (pip install torch --index-url "
                        "https://download.pytorch.org/whl/cu121)")
    else:
        import torch
        if not torch.cuda.is_available():
            problems.append("PyTorch is installed but no CUDA GPU was detected "
                            "(torch.cuda.is_available() is False)")
    if not propainter_dir or not propainter_dir.is_dir():
        problems.append(f"ProPainter repo not found: {propainter_dir} "
                        f"(clone https://github.com/sczhou/ProPainter and pass --propainter-dir)")
    elif not (propainter_dir / "inference_propainter.py").is_file():
        problems.append(f"{propainter_dir} does not contain inference_propainter.py — "
                        f"is it really a ProPainter checkout?")
    wdir = weights_dir or (propainter_dir / "weights" if propainter_dir else None)
    if not wdir or not wdir.is_dir():
        problems.append(f"weights folder not found: {wdir}")
    else:
        missing = [w for w in PROPAINTER_WEIGHTS if not (wdir / w).is_file()]
        if missing:
            problems.append(
                f"missing ProPainter weights in {wdir}: {', '.join(missing)} "
                f"(download them from the V0.1.0 release at "
                f"https://github.com/sczhou/ProPainter/releases/tag/v0.1.0)")
    if problems:
        sys.exit("ERROR: --method propainter cannot run:\n  - " + "\n  - ".join(problems) +
                 "\n\nRefusing to silently run Telea instead. Either fix the "
                 "items above, or re-run with --method telea (or add "
                 "--fallback-telea to fall back explicitly).")
    assert propainter_dir is not None and wdir is not None
    return propainter_dir, wdir


def process_propainter(input_path: Path, profile: dict, output_silent: Path,
                       propainter_dir: Path, weights_dir: Path,
                       quality: str, workdir: Path) -> None:
    """Run ProPainter's own inference script on the video.

    The HUD mask is static, so we write one mask PNG and let
    inference_propainter.py use it for every frame (it accepts a single
    mask image as well as a frame-wise mask folder).
    """
    import cv2

    cap = cv2.VideoCapture(str(input_path))
    if not cap.isOpened():
        sys.exit(f"ERROR: cannot open video: {input_path}")
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    cap.release()

    mask = build_mask_image(profile["masks"], width, height)
    mask_png = workdir / "mask.png"
    cv2.imwrite(str(mask_png), mask)

    # Keep 1080p+ video inside T4-class VRAM: ProPainter's own README
    # suggests --width/--height to cap processing resolution.
    cmd = [sys.executable, "inference_propainter.py",
           "--video", str(input_path),
           "--mask", str(mask_png),
           "--ckpt", str(weights_dir / "ProPainter.pth"),
           "--output", str(workdir / "propainter_out"),
           "--fp16"]
    if quality == "balanced" and height > 720:
        cmd += ["--width", "1280", "--height", "720"]
        print("[propainter] balanced: processing at 1280x720")
    print("[propainter] running: " + " ".join(cmd))
    print("[propainter] NOTE: ProPainter is NON-COMMERCIAL licensed "
          "(NTU S-Lab License 1.0) — see README.md")
    r = subprocess.run(cmd, cwd=str(propainter_dir))
    if r.returncode != 0:
        sys.exit(f"ERROR: ProPainter inference failed (exit {r.returncode}). "
                 f"Check the output above; the ProPainter checkout may need "
                 f"its requirements installed (pip install -r requirements.txt "
                 f"inside {propainter_dir}).")
    # inference_propainter.py writes <output>/<video_stem>.mp4
    produced = list((workdir / "propainter_out").rglob("*.mp4"))
    if not produced:
        sys.exit("ERROR: ProPainter finished but produced no .mp4 output — "
                 "check the ProPainter output folder.")
    shutil.copyfile(produced[0], output_silent)
    print(f"[propainter] done: {produced[0].name}")


# ---------------------------------------------------------------------------
# One video, end to end
# ---------------------------------------------------------------------------

def process_one(input_path: Path, profile: dict, output_path: Path,
                method: str, quality: str, radius: int,
                propainter_dir: Path | None, weights_dir: Path | None,
                fallback_telea: bool) -> None:
    print(f"\n=== {input_path.name} -> {output_path.name}  (method={method}, quality={quality}) ===")
    output_path.parent.mkdir(parents=True, exist_ok=True)

    if method == "propainter":
        try:
            pp_dir, wdir = check_propainter(propainter_dir, weights_dir)
        except SystemExit as exc:
            if fallback_telea:
                print(f"[warn] ProPainter unavailable — FALLING BACK TO TELEA "
                      f"because --fallback-telea was given:\n{exc}")
                method = "telea"
            else:
                raise
    if method == "propainter":
        with tempfile.TemporaryDirectory(prefix="ghostcapture-pp-") as tmp:
            tmpdir = Path(tmp)
            silent = tmpdir / "silent.mp4"
            process_propainter(input_path, profile, silent, pp_dir, wdir,
                               quality, tmpdir)
            finish_with_audio(input_path, silent, output_path)
        return

    # --- telea path ---
    import cv2
    cap = cv2.VideoCapture(str(input_path))
    if not cap.isOpened():
        sys.exit(f"ERROR: cannot open video: {input_path}")
    width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    cap.release()
    mask = build_mask_image(profile["masks"], width, height)
    with tempfile.TemporaryDirectory(prefix="ghostcapture-") as tmp:
        silent = Path(tmp) / "silent.mp4"
        process_telea(input_path, mask, silent, quality, radius)
        finish_with_audio(input_path, silent, output_path)


def finish_with_audio(input_path: Path, silent: Path, output_path: Path) -> None:
    ffmpeg = find_ffmpeg()
    if ffmpeg is None:
        print("[warn] NO ffmpeg found (neither system ffmpeg nor imageio-ffmpeg).\n"
              "       Writing VIDEO-ONLY output — the original audio track was DROPPED.\n"
              "       Install ffmpeg (https://ffmpeg.org/download.html) and re-run "
              "to keep audio.")
        shutil.copyfile(silent, output_path)
    else:
        if mux_audio(ffmpeg, silent, input_path, output_path):
            print(f"[audio] original audio preserved -> {output_path}")
        else:
            print("[warn] audio mux failed — writing video-only output instead.")
            shutil.copyfile(silent, output_path)
    print(f"[done] {output_path} ({output_path.stat().st_size / 1_048_576:.1f} MB)")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        prog="ghostcapture_process.py",
        description="GhostCapture AI: remove game HUD elements from a recorded "
                    "gameplay video by inpainting the masked regions, keeping "
                    "the original audio. Free/open-source only — no paid APIs.",
        epilog="Example: python ghostcapture_process.py --input game.mp4 "
               "--masks profile.json --output game_clean.mp4",
    )
    p.add_argument("--input", "-i", type=Path,
                   help="input video file (ignored with --batch)")
    p.add_argument("--masks", "-m", type=Path, required=True,
                   help="mask profile JSON from the GhostCapture Android app")
    p.add_argument("--output", "-o", type=Path, required=True,
                   help="output video file, or output FOLDER with --batch")
    p.add_argument("--method", choices=["telea", "propainter"], default="telea",
                   help="inpainting method: telea = OpenCV CPU (default), "
                        "propainter = ProPainter GPU (needs torch + CUDA + "
                        "weights; non-commercial licence)")
    p.add_argument("--quality", choices=["high", "balanced"], default="high",
                   help="high = full resolution (default); balanced = faster, "
                        "processes at reduced resolution")
    p.add_argument("--radius", type=int, default=5,
                   help="Telea inpainting radius in pixels (default 5; "
                        "larger = smoother but slower)")
    p.add_argument("--batch", type=Path, default=None, metavar="FOLDER",
                   help="batch mode: process every video in FOLDER with the "
                        "same mask profile; --output is the output folder")
    p.add_argument("--propainter-dir", type=Path, default=None,
                   help="path to a local clone of https://github.com/sczhou/ProPainter "
                        "(only for --method propainter)")
    p.add_argument("--weights-dir", type=Path, default=None,
                   help="folder with ProPainter.pth etc. (default: "
                        "<propainter-dir>/weights)")
    p.add_argument("--fallback-telea", action="store_true",
                   help="with --method propainter: if ProPainter cannot run, "
                        "fall back to Telea WITH a printed warning instead of "
                        "erroring out")
    p.add_argument("--version", action="version", version=f"%(prog)s {VERSION}")
    args = p.parse_args(argv)

    print(f"GhostCapture AI desktop processor v{VERSION} — method={args.method}, "
          f"quality={args.quality}")
    profile = load_mask_profile(args.masks)

    if args.batch is not None:
        if not args.batch.is_dir():
            sys.exit(f"ERROR: batch folder not found: {args.batch}")
        videos = sorted(v for v in args.batch.iterdir()
                        if v.is_file() and v.suffix.lower() in VIDEO_EXTS)
        if not videos:
            sys.exit(f"ERROR: no video files found in {args.batch}")
        print(f"[batch] {len(videos)} video(s) in {args.batch}")
        outdir = args.output
        outdir.mkdir(parents=True, exist_ok=True)
        failed = []
        for i, video in enumerate(videos, 1):
            print(f"\n--- batch {i}/{len(videos)} ---")
            out = outdir / f"{video.stem}_clean{video.suffix}"
            try:
                process_one(video, profile, out, args.method, args.quality,
                            args.radius, args.propainter_dir, args.weights_dir,
                            args.fallback_telea)
            except SystemExit as exc:
                print(f"[batch] FAILED {video.name}: {exc}")
                failed.append(video.name)
        print(f"\n[batch] finished: {len(videos) - len(failed)} ok, "
              f"{len(failed)} failed" + (f" ({', '.join(failed)})" if failed else ""))
        return 1 if failed else 0

    if args.input is None:
        p.error("--input is required unless --batch is given")
    if not args.input.is_file():
        sys.exit(f"ERROR: input video not found: {args.input}")
    process_one(args.input, profile, args.output, args.method, args.quality,
                args.radius, args.propainter_dir, args.weights_dir,
                args.fallback_telea)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
