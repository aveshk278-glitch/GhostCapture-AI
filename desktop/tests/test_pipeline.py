import json
from pathlib import Path
import shutil
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest

import numpy as np

import ghostcapture as cli
from hud import TemporalFill, mask_from_profile, suggest_mask


class HudTests(unittest.TestCase):
    def test_mask_and_unmasked_pixels(self):
        with TemporaryDirectory() as folder:
            profile = Path(folder) / "profile.json"
            profile.write_text(json.dumps({"version": 1, "rectangles": [[.25, .25, .5, .5]]}))
            mask = mask_from_profile(profile, 64, 64)
            self.assertEqual(16 * 16, mask.sum())
            expected = np.full((64, 64, 3), 80, dtype=np.uint8)
            frame = expected.copy()
            frame[mask] = 240
            engine = TemporalFill(mask)
            result = engine.fill(frame, engine.signature(frame), [])
            np.testing.assert_array_equal(result[~mask], expected[~mask])
            np.testing.assert_allclose(result[mask], expected[mask], atol=1)

    def test_translation_and_neighbor_reconstruction(self):
        rng = np.random.default_rng(13)
        clean = rng.integers(0, 255, (128, 128, 3), dtype=np.uint8)
        mask = np.zeros((128, 128), dtype=bool)
        mask[50:54, 40:42] = True
        previous = clean.copy()
        previous[mask] = 255
        shifted = np.roll(clean, (0, 8), axis=(0, 1))
        current = shifted.copy()
        current[mask] = 255
        engine = TemporalFill(mask)
        previous_signature = engine.signature(previous)
        signature = engine.signature(current)
        self.assertEqual((0, 8), engine.translation(signature, previous_signature))
        result = engine.fill(current, signature, [(previous, previous_signature)])
        np.testing.assert_array_equal(result, shifted)

    def test_static_edge_suggestion(self):
        rng = np.random.default_rng(5)
        samples = []
        for _ in range(12):
            frame = rng.integers(10, 145, (120, 120, 3), dtype=np.uint8)
            frame[10:18, 10:18] = 255
            samples.append(frame)
        found = suggest_mask(samples)
        self.assertTrue(found[14, 14])
        self.assertLess(found.sum(), 120 * 120 * .05)


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg is required")
class ExportTests(unittest.TestCase):
    def test_batch_resume_preserves_input_and_audio(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            profile = root / "profile.json"
            profile.write_text(json.dumps({"version": 1, "rectangles": [[.2, .25, .3, .4]]}))
            recordings = [root / "game.mp4", root / "game2.mp4"]
            for path in recordings:
                cli.call("-f", "lavfi", "-i", "testsrc2=s=128x96:r=30000/1001",
                         "-f", "lavfi", "-i", "sine=f=880", "-t", "1.1",
                         "-c:v", "libx264", "-c:a", "aac", path)
            source_hash = cli.digest(recordings[0])
            command = [sys.executable, cli.__file__, *map(str, recordings), "--profile", str(profile),
                       "--chunk-frames", "7", "--output-dir", str(root / "output"),
                       "--checkpoints", str(root / "checkpoints"), "--overwrite"]
            subprocess.run(command, check=True, capture_output=True)
            output = root / "output/game-clean.mp4"
            original = cli.probe(recordings[0])["streams"]
            result = cli.probe(output)["streams"]
            self.assertEqual(original[0]["nb_frames"], result[0]["nb_frames"])
            self.assertEqual(original[1]["nb_frames"], result[1]["nb_frames"])
            self.assertAlmostEqual(float(original[1]["duration"]), float(result[1]["duration"]), places=3)
            self.assertEqual(source_hash, cli.digest(recordings[0]))
            checkpoints = sorted((root / "checkpoints").glob("*/part-*.mp4"))
            self.assertGreater(len(checkpoints), 2)
            removed = checkpoints.pop()
            removed.unlink()
            previous_times = {path: path.stat().st_mtime_ns for path in checkpoints}
            subprocess.run(command, check=True, capture_output=True)
            self.assertTrue(removed.is_file())
            self.assertEqual(previous_times, {path: path.stat().st_mtime_ns for path in checkpoints})
            self.assertTrue((root / "output/game2-clean.mp4").is_file())

    def test_fractional_seek_is_frame_exact(self):
        with TemporaryDirectory() as folder:
            path = Path(folder) / "frame.mp4"
            cli.call("-f", "lavfi", "-i", "testsrc2=s=128x96:r=30000/1001",
                     "-frames:v", "70", "-c:v", "libx264", "-g", "17", path)
            with cli.raw_frames(path, 128, 96) as frames:
                full = list(frames)
            from fractions import Fraction
            for index in (0, 7, 16, 25, 46, 59):
                with cli.raw_frames(path, 128, 96, Fraction(index * 1001, 30000), 5) as frames:
                    selected = list(frames)
                np.testing.assert_array_equal(selected, full[index:index + 5])


if __name__ == "__main__":
    unittest.main()
