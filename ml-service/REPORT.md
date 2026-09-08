# ML Service Development Report

Chest X-ray pneumonia detection — from transfer learning through a production-shaped,
out-of-distribution-aware serving pipeline. This report walks through what was built, in the
order it was built, including the problems found along the way and how each was solved. It is
written as source material for a presentation, not as API documentation.

## 1. Goal

Classify a chest X-ray as `NORMAL` or `PNEUMONIA`. Two modeling approaches were compared on
the same held-out test split:

- **Phase 3 — classical ML** on twelve hand-engineered pixel/texture/edge/region features:
  M4 (Logistic Regression, baseline) and M6 (XGBoost, primary), selected via cross-validation.
- **Phase 4 — deep learning** via transfer learning: a ResNet18 fine-tuned directly on raw
  pixels.

M4 and M6 exist as baselines to quantify how much transfer learning gains over
hand-engineered features — the deep learning model was always the intended production model,
subject to actually winning the comparison.

## 2. Training the Model

**Architecture.** `torchvision`'s ResNet18, pretrained on ImageNet, with its final layer
replaced by a single logit output (`P(pneumonia)`). Fine-tuned on the project's `train` split
(5216 images, `NORMAL`/`PNEUMONIA`, ~2.89:1 imbalanced), validated against `val` (18 images).

**Training run.** 10 epochs, Adam optimizer, `lr=1e-4`, `batch_size=32`, on Apple Silicon
(`mps` backend, ~1 minute/epoch).

**Result on the untouched, 624-image `test` split:**

| Metric | Value |
|---|---|
| Accuracy | 0.8301 |
| Precision | 0.7874 |
| Recall | 0.9974 |
| F1 | 0.8801 |
| ROC-AUC | 0.9422 |

Confusion matrix: 129 true negatives, 105 false positives, **1 false negative**, 389 true
positives. In a screening context, Recall is the metric that matters most — missing a true
positive is more costly than a false alarm — and this model misses only 1 of 390 PNEUMONIA
cases.

**Comparison against the classical baselines** (`05_model_comparison.ipynb`):

| Model | Accuracy | Precision | Recall | F1 | ROC-AUC |
|---|---|---|---|---|---|
| M4 — Logistic Regression | 0.7372 | 0.7665 | 0.8333 | 0.7985 | 0.7679 |
| M6 — XGBoost | 0.7420 | 0.7585 | 0.8615 | 0.8067 | 0.8040 |
| **ResNet18 (winner)** | **0.8301** | **0.7874** | **0.9974** | **0.8801** | **0.9422** |

ResNet18 won decisively on every metric, especially Recall — the failure mode that matters
most for screening.

### Finding: the validation-based early stopping never actually did anything

`train.py` tracks `val_auc` each epoch and keeps the checkpoint with the best score. The
training log showed `val_auc` reaching its ceiling of **1.0000 at epoch 1** and staying there
through epoch 10 — the 18-image `val` split is small enough to be perfectly separable, so past
epoch 1 it stopped providing any signal to distinguish between checkpoints. Combined with the
script's strict `>` comparison (a later epoch only overwrites the checkpoint if its `val_auc`
is *higher*, not merely equal), the checkpoint actually promoted was **epoch 1's**, not one
chosen by genuine competition across all 10 epochs. That epoch 1's weights still generalized
this well to `test` (ROC-AUC 0.9422) says more about how fast ImageNet-pretrained features
adapt to this task than about the early-stopping logic, which never got exercised.

### Finding: training turned out to be incidentally deterministic

`train.py` has no `--seed` flag. But when called from inside the notebook, it inherits
`torch`'s already-seeded global RNG state (the notebook seeds `torch` for its own test-time
use) — so re-running the training cell reproduced the *exact same* test metrics, down to the
confusion matrix, across multiple runs. Not a designed feature, but a useful, documented
observation. (Caveat found later: this only holds if the kernel is restarted — or at least the
seeding cell re-run — immediately before training. Re-running the training cell a second time
in the same kernel session, without resetting `torch`'s RNG state first, silently produces a
genuinely different model, since the RNG has already advanced.)

### Finding: we had forgotten to account for class imbalance in the ResNet18 fine-tune

Manual testing surfaced this one, not a review of the code: a known, genuinely `NORMAL` sample
image (`NORMAL2-IM-0297-0001.jpeg`) uploaded to the running service came back flagged
`PNEUMONIA` (58%, later 65.5% on a re-trained checkpoint — both borderline, neither confident).
Checking it against numbers already on hand, this wasn't a one-off: the test-set confusion
matrix already recorded **105 of 234 real `NORMAL` images (44.9%) as false positives** at the
0.5 threshold — Precision 0.7874 against Recall 0.9974 was already telling this story, just not
yet connected to a root cause.

The root cause, once looked for: `train.py` fine-tunes with plain `torch.nn.BCEWithLogitsLoss()`
— no correction at all for `train`'s ~2.89:1 imbalance toward `PNEUMONIA` (3875 vs. 1341
images). Phase 3's own two models handle this exact imbalance explicitly — M4 with
`class_weight="balanced"`, M6 with `scale_pos_weight` — but the same correction was never
carried over to the ResNet18 fine-tune. An unweighted loss on an imbalanced training set biases
the decision boundary toward the majority class, which matches the symptom exactly: extremely
high Recall, mediocre Precision, driven by over-predicting `PNEUMONIA`.

**Fix implemented:** `lab/src/train.py` now computes `pos_weight` directly from `train_dir`
(`num_normal / num_pneumonia`, the same ratio M6's `scale_pos_weight` computes from its own
pool — recomputed fresh, not copied from Phase 3's numbers) and passes it to
`BCEWithLogitsLoss(pos_weight=...)`. For this project's `train` split, that value is **0.346**.
Since `PNEUMONIA` is the *majority* class here (unlike the more typical case this parameter is
used for, where the positive class is rare), a `pos_weight` below 1 downweights `PNEUMONIA`'s
loss contribution — the correct direction to counter the bias. Retraining with this change, and
re-measuring Precision/Recall/the confusion matrix, was pending at the time of writing this
report (see `04_dl_cv_transfer_learning.ipynb`/`05_model_comparison.ipynb` for whichever numbers
are current).

## 3. Engineering Problems Found During Training & Export

Several environment/tooling issues surfaced while getting the model trained and exported, in
the order they were hit:

- **MPS + `float64` incompatibility.** The dataset's label loader returned a plain Python
  `float`, which PyTorch's default batch-collation silently promotes to `float64`. Apple's MPS
  backend cannot move `float64` tensors to the GPU at all, so training crashed the moment a
  batch of labels reached `.to(device)`. Fixed by returning `torch.tensor(label,
  dtype=torch.float32)` instead of a raw Python float.
- **ONNX export churn (torch 2.11).** A very recent PyTorch version changed
  `torch.onnx.export`'s defaults in several ways:
  - It now needs `onnxscript` importable even for a "simple" export (added as a dependency).
  - It defaults to writing model weights to a *separate* `model.onnx.data` file — the serving
    code only loads `model.onnx` — so exports had to pass `external_data=False` to keep
    everything in one file.
  - It silently refuses opset 17 and upgrades to opset 18 regardless of what's requested;
    the script was updated to just ask for 18 explicitly.
- **Reproducing without retraining.** Once the export code changed, the already-trained
  checkpoint needed to be re-exported to pick up the new ONNX behavior. `train.py` gained a
  `--checkpoint` flag: skip training entirely and re-export ONNX/embeddings from an existing
  checkpoint, avoiding a wasted ~10-minute retrain every time the export logic changed.

## 4. Deploying the Model — the Promotion Pipeline

`lab/promote.py` (with a `promote.sh` convenience wrapper) copies the trained ONNX model and
its supporting artifacts into `app/model/artifacts/` and writes `app/model/manifest.yaml`:
`trained_from_commit` (from git), `metrics` (recomputed from the saved test predictions CSV,
never copy-pasted from a notebook's printed output), and `dataset_version` (supplied by the
person promoting).

**Design principle adopted partway through:** the model, its metrics, and (later) its
out-of-distribution guardrail configuration should move together as **one published contract**
— `manifest.yaml` — rather than the serving code hardcoding copies of values that originate in
training. `app/model/inference.py` reads everything it needs from the manifest at startup; if
the manifest is missing something a promoted model claims to have, the service crashes loudly
at startup rather than silently degrading.

## 5. The Out-of-Distribution Problem

### 5.1 Discovery: confidence is not a safety net

The model's sigmoid always outputs *something*, regardless of how unlike a chest X-ray the
input is. Testing with synthetic images made this concrete and uncomfortable:

| Input | P(pneumonia) |
|---|---|
| Random color noise | 99.6% |
| Solid red | 93.5% |
| Solid white | 97.9% |

None of these are remotely related to a chest X-ray. Nothing in the serving path was stopping
them from producing a confident, meaningless diagnosis.

### 5.2 Guardrail 1 — grayscale check

Observation: every chest X-ray in this dataset (verified across all 5856 images in
`train`/`val`/`test`) is stored with `R == G == B` **exactly** per pixel — a grayscale scan
replicated across three JPEG channels. A genuine color photo will not be.

`is_grayscale_like()` measures, per pixel, the standard deviation across the R/G/B channels
and rejects the image if too many pixels exceed a small tolerance (calibrated with a wide
margin: real X-rays score ~0 everywhere; every synthetic color image tried scored a grayscale
fraction under 0.1%).

This caught the color-noise and solid-red cases above. It did **not** catch solid white —
white is technically an achromatic color, so `R == G == B` holds trivially.

### 5.3 Guardrail 2 — k-NN distance in the fine-tuned model's own embedding space

A grayscale image that isn't an X-ray at all (random noise, a repeating pattern) still passes
guardrail 1. The fix: measure how far a new image's embedding sits from its nearest neighbors
among every `train` image's embedding, in the *fine-tuned classifier's own* 512-dim
penultimate-layer feature space. This is the k-nearest-neighbors method from the program's own
curriculum (M4 Unit 5) — Euclidean distance on standardized features — reused here for anomaly
thresholding instead of majority-vote classification.

- `k = 10`, deliberately smaller than the course's classification rule of thumb
  (`k ≈ sqrt(n) ≈ 72` for 5216 training images): that rule balances bias/variance for
  majority-vote classification, not sensitivity to local structure for anomaly thresholding.
- Threshold calibrated as the 99th percentile of the leave-one-out k-NN distance among the
  training images themselves (i.e. "how far does a genuine training image typically sit from
  its own nearest neighbors?"): **17.7**.
- Validated: random noise and a checkerboard pattern scored ~36 (comfortably rejected); the
  large majority of real held-out `test` X-rays scored well under the threshold.

**Cost, measured honestly:** at the p99 threshold, roughly **1% of genuine test X-rays** were
themselves rejected as false positives — a deliberate trade favoring "almost never block a real
patient" over "catch every possible anomaly."

**Known gap at this point:** solid white passed *both* guardrails (grayscale check trivially,
and its k-NN distance in this space landed just under the threshold).

### 5.4 The serious failure found in manual testing: wrong body part, high confidence

This is the finding that mattered most. During manual QA testing (not a synthetic exercise), a
real **abdomen/pelvis X-ray** — grayscale, genuine radiograph texture, correct imaging
modality, just the wrong body part — was uploaded to the running service and came back:

> **"pneumonia" at 99.4% confidence.**

Neither guardrail caught it:

- It **is** grayscale (real X-rays of any body part are), so guardrail 1 passed it.
- Its k-NN distance in the fine-tuned classifier's embedding space was **16.1** — comfortably
  under the 17.7 threshold, so guardrail 2 passed it too.

**Root cause.** The fine-tuned classifier's embedding was optimized for exactly one thing:
telling NORMAL chest X-rays from PNEUMONIA chest X-rays. It was never given a reason to
preserve *which body region* is in the image, and evidently didn't — an abdomen X-ray's
texture/contrast statistics are similar enough to a chest X-ray's that it lands inside the
"normal-looking" band of that embedding space. This is a materially more serious failure than
the solid-white gap: it is a plausible real upload silently producing a confident, wrong
clinical result, not a synthetic edge case.

### 5.5 Guardrail 3 — anatomical-region check via a frozen, pretrained embedding

The fix reuses the identical k-NN method, over a **different** embedding source: a second
ResNet18 that keeps its original ImageNet weights and is **never fine-tuned**. Because it was
never pushed to specialize on lung texture, it retains more general-purpose, anatomically
relevant visual features.

- Exported as a second ONNX file (`ood_embedding.onnx`) alongside the classifier — fixed
  weights, so this export is identical on every training run and does not depend on the
  fine-tuning outcome at all.
- A second reference embedding set (`train_embeddings_pretrained.npy`) computed the same way.
- Calibrated the same way as guardrail 2 (leave-one-out k-NN distance, p99 threshold): **26.8**.

**Result:**

| Case | Fine-tuned space (guardrail 2) | Pretrained space (guardrail 3) |
|---|---|---|
| Abdomen/pelvis X-ray | 16.1 → **accepted** (bug) | 27.4 → **rejected** (fixed) |
| Solid white | 17.4 → accepted (known gap) | ~43 → **rejected** (bonus fix) |
| Skull X-ray | 23.4 → rejected | ~41 → rejected |
| Random noise / checkerboard | rejected | rejected |
| Real `test` X-rays | mostly accepted | mostly accepted |

Guardrail 3 independently closed both the abdomen failure *and* the solid-white gap guardrail
2 alone had left open.

**Cost, measured honestly (again).** Guardrail 2 and guardrail 3 are each independently
calibrated to a ~1% false-rejection rate on real X-rays. Running both and rejecting if
*either* fires does not stay near 1% — a real X-ray only has to fail one of the two checks.
Measured on a 200–300 image real `test` sample, the **combined false-rejection rate lands
around 2–3%**. This is a genuine trade-off: more wrong-body-part (or otherwise anomalous)
uploads get caught, at the cost of more real patients occasionally needing to re-submit a
rejected upload. Given the alternative — a wrong body part silently returning a confident,
wrong diagnosis — the project's judgment is that this trade is worth it for a screening tool,
but it is a judgment call, not a free win, and is documented as such rather than glossed over.

### 5.6 Publishing the guardrail as part of the model's contract

All five OOD parameters (`max_channel_std`, `min_grayscale_fraction`, `k_neighbors`, and both
k-NN thresholds) are written by the training notebook to `ood_config.json`, folded by
`promote.py` into `manifest.yaml`'s `ood` section, and read by `app/model/inference.py` at
startup — no serving-side hardcoded copies of values that are actually properties of a specific
trained model and its calibration run.

## 6. Current System (as of `model_version: 4`)

`POST /predict` now runs, in order, before any classification happens:

1. **Grayscale check** — rejects color images.
2. **k-NN, fine-tuned embedding** — rejects grayscale-but-structureless content (noise,
   patterns) the classifier's own feature space flags as unlike anything in training.
3. **k-NN, frozen pretrained embedding** — rejects the wrong anatomical region, using a
   feature space that was never specialized away from general visual/anatomical structure.

Any rejection returns **HTTP 422** with a human-readable reason, instead of a confident,
wrong `pneumonia` result. A missing or corrupt promoted model/config still fails loudly at
startup, by design — the service either serves a real, fully-configured model or falls back to
an explicitly-labeled, non-diagnostic placeholder mode, never a silently degraded guess.

## 7. Known Limitations / Future Work

- **Combined false-rejection rate (~2–3%)** on real X-rays is a measured, accepted cost of
  guardrails 2+3 together — not eliminated, only documented and judged worthwhile.
- **No dedicated body-part classifier.** Guardrail 3 is a general-purpose anomaly detector
  repurposed for this specific gap; a model trained explicitly to classify anatomical region
  would likely separate chest-vs-other X-rays more cleanly, at the cost of needing labeled
  data this project does not currently have.
- **Small `val` split (18 images)** means early stopping never meaningfully discriminated
  between epochs in the one training run analyzed — a larger validation set would make that
  mechanism actually load-bearing.
- **`train.py` is not seeded by design**, so exact reproducibility across environments is
  incidental (inherited from a caller's own RNG state) rather than guaranteed.
