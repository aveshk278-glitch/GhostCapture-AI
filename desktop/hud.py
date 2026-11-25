"""Mask validation, static overlay suggestions, and temporal reconstruction."""
import json
from pathlib import Path

import numpy as np
from scipy import ndimage


def mask_from_profile(path: Path, width: int, height: int) -> np.ndarray:
    profile = json.loads(path.read_text(encoding="utf-8"))
    if profile.get("version") != 1 or not isinstance(profile.get("rectangles"), list):
        raise ValueError("Expected a version 1 GhostCapture HUD profile.")
    mask = np.zeros((height, width), dtype=bool)
    for rectangle in profile["rectangles"]:
        left, top, right, bottom = map(float, rectangle)
        if not (0 <= left < right <= 1 and 0 <= top < bottom <= 1):
            raise ValueError(f"Invalid normalized rectangle: {rectangle}")
        x0, y0 = int(left * width), int(top * height)
        x1, y1 = int(np.ceil(right * width)), int(np.ceil(bottom * height))
        mask[y0:y1, x0:x1] = True
    return validate_mask(mask)


def validate_mask(mask):
    if not mask.any():
        raise ValueError("The mask is empty. Supply a profile or inspect the HUD suggestions.")
    if mask.all():
        raise ValueError("The mask covers the entire frame.")
    return mask


def luminance(frame):
    return frame[..., 0] * .299 + frame[..., 1] * .587 + frame[..., 2] * .114


def suggest_mask(samples):
    """Find persistent high-contrast edges in places where adjacent pixels change."""
    gray = np.stack([luminance(frame) for frame in samples]).astype(np.float32)
    dx = ndimage.sobel(gray, axis=2) / 8
    dy = ndimage.sobel(gray, axis=1) / 8
    consistent = np.maximum(np.abs(np.median(dx, axis=0)), np.abs(np.median(dy, axis=0)))
    variation = gray.std(axis=0)
    nearby_motion = ndimage.maximum_filter(variation, size=11)
    candidates = (consistent > 20) & (variation < 12) & (nearby_motion > 19)
    candidates = ndimage.binary_closing(candidates, iterations=2)
    candidates = ndimage.binary_dilation(candidates, iterations=4)
    labels, _ = ndimage.label(candidates)
    sizes = np.bincount(labels.ravel())
    keep = (sizes >= 24) & (sizes < candidates.size * .05)
    keep[0] = False
    detected = ndimage.binary_fill_holes(keep[labels])
    if detected.mean() > .20:
        raise ValueError("Automatic suggestions covered too much of the frame. Use a manual mask.")
    return detected


class TemporalFill:
    def __init__(self, mask, neural=None):
        self.mask = mask
        self.neural = neural
        self.nearest = ndimage.distance_transform_edt(mask, return_distances=False, return_indices=True)
        self.seed_weights = ndimage.gaussian_filter((~mask).astype(np.float32), 5)[mask, None]
        self.stride = max(1, max(mask.shape) // 320)
        self.window = (np.hanning(mask[::self.stride, ::self.stride].shape[0])[:, None]
                       * np.hanning(mask[::self.stride, ::self.stride].shape[1])[None, :])
        self.rows, self.cols = np.where(mask)

    def signature(self, frame):
        small = luminance(frame[::self.stride, ::self.stride])
        visible = ~self.mask[::self.stride, ::self.stride]
        centered = np.where(visible, small - small[visible].mean(), 0)
        return np.fft.rfft2(centered * self.window)

    def translation(self, current, neighbor):
        phase = current * neighbor.conj()
        corr = np.fft.irfft2(phase / np.maximum(np.abs(phase), 1e-6), s=self.window.shape, axes=(0, 1))
        y, x = np.unravel_index(np.argmax(corr), corr.shape)
        if corr[y, x] < .10:
            return None
        if y > corr.shape[0] // 2:
            y -= corr.shape[0]
        if x > corr.shape[1] // 2:
            x -= corr.shape[1]
        if abs(y) > corr.shape[0] * .18 or abs(x) > corr.shape[1] * .18:
            return None
        return y * self.stride, x * self.stride

    def fill(self, frame, signature, neighbors):
        color = np.zeros((len(self.rows), 3), dtype=np.float32)
        used = np.zeros(len(self.rows), dtype=np.float32)
        for other, other_signature in neighbors:
            shift = self.translation(signature, other_signature)
            if shift is None or shift == (0, 0):
                continue
            y, x = shift
            height, width = self.mask.shape
            sample_y = np.clip(self.rows - y, 0, height - 1)
            sample_x = np.clip(self.cols - x, 0, width - 1)
            valid = ((self.rows - y == sample_y) & (self.cols - x == sample_x)
                     & ~self.mask[sample_y, sample_x])
            # Reject translations whose visible overlap disagrees, such as at a scene cut.
            probe_y = np.arange(max(y, 0), min(height, height + y), 8)
            probe_x = np.arange(max(x, 0), min(width, width + x), 8)
            yy, xx = np.ix_(probe_y, probe_x)
            visible = ~self.mask[yy, xx] & ~self.mask[yy - y, xx - x]
            if not visible.any():
                continue
            delta = np.abs(frame[yy, xx][visible].astype(np.float32) - other[yy - y, xx - x][visible])
            if delta.mean() > 26:
                continue
            color[valid] += other[sample_y[valid], sample_x[valid]]
            used[valid] += 1

        output = frame.copy()
        missing = used == 0
        output[self.rows[~missing], self.cols[~missing]] = np.rint(color[~missing] / used[~missing, None])
        if missing.any():
            remaining = np.zeros_like(self.mask)
            remaining[self.rows[missing], self.cols[missing]] = True
            if self.neural:
                fallback = self.neural.fill(output, remaining)[self.mask]
            else:
                fallback = self.spatial(frame)
            output[self.rows[missing], self.cols[missing]] = fallback[missing]
        return output

    def spatial(self, frame):
        try:
            import cv2
        except ImportError:
            valid = (~self.mask).astype(np.float32)
            nearby = ndimage.gaussian_filter(frame.astype(np.float32) * valid[..., None], (5, 5, 0))[self.mask]
            nearest = frame[self.nearest[0][self.mask], self.nearest[1][self.mask]]
            return np.where(self.seed_weights > 1e-5,
                            nearby / np.maximum(self.seed_weights, 1e-5), nearest).clip(0, 255).astype(np.uint8)
        return cv2.inpaint(frame, self.mask.astype(np.uint8), 3, cv2.INPAINT_TELEA)[self.mask]
