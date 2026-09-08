"""Unit tests for lab/src/ood.py.

Run with: pytest lab/src/test_ood.py (from within the pneumonia-lab conda env).

Exercises is_grayscale_like against the small fixture image already committed at
lab/data/sample/Train/ (a real chest X-ray, must pass) and manually constructed color
images (must fail) -- solid color and random-noise RGB arrays, the same kind of input
that was observed turning into a confident "pneumonia" prediction with no guardrail.
Also exercises the k-NN distance helpers against small, manually constructed embedding
clusters -- not the real 512-dim lab/artifacts/train_embeddings.npy, which is a training
artifact and may not exist in a fresh checkout.
"""

import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ood import is_grayscale_like, knn_distance, reference_stats, standardize

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


def test_reference_stats_and_standardize_center_and_scale_the_reference_set():
    rng = np.random.default_rng(1)
    reference = rng.normal(loc=5.0, scale=2.0, size=(500, 4))
    mean, std = reference_stats(reference)
    standardized = standardize(reference, mean, std)
    assert np.allclose(standardized.mean(axis=0), 0.0, atol=0.05)
    assert np.allclose(standardized.std(axis=0), 1.0, atol=0.05)


def test_knn_distance_ranks_a_reference_point_closer_than_one_far_outside_the_cluster():
    rng = np.random.default_rng(0)
    reference = rng.normal(loc=0.0, scale=1.0, size=(200, 8))
    mean, std = reference_stats(reference)
    reference_standardized = standardize(reference, mean, std)

    near_query = reference[0]  # an actual reference point
    far_query = np.full(8, 100.0)  # far outside the reference cluster

    near_distance = knn_distance(near_query, reference_standardized, mean, std, k=5)
    far_distance = knn_distance(far_query, reference_standardized, mean, std, k=5)
    assert near_distance < far_distance


def test_knn_distance_is_large_for_a_point_far_outside_the_reference_cluster():
    rng = np.random.default_rng(0)
    reference = rng.normal(loc=0.0, scale=1.0, size=(200, 8))
    mean, std = reference_stats(reference)
    reference_standardized = standardize(reference, mean, std)

    query = np.full(8, 100.0)  # far from the reference cluster
    assert knn_distance(query, reference_standardized, mean, std, k=5) > 10.0
