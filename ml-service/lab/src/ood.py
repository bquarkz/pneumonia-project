"""Out-of-distribution guardrail for the serving app (see app/model/inference.py).

Confidence alone doesn't flag an out-of-distribution input: 04_dl_cv_transfer_learning.ipynb's
model turns random noise and solid colors into a 93-99% confident "pneumonia" prediction,
since the sigmoid always outputs *something* regardless of how unlike a chest X-ray the
input is. Two independent, cheap checks run before the model:

1. is_grayscale_like -- catches color photos. Verified against every image in
   lab/data/{train,val,test}/ (5856 files, see test_ood.py): this dataset's chest X-rays
   are all stored with R == G == B exactly per pixel (grayscale scans replicated across
   3 channels for JPEG) -- a genuine color photo will not be.
2. knn_distance -- catches grayscale-but-not-an-X-ray content (a photo converted to
   grayscale, random noise, a structureless pattern) that (1) can't see, by measuring
   distance -- in the trained ResNet18's own 512-dim embedding space -- to
   lab/artifacts/train_embeddings.npy (every `train` image's embedding). This is the
   nearest-neighbor method from M4 Unit 5 (K-Nearest Neighbors) -- Euclidean distance on
   standardized features -- applied to anomaly thresholding instead of majority-vote
   classification.
"""

import numpy as np
from PIL import Image

# A few % of pixels are allowed above the per-pixel threshold, and the threshold itself
# has headroom above the 0.0 measured on the real dataset -- both margins absorb JPEG
# re-encoding noise on a legitimate X-ray uploaded through a different pipeline than this
# dataset's, without letting an actual color photo through (every synthetic color image
# tried -- noise, solid colors, a colored gradient -- scored a grayscale fraction < 0.001).
MAX_CHANNEL_STD = 2.0
MIN_GRAYSCALE_FRACTION = 0.95


def is_grayscale_like(image: Image.Image) -> bool:
    """True if `image` is grayscale-like (R ≈ G ≈ B per pixel), as a real chest X-ray is."""
    array = np.asarray(image.convert("RGB"), dtype=np.float32)
    per_pixel_channel_std = array.std(axis=2)
    grayscale_fraction = (per_pixel_channel_std <= MAX_CHANNEL_STD).mean()
    return bool(grayscale_fraction >= MIN_GRAYSCALE_FRACTION)


# A smaller k than M4 Unit 5's classification rule of thumb (k ~= sqrt(n) ~= 72 for this
# project's n=5216 training images) on purpose: that rule balances bias/variance for
# majority-vote classification, not sensitivity to local structure for anomaly
# thresholding, where a smaller k stays closer to genuinely nearby points instead of
# averaging in more distant ones.
K_NEIGHBORS = 10

# Calibrated empirically against lab/artifacts/train_embeddings.npy (5216 x 512): the
# leave-one-out k-NN distance (standardized embeddings, k=10) among training points
# themselves has p99 ~= 17.9, max ~= 20.0 (1500-point sample) -- real held-out `test`
# X-rays scored 11.75-16.42, well inside that band. Confirmed OOD content -- grayscale
# random noise, a checkerboard pattern, a real X-ray with its pixels spatially scrambled
# -- scored 32-36, comfortably clear of this threshold. A smooth grayscale gradient
# scored 18.2, close enough to slip past it -- a known, accepted limitation: this catches
# clearly structureless/unrelated content, not every synthetic edge case, and the
# threshold favors never rejecting a real X-ray over catching every adversarial pattern.
KNN_DISTANCE_THRESHOLD = 20.0


def reference_stats(reference_embeddings: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """Per-dimension mean/std of a reference embedding set, used to standardize before
    distance -- same reason M4 Unit 5 standardizes before k-NN: without it, dimensions
    with more natural spread would dominate the distance regardless of relevance."""
    mean = reference_embeddings.mean(axis=0)
    std = reference_embeddings.std(axis=0) + 1e-6
    return mean, std


def standardize(embeddings: np.ndarray, mean: np.ndarray, std: np.ndarray) -> np.ndarray:
    return (embeddings - mean) / std


def knn_distance(
    query_embedding: np.ndarray,
    reference_embeddings_standardized: np.ndarray,
    mean: np.ndarray,
    std: np.ndarray,
    k: int = K_NEIGHBORS,
) -> float:
    """Mean Euclidean distance from query_embedding to its k nearest neighbors in an
    already-standardized reference set (see reference_stats/standardize)."""
    query_standardized = standardize(query_embedding, mean, std)
    distances = np.linalg.norm(reference_embeddings_standardized - query_standardized[None, :], axis=1)
    distances.sort()
    return float(distances[:k].mean())
