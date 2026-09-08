"""Out-of-distribution guardrail for the serving app (see app/model/inference.py).

Confidence alone doesn't flag an out-of-distribution input: 04_dl_cv_transfer_learning.ipynb's
model turns random noise and solid colors into a 93-99% confident "pneumonia" prediction,
since the sigmoid always outputs *something* regardless of how unlike a chest X-ray the
input is. Three independent, cheap checks run before the model:

1. is_grayscale_like -- catches color photos. Verified against every image in
   lab/data/{train,val,test}/ (5856 files, see test_ood.py): this dataset's chest X-rays
   are all stored with R == G == B exactly per pixel (grayscale scans replicated across
   3 channels for JPEG) -- a genuine color photo will not be.
2. knn_distance, on lab/artifacts/train_embeddings.npy (the fine-tuned classifier's own
   512-dim embedding of every `train` image) -- catches grayscale-but-not-an-X-ray
   content (a photo converted to grayscale, random noise, a structureless pattern) that
   (1) can't see. This is the nearest-neighbor method from M4 Unit 5 (K-Nearest
   Neighbors) -- Euclidean distance on standardized features -- applied to anomaly
   thresholding instead of majority-vote classification.
3. knn_distance again, but on lab/artifacts/train_embeddings_pretrained.npy (a frozen,
   NOT fine-tuned, ImageNet ResNet18's embedding of the same images) -- catches a
   different anatomical region entirely (e.g. an abdomen/pelvis X-ray), which (2) does
   NOT catch: a classifier fine-tuned only to tell NORMAL from PNEUMONIA compresses away
   body-region signal it never needed, so a wrong-body-part X-ray still lands inside its
   "normal chest X-ray" embedding band. The pretrained network never specialized away
   from that signal (see 04_dl_cv_transfer_learning.ipynb's OOD section for the
   real-world abdomen case this was found from, and the calibration numbers).

The five parameters below (MAX_CHANNEL_STD, MIN_GRAYSCALE_FRACTION, K_NEIGHBORS,
KNN_DISTANCE_THRESHOLD, KNN_DISTANCE_THRESHOLD_PRETRAINED) are published to
app/model/manifest.yaml's `ood` section by promote.py (via config_dict() and
lab/artifacts/ood_config.json) -- app/model/inference.py reads them from the manifest at
startup, not from a hardcoded copy of this module. See lab/README.md's
Training/Promoting sections.
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


def is_grayscale_like(
    image: Image.Image,
    max_channel_std: float = MAX_CHANNEL_STD,
    min_grayscale_fraction: float = MIN_GRAYSCALE_FRACTION,
) -> bool:
    """True if `image` is grayscale-like (R ≈ G ≈ B per pixel), as a real chest X-ray is."""
    array = np.asarray(image.convert("RGB"), dtype=np.float32)
    per_pixel_channel_std = array.std(axis=2)
    grayscale_fraction = (per_pixel_channel_std <= max_channel_std).mean()
    return bool(grayscale_fraction >= min_grayscale_fraction)


# A smaller k than M4 Unit 5's classification rule of thumb (k ~= sqrt(n) ~= 72 for this
# project's n=5216 training images) on purpose: that rule balances bias/variance for
# majority-vote classification, not sensitivity to local structure for anomaly
# thresholding, where a smaller k stays closer to genuinely nearby points instead of
# averaging in more distant ones. Shared by both k-NN checks below (fine-tuned and
# pretrained embedding spaces).
K_NEIGHBORS = 10

# Guardrail 2 (fine-tuned embedding, see train_embeddings.npy). Calibrated empirically
# in 04_dl_cv_transfer_learning.ipynb: the leave-one-out k-NN distance (standardized
# embeddings, k=10) among a 1000-image training sample has p50=13.1, p90=15.2, p95=16.1,
# p99=17.7, max=21.5 -- this threshold is that p99. Confirmed OOD content -- grayscale
# random noise, a checkerboard pattern -- scored ~36, comfortably clear of it; 19 of 20
# sampled real `test` X-rays scored under it (10.7-18.0).
#
# Known, accepted gap: the one real `test` X-ray that did land over this threshold
# (18.0) was a true PNEUMONIA case the model itself would have flagged with 99.95%
# confidence -- the p99 threshold's ~1% false-rejection rate is a deliberate trade
# favoring almost never blocking a real patient over catching every OOD input; raising
# it (e.g. to the observed max, 21.5) trades that back the other way.
#
# This embedding space alone missed two real cases found in manual testing: a solid
# white image (distance 17.4, just under threshold -- any achromatic color trivially
# satisfies is_grayscale_like too) and, more seriously, a real abdomen/pelvis X-ray
# (distance 16.1) mistaken for a chest X-ray at 99% "pneumonia" confidence -- a
# fine-tuned-for-NORMAL/PNEUMONIA classifier's embedding has no reason to preserve
# body-region signal. Both are caught by KNN_DISTANCE_THRESHOLD_PRETRAINED below.
KNN_DISTANCE_THRESHOLD = 17.7

# Guardrail 3 (frozen ImageNet-pretrained embedding, see train_embeddings_pretrained.npy
# -- NOT the fine-tuned classifier). Calibrated the same way, over the full 5216-image
# training set: p50=21.4, p90=24.2, p95=25.1, p99=26.8, max=30.0 -- this threshold is
# that p99. On a 60-image real `test` sample: 0 false rejections (max distance observed
# was 25.8, comfortably under this threshold). Both real cases guardrail 2 missed are
# caught here with margin: the abdomen/pelvis X-ray scored 27.2 (vs. 25.8 max for real
# chest X-rays) and the solid white image scored 42.8 -- a plain ImageNet-pretrained
# network already separates them, since it was never specialized away from general
# visual/anatomical features the way the fine-tuned classifier's embedding was.
KNN_DISTANCE_THRESHOLD_PRETRAINED = 26.8


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


def config_dict(
    knn_distance_threshold: float = KNN_DISTANCE_THRESHOLD,
    knn_distance_threshold_pretrained: float = KNN_DISTANCE_THRESHOLD_PRETRAINED,
) -> dict:
    """The OOD parameters as a plain dict, in the shape 04_dl_cv_transfer_learning.ipynb's
    calibration cells write to lab/artifacts/ood_config.json (passing their freshly
    recalibrated thresholds, since both are specific to that run's own train_embeddings*.npy,
    not this module's defaults) for promote.py to fold into app/model/manifest.yaml. The
    manifest is the actual contract app/model/inference.py reads at startup -- see
    lab/README.md's Training/Promoting sections."""
    return {
        "max_channel_std": MAX_CHANNEL_STD,
        "min_grayscale_fraction": MIN_GRAYSCALE_FRACTION,
        "k_neighbors": K_NEIGHBORS,
        "knn_distance_threshold": knn_distance_threshold,
        "knn_distance_threshold_pretrained": knn_distance_threshold_pretrained,
    }
