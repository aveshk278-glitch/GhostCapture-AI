#!/usr/bin/env python3
"""GhostCapture local and notebook entry point."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from fractions import Fraction
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import numpy as np
from PIL import Image

from hud import TemporalFill, mask_from_profile, suggest_mask, validate_mask


def ffmpeg(*args):
    return ["ffmpeg", "-hide_banner", "-nostdin", "-loglevel", "error", "-y", *map(str, args)]


def call(*args):
    subprocess.run(ffmpeg(*args), check=True)


def probe(path):
    raw = subprocess.check_output(["ffprobe", "-v", "error", "-of", "json",
                                  "-show_streams", "-show_format", str(path)])
    return json.loads(raw)


def video_stream(path):
    return next(stream for stream in probe(path)["streams"] if stream["codec_type"] == "video")


def valid_video(path, count=None):
    if not path.is_file():
        return False
    try:
        stream = video_stream(path)
        return count is None or int(stream.get("nb_frames", 0)) == count
    except (subprocess.CalledProcessError, ValueError, StopIteration):
        return False


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


@contextmanager
def checkpoint_lock(directory):
    with (directory / ".lock").open("a+b") as lock:
        lock.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


@contextmanager
def raw_frames(path, width, height, start=None, count=None):
    seek = ["-ss", f"{float(start):.9f}"] if start else []
    limit = ["-frames:v", str(count)] if count else []
    command = ffmpeg("-threads", 2, *seek, "-i", path, "-map", "0:v:0", "-an",
                     *limit, "-fps_mode", "passthrough", "-pix_fmt", "rgb24",
                     "-threads", 1, "-f", "rawvideo", "pipe:1")
    process = subprocess.Popen(command, stdout=subprocess.PIPE)

    def frames():
        while True:
            data = process.stdout.read(width * height * 3)
            if not data:
                break
            if len(data) != width * height * 3:
                raise IOError("A decoded frame was truncated.")
            yield np.frombuffer(data, dtype=np.uint8).reshape(height, width, 3)
        if process.wait():
            raise IOError("FFmpeg could not decode the video.")

    try:
        yield frames()
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.terminate()
        process.wait()


def collect_samples(path, width, height, fps, frame_count):
    indices = sorted(set(np.linspace(0, frame_count - 1, min(12, frame_count)).astype(int)))
    samples = []
    for index in indices:
        with raw_frames(path, width, height, Fraction(int(index), 1) / fps, 1) as frames:
            samples.extend(frames)
    if not samples:
        raise ValueError("Could not sample the input video.")
    return samples


def render_part(source, target, width, height, rate, total, begin, end, fill, quality):
    if valid_video(target, end - begin):
        return
    temporary = target.with_suffix(".partial.mp4")
    first, last = max(0, begin - 1), min(total, end + 1)
    command = ffmpeg("-f", "rawvideo", "-pix_fmt", "rgb24", "-video_size", f"{width}x{height}",
                     "-framerate", str(rate), "-i", "pipe:0", "-c:v", "libx264", "-threads", 2,
                     "-crf", quality, "-preset", "fast", "-pix_fmt", "yuv420p", temporary)
    with raw_frames(source, width, height, Fraction(first, 1) / rate, last - first) as frames:
        writer = subprocess.Popen(command, stdin=subprocess.PIPE)
        wrote = 0
        try:
            previous = None
            frame = next(frames)
            current = (frame, fill.signature(frame))
            for index in range(first, last):
                frame = next(frames, None)
                following = (frame, fill.signature(frame)) if frame is not None else None
                if begin <= index < end:
                    neighbors = [item for item in (previous, following) if item is not None]
                    writer.stdin.write(fill.fill(*current, neighbors).tobytes())
                    wrote += 1
                previous = current
                current = following
                if current is None:
                    break
            writer.stdin.close()
            if writer.wait() or wrote != end - begin or not valid_video(temporary, end - begin):
                raise IOError(f"Could not finalize frames {begin} through {end - 1}.")
            os.replace(temporary, target)
        finally:
            if writer.poll() is None:
                writer.terminate()
            try:
                writer.stdin.close()
            finally:
                writer.wait()
                temporary.unlink(missing_ok=True)
    print(f"Checkpoint saved: frames {begin}–{end - 1} of {total}", flush=True)


def render(source, output, args):
    source = source.resolve()
    output = output.resolve()
    if source == output:
        raise ValueError("Output must be different from the input.")
    if output.exists() and not args.overwrite:
        raise FileExistsError(f"{output} already exists. Use --overwrite to replace it.")
    output.parent.mkdir(parents=True, exist_ok=True)
    info = probe(source)
    original = next(stream for stream in info["streams"] if stream["codec_type"] == "video")
    fps = Fraction(args.fps or original["avg_frame_rate"])
    if not 0 < fps <= 240:
        raise ValueError("Set a valid export frame rate with --fps.")
    mask_file = args.mask or args.profile
    options = (str(fps), args.chunk_frames, args.crf, args.neural_steps, args.profile is not None,
               args.preview_only, args.device, digest(mask_file) if mask_file else "automatic")
    fingerprint = hashlib.sha256(repr((digest(source), options, 1)).encode()).hexdigest()[:24]
    work = (args.checkpoints / fingerprint).resolve()
    work.mkdir(parents=True, exist_ok=True)
    with checkpoint_lock(work):
        render_locked(source, output, info, original, fps, work, args)
    if args.clean_checkpoints and not args.preview_only:
        with checkpoint_lock(work):
            for item in work.iterdir():
                if item.name != ".lock":
                    item.unlink()


def render_locked(source, output, info, original, fps, work, args):
    normalized = work / "source.mp4"
    if not valid_video(normalized):
        temporary = work / "source.partial.mp4"
        call("-i", source, "-map", "0:v:0", "-an",
             "-vf", f"fps={fps},pad=ceil(iw/2)*2:ceil(ih/2)*2",
             "-c:v", "libx264", "-crf", "12", "-preset", "fast", "-threads", "4",
             "-g", max(1, int(fps)), "-pix_fmt", "yuv420p", temporary)
        os.replace(temporary, normalized)
    stream = video_stream(normalized)
    width, height = stream["width"], stream["height"]
    count = int(stream["nb_frames"])
    samples = None
    if args.profile:
        mask = mask_from_profile(args.profile, width, height)
    elif args.mask:
        with Image.open(args.mask) as image:
            mask = np.asarray(image.convert("L").resize((width, height), Image.Resampling.NEAREST)) > 127
    else:
        samples = collect_samples(normalized, width, height, fps, count)
        mask = suggest_mask(samples)
        print(f"Automatic static HUD suggestions cover {mask.mean():.1%} of the frame. Review the preview.", flush=True)
    if args.preview_only:
        samples = samples or collect_samples(normalized, width, height, fps, count)
        overlay = samples[len(samples) // 2].copy()
        overlay[mask] = (overlay[mask].astype(np.float32) * .4 + np.array([150, 20, 140])).clip(0, 255)
        Image.fromarray(overlay).save(output)
        if args.save_mask:
            Image.fromarray(mask.astype(np.uint8) * 255).save(args.save_mask)
        return
    validate_mask(mask)
    if args.save_mask:
        Image.fromarray(mask.astype(np.uint8) * 255).save(args.save_mask)
    devices = ["cpu"]
    model_path = work / "inpaint.pt"
    if args.neural_steps:
        from neural import choose_devices, train
        devices = choose_devices(args.device)
        if not model_path.is_file():
            samples = samples or collect_samples(normalized, width, height, fps, count)
            train(samples, mask, model_path, devices[0], args.neural_steps)
    print(f"Processing {source.name} on {', '.join(devices)}", flush=True)
    del samples
    parts = [(work / f"part-{start:012d}.mp4", start, min(start + args.chunk_frames, count))
             for start in range(0, count, args.chunk_frames)]
    pending = [part for part in parts if not valid_video(part[0], part[2] - part[1])]

    def worker(device, assigned):
        if not assigned:
            return
        model = None
        if args.neural_steps:
            from neural import NeuralFill
            model = NeuralFill(model_path, device)
        fill = TemporalFill(mask, model)
        for target, begin, end in assigned:
            render_part(normalized, target, width, height, fps, count, begin, end, fill, args.crf)

    with ThreadPoolExecutor(max_workers=len(devices)) as executor:
        jobs = [executor.submit(worker, device, pending[index::len(devices)])
                for index, device in enumerate(devices)]
        for job in jobs:
            job.result()
    listing = work / "parts.txt"
    listing.write_text("".join(f"file '{part.name}'\nduration {(end - begin) / float(fps):.9f}\n" for part, begin, end in parts),
                       encoding="utf-8")
    intermediate = output.with_name(f".{output.stem}-{os.getpid()}.partial.mp4")
    try:
        start = float(original.get("start_time", 0)) - float(info["format"].get("start_time", 0))
        audio = next((item for item in info["streams"] if item["codec_type"] == "audio"), {})
        audio_options = ["-c:a", "copy"] if audio.get("codec_name") == "aac" else ["-c:a", "aac", "-b:a", "192k"]
        call("-itsoffset", f"{start:.9f}", "-f", "concat", "-safe", "1", "-i", listing, "-i", source,
             "-map", "0:v:0", "-map", "1:a:0?", "-c:v", "copy", *audio_options,
             "-movflags", "+faststart", intermediate)
        if not valid_video(intermediate, count):
            raise IOError("Output frame count did not match the normalized input.")
        os.replace(intermediate, output)
    finally:
        intermediate.unlink(missing_ok=True)
    print(f"Exported {output}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("videos", type=Path, nargs="+")
    masks = parser.add_mutually_exclusive_group()
    masks.add_argument("--profile", type=Path, help="GhostCapture JSON rectangle profile")
    masks.add_argument("--mask", type=Path, help="PNG mask with white indicating HUD")
    parser.add_argument("--save-mask", type=Path)
    parser.add_argument("--preview-only", action="store_true", help="Write an image showing the suggested mask")
    parser.add_argument("--output-dir", type=Path, default=Path("exports"))
    parser.add_argument("--checkpoints", type=Path, default=Path(".ghostcapture"))
    parser.add_argument("--chunk-frames", type=int, default=120)
    parser.add_argument("--fps", help="Constant frame rate, such as 60 or 30000/1001")
    parser.add_argument("--crf", type=int, default=18)
    parser.add_argument("--device", choices=["auto", "cpu", "mps", "cuda"], default="auto")
    parser.add_argument("--neural-steps", type=int, default=0, help="Train the optional PyTorch fill model on this video")
    parser.add_argument("--clean-checkpoints", action="store_true")
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()
    if args.chunk_frames < 1 or not 0 <= args.crf <= 51 or args.neural_steps < 0:
        parser.error("Chunk size must be positive, CRF must be 0 through 51, and training steps must be nonnegative.")
    if args.save_mask and len(args.videos) != 1:
        parser.error("--save-mask accepts one video at a time.")
    if any(not shutil.which(tool) for tool in ("ffmpeg", "ffprobe")):
        parser.error("Install FFmpeg with libx264 and put ffmpeg and ffprobe on PATH.")
    if len({p.stem for p in args.videos}) != len(args.videos):
        parser.error("Input base names must be unique within a batch.")
    failed = False
    for video in args.videos:
        suffix = "-mask.png" if args.preview_only else "-clean.mp4"
        try:
            render(video, args.output_dir / (video.stem + suffix), args)
        except Exception as error:
            print(f"{video}: {error}", file=sys.stderr)
            failed = True
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
