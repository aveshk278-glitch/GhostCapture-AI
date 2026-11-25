import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import numpy as np


@unittest.skipUnless(importlib.util.find_spec("torch"), "Optional PyTorch dependency is absent")
class NeuralTests(unittest.TestCase):
    def test_train_checkpoint_and_cpu_inference(self):
        import torch
        from neural import NeuralFill, choose_devices, train

        torch.set_num_threads(2)
        rng = np.random.default_rng(19)
        frame = rng.integers(0, 255, (100, 156, 3), dtype=np.uint8)
        mask = np.zeros((100, 156), dtype=bool)
        mask[30:36, 8:14] = True
        self.assertEqual(["cpu"], choose_devices("cpu"))
        with TemporaryDirectory() as folder:
            weights = Path(folder) / "weights.pt"
            train([frame], mask, weights, "cpu", 2)
            self.assertTrue(weights.is_file())
            original_time = weights.stat().st_mtime_ns
            train([frame], mask, weights, "cpu", 2)
            self.assertEqual(original_time, weights.stat().st_mtime_ns)
            result = NeuralFill(weights, "cpu").fill(frame, mask)
            np.testing.assert_array_equal(frame[~mask], result[~mask])
            self.assertFalse(np.array_equal(frame[mask], result[mask]))
