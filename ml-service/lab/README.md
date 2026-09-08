# ml-service/lab — training environment

Research/training environment, separate from the service that runs in `docker compose` (`ml-service/app/`).
Nothing here goes into the Docker image (see `../.dockerignore`) — only the final promoted model.

## Setup

```bash
conda env create -f environment.yml
conda activate pneumonia-lab
jupyter lab   # from ml-service/lab/
```

## Structure

```
lab/
  environment.yml    # conda environment (Jupyter + training libs)
  promote.py          # promotes model.onnx/ood_embedding.onnx + both train_embeddings*.npy
                      # to app/ (see "Promoting a model")
  promote.sh           # conda-activate + run promote.py, from any cwd
  notebooks/          # EDA, experimentation — imports from src/, doesn't reimplement
  src/
    dataset.py         # PneumoniaXrayDataset (expects <dir>/NORMAL, <dir>/PNEUMONIA)
    model.py            # ResNet18 (transfer learning), output: 1 logit = P(pneumonia)
    train.py             # training script + ONNX export
    features.py          # 12 hand-engineered features for 01/03 (+ test_features.py)
    ood.py                # OOD guardrail (grayscale + 2x k-NN embedding distance) —
                          # functions kept in sync in app/model/inference.py, config
                          # published via manifest.yaml (see "Promoting a model") (+ test_ood.py)
  data/
    sample/               # tiny fixture (2 images/split), committed — used by
                          # src/test_features.py, not the training pipeline. Full dataset
                          # does NOT live here (GBs) — see data/sample/README.md.
  artifacts/            # training output (checkpoints, .onnx) — gitignored
```

## Training

```bash
cd src
python train.py --train-dir ../data/train --val-dir ../data/val --epochs 10
```

Each directory (`train`, `val`) needs `NORMAL/` and `PNEUMONIA/` subfolders with JPEGs.
At the end, it generates `lab/artifacts/model_best.pt` (PyTorch checkpoint), `lab/artifacts/model.onnx`
(exported, outputs `logits` + a 512-dim `embedding` — what the service actually loads), and
`lab/artifacts/train_embeddings.npy` (every `train` image's embedding, the reference set the
first of `lab/src/ood.py`'s two k-NN out-of-distribution checks compares against).

It also exports `lab/artifacts/ood_embedding.onnx` — a second, frozen ResNet18 (ImageNet
weights, **not** fine-tuned) used purely as an embedding extractor — and
`lab/artifacts/train_embeddings_pretrained.npy` (every `train` image's embedding in *that*
space). This exists because the fine-tuned classifier's own embedding above is optimized only
to tell NORMAL from PNEUMONIA, so it compresses away which body region is in the image — a real
abdomen/pelvis X-ray landed inside its "normal chest X-ray" band at 99% "pneumonia" confidence
in manual testing. A plain ImageNet-pretrained network was never specialized away from that
signal, so it separates different anatomy far better (see
`04_dl_cv_transfer_learning.ipynb`'s OOD section for the full story and calibration numbers).
This second export is identical every run (fixed ImageNet weights, no training involved).

`04_dl_cv_transfer_learning.ipynb`'s OOD calibration cells then recalibrate both k-NN
thresholds against those two embedding sets and write `lab/artifacts/ood_config.json` — the
guardrail's full parameters (`max_channel_std`, `min_grayscale_fraction`, `k_neighbors`,
`knn_distance_threshold`, `knn_distance_threshold_pretrained`). `promote.py` folds this into
`manifest.yaml`'s `ood` section (see "Promoting a model" below); `app/model/inference.py`
reads that section at startup instead of hardcoding these values.

To regenerate all of the above from an existing checkpoint without retraining (e.g. after
changing `export_artifacts` in `train.py`), pass `--checkpoint`:

```bash
python train.py --train-dir ../data/train --val-dir ../data/val --checkpoint ../artifacts/model_best.pt
```

**Why ONNX and not plain PyTorch for serving:** `onnxruntime` in the `app/` runtime weighs
~15MB against 200MB+ for `torch` (CPU); in a deliverable that needs to run on someone else's
machine without a GPU, that matters. If you prefer plain `torch`, swap this script's final export and
the deps in `../requirements.txt`.

## Promoting a model

`promote.py` copies `artifacts/model.onnx`, `artifacts/ood_embedding.onnx`,
`artifacts/train_embeddings.npy`, and `artifacts/train_embeddings_pretrained.npy` to
`../app/model/artifacts/` and fills in `../app/model/manifest.yaml` — `trained_from_commit`
(`git rev-parse --short HEAD`), `metrics` (recomputed from
`artifacts/predictions/phase4_test_predictions.csv`, the same file
`05_model_comparison.ipynb` reads), and `ood` (copied from `artifacts/ood_config.json`) are
filled in automatically; you only supply `dataset_version` (which dataset/version this model
was trained on). `manifest.yaml` is the one contract the model, its metrics, and its OOD
guardrail config are all published through — `app/model/inference.py` reads all of it at
startup instead of hardcoding a copy of any of these values:

```bash
./promote.sh --dataset-version "<e.g. a Kaggle dataset URL + date>"
```

`promote.sh` activates the `pneumonia-lab` conda env and calls `promote.py` for you, and
works from any cwd. (Calling `python promote.py ...` directly also works, as long as
`pneumonia-lab` is already active.)

Only ResNet18 has an ONNX export path today (see `05_model_comparison.ipynb`'s Decision
cell), so this promotes that model. It requires `artifacts/model.onnx`,
`artifacts/ood_embedding.onnx`, `artifacts/train_embeddings.npy`, and
`artifacts/train_embeddings_pretrained.npy` (all four from `train.py`),
`artifacts/ood_config.json` (from `04_dl_cv_transfer_learning.ipynb`'s OOD calibration
cells), and `artifacts/predictions/phase4_test_predictions.csv` (from that same notebook's
test-evaluation cells) to already exist.

`promote.py` does not commit anything — it prints the commands to run next. Check each
`.onnx`'s size first (`ls -lh`; ≤100MB, `git add` directly works, above that see the Git LFS
discussion — both `.onnx` files here are ResNet18-sized, ~43MB each), then:

```bash
git add -f ../app/model/artifacts/model.onnx ../app/model/artifacts/ood_embedding.onnx \
  ../app/model/artifacts/train_embeddings.npy ../app/model/artifacts/train_embeddings_pretrained.npy \
  ../app/model/manifest.yaml
git commit -m "..."
```

Without a promoted model, the service runs in fallback mode (deterministic pseudo-random
prediction — see `app/model/inference.py`).
