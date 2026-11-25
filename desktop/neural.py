"""Train a small fully convolutional fill model using unmasked input patches."""
import os

import numpy as np
import torch
from torch import nn


def network():
    layers = [nn.Conv2d(4, 24, 3, padding=1), nn.ReLU()]
    for dilation in (2, 4, 8, 16, 4, 1):
        layers.extend([nn.Conv2d(24, 24, 3, padding=dilation, dilation=dilation), nn.ReLU()])
    layers.extend([nn.Conv2d(24, 3, 1), nn.Sigmoid()])
    return nn.Sequential(*layers)


def choose_devices(option):
    if option == "cpu":
        return ["cpu"]
    if option in ("auto", "cuda") and torch.cuda.is_available():
        return [f"cuda:{i}" for i in range(torch.cuda.device_count())]
    if option in ("auto", "mps") and torch.backends.mps.is_available():
        return ["mps"]
    if option != "auto":
        raise ValueError(f"Requested {option} device is unavailable.")
    return ["cpu"]


def train(samples, mask, checkpoint, device, steps):
    if checkpoint.is_file():
        return
    height, width = mask.shape
    side = min(96, height, width)
    if side < 16:
        raise ValueError("Video is too small for neural training.")
    rng = np.random.default_rng(37)
    torch.manual_seed(37)
    clean = []
    for _ in range(4096):
        y, x = rng.integers(height - side + 1), rng.integers(width - side + 1)
        if not mask[y:y + side, x:x + side].any():
            clean.append((int(y), int(x)))
        if len(clean) == 256:
            break
    if not clean:
        raise ValueError("No clean training patches found. Reduce the HUD mask or disable neural mode.")
    model = network().to(device)
    optimizer = torch.optim.Adam(model.parameters(), lr=.001)
    for step in range(steps):
        patches, holes = [], []
        for _ in range(6):
            frame = samples[rng.integers(len(samples))]
            y, x = clean[rng.integers(len(clean))]
            patches.append(frame[y:y + side, x:x + side])
            hole = np.zeros((side, side), dtype=np.float32)
            h, w = rng.integers(side // 8, side // 2 + 1, size=2)
            a, b = rng.integers(side - h + 1), rng.integers(side - w + 1)
            hole[a:a + h, b:b + w] = 1
            holes.append(hole)
        target = torch.from_numpy(np.stack(patches)).to(device).permute(0, 3, 1, 2).float() / 255
        hole = torch.from_numpy(np.stack(holes)[:, None]).to(device)
        prediction = model(torch.cat((target * (1 - hole), hole), dim=1))
        loss = ((prediction - target).abs() * hole).sum() / (3 * hole.sum())
        optimizer.zero_grad()
        loss.backward()
        optimizer.step()
        if (step + 1) % 25 == 0 or step + 1 == steps:
            print(f"Neural training {step + 1}/{steps}, masked L1={loss.item():.4f}", flush=True)
    temporary = checkpoint.with_suffix(".partial")
    torch.save({name: parameter.cpu() for name, parameter in model.state_dict().items()}, temporary)
    os.replace(temporary, checkpoint)


class NeuralFill:
    def __init__(self, checkpoint, device):
        self.device = device
        self.model = network()
        self.model.load_state_dict(torch.load(checkpoint, map_location="cpu", weights_only=True))
        self.model.to(device).eval()

    @torch.inference_mode()
    def fill(self, frame, mask):
        output = frame.copy()
        height, width = mask.shape
        tile, context = 160, 45
        for y in range(0, height, tile):
            for x in range(0, width, tile):
                y1, x1 = min(y + tile, height), min(x + tile, width)
                core = mask[y:y1, x:x1]
                if not core.any():
                    continue
                top, left = max(0, y - context), max(0, x - context)
                bottom, right = min(height, y1 + context), min(width, x1 + context)
                pixels = torch.from_numpy(frame[top:bottom, left:right].copy()).to(self.device)
                pixels = pixels.permute(2, 0, 1)[None].float() / 255
                hole = torch.from_numpy(mask[top:bottom, left:right].copy()).to(self.device)[None, None]
                image = torch.cat((pixels * (~hole), hole.float()), dim=1)
                with torch.autocast(device_type="cuda", enabled=self.device.startswith("cuda")):
                    prediction = self.model(image)
                result = prediction[0].float().permute(1, 2, 0).mul(255).round().byte().cpu().numpy()
                crop = result[y - top:y1 - top, x - left:x1 - left]
                output[y:y1, x:x1][core] = crop[core]
        return output
