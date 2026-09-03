"""Unit tests for lab/src/features.py.

Run with: pytest lab/src/test_features.py (from within the pneumonia-lab conda env).

Exercises the feature-extraction functions against the small fixture image already
committed at lab/data/sample/Train/, plus two manually constructed edge-case arrays
(a uniform image and a mirror-symmetric image) that lock down known/expected behavior
of the GLCM texture computation and the left-right symmetry computation.
"""

import math
import sys
from pathlib import Path

import numpy as np

# lab/src has its own __init__.py (making it importable as a package from lab/), but
# features.py and dataset.py use bare, flat imports of each other. Explicitly put this
# file's own directory on sys.path so `from features import ...` / `from dataset import
# ...` resolve the same way here regardless of how pytest itself imports this module.
sys.path.insert(0, str(Path(__file__).resolve().parent))

from dataset import IMG_SIZE
from features import extract_features, extract_region_features, extract_texture_features

SAMPLE_IMAGE_PATH = (
    Path(__file__).resolve().parent.parent
    / "data"
    / "sample"
    / "Train"
    / "NORMAL2-IM-1328-0001.jpeg"
)

# The 12 documented feature keys (pixel + texture + edge + region), independent of
# Image_ID/File_Path which extract_features attaches separately.
FEATURE_KEYS = {
    "Mean_Intensity",
    "Std_Intensity",
    "Min_Intensity",
    "Max_Intensity",
    "Contrast",
    "Homogeneity",
    "Energy",
    "Entropy",
    "Edge_Density",
    "Contour_Count",
    "Brightness_Dist",
    "Symmetry",
}


def test_extract_features_returns_all_keys_with_finite_values():
    """extract_features must return exactly the 12 documented feature keys plus
    Image_ID/File_Path, and every numeric feature value must be finite (no NaN/inf)."""
    result = extract_features(SAMPLE_IMAGE_PATH)

    assert isinstance(result, dict)
    assert set(result.keys()) == FEATURE_KEYS | {"Image_ID", "File_Path"}
    assert result["Image_ID"] == SAMPLE_IMAGE_PATH.name
    assert result["File_Path"] == str(SAMPLE_IMAGE_PATH)

    for key in FEATURE_KEYS:
        value = result[key]
        assert isinstance(value, (int, float))
        assert math.isfinite(value), f"{key} is not finite: {value}"


def test_extract_texture_features_uniform_image_is_degenerate_glcm():
    """A uniform (all-black) image collapses the GLCM to a single occupied cell: zero
    contrast, maximal homogeneity, maximal energy. This is a known/expected edge case
    worth locking down, not a bug."""
    gray = np.zeros((IMG_SIZE, IMG_SIZE), dtype=np.uint8)

    result = extract_texture_features(gray)

    assert result["Contrast"] == 0.0
    assert result["Homogeneity"] == 1.0
    assert result["Energy"] == 1.0


def test_extract_region_features_mirror_symmetric_image_has_perfect_symmetry():
    """A manually constructed left-right mirror-symmetric image must score
    Symmetry == 1.0 exactly."""
    left_half = np.arange(100, dtype=np.uint8).reshape(10, 10)
    mirrored = np.concatenate([left_half, np.fliplr(left_half)], axis=1)

    result = extract_region_features(mirrored)

    assert result["Symmetry"] == 1.0
