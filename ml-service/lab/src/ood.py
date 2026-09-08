"""Out-of-distribution guardrail for the serving app (see app/model/inference.py).

Confidence alone doesn't flag an out-of-distribution input: 04_dl_cv_transfer_learning.ipynb's
model turns random noise and solid colors into a 93-99% confident "pneumonia" prediction,
since the sigmoid always outputs *something* regardless of how unlike a chest X-ray the
input is. This is a cheap, separate check on the raw image, run before the model.

Verified against every image in lab/data/{train,val,test}/ (5856 files, see test_ood.py):
this dataset's chest X-rays are all stored with R == G == B exactly per pixel (grayscale
scans replicated across 3 channels for JPEG) -- a genuine color photo will not be.
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
