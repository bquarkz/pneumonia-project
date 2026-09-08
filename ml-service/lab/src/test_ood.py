"""Unit tests for lab/src/ood.py.

Run with: pytest lab/src/test_ood.py (from within the pneumonia-lab conda env).

Exercises is_grayscale_like against the small fixture image already committed at
lab/data/sample/Train/ (a real chest X-ray, must pass) and manually constructed color
images (must fail) -- solid color and random-noise RGB arrays, the same kind of input
that was observed turning into a confident "pneumonia" prediction with no guardrail.
"""

import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ood import is_grayscale_like

SAMPLE_XRAY_PATH = (
    Path(__file__).resolve().parent.parent / "data" / "sample" / "Train" / "NORMAL2-IM-1328-0001.jpeg"
)


def test_real_chest_xray_is_grayscale_like():
    image = Image.open(SAMPLE_XRAY_PATH)
    assert is_grayscale_like(image)


def test_solid_color_image_is_not_grayscale_like():
    image = Image.new("RGB", (224, 224), (255, 0, 0))
    assert not is_grayscale_like(image)


def test_random_noise_color_image_is_not_grayscale_like():
    rng = np.random.default_rng(42)
    array = rng.integers(0, 256, (224, 224, 3), dtype=np.uint8)
    image = Image.fromarray(array)
    assert not is_grayscale_like(image)


def test_synthetic_grayscale_image_is_grayscale_like():
    """A non-X-ray image can still be grayscale (e.g. a black-and-white photo) --
    is_grayscale_like only rules out color, it is not a full OOD detector on its own."""
    rng = np.random.default_rng(42)
    single_channel = rng.integers(0, 256, (224, 224), dtype=np.uint8)
    image = Image.fromarray(np.repeat(single_channel[:, :, None], 3, axis=2))
    assert is_grayscale_like(image)
