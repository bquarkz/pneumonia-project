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
  promote.py          # promotes artifacts/model.onnx to serve in app/ (see "Promoting a model")
  promote.sh           # conda-activate + run promote.py, from any cwd
  notebooks/          # EDA, experimentation — imports from src/, doesn't reimplement
  src/
    dataset.py         # PneumoniaXrayDataset (expects <dir>/NORMAL, <dir>/PNEUMONIA)
    model.py            # ResNet18 (transfer learning), output: 1 logit = P(pneumonia)
    train.py             # training script + ONNX export
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
At the end, it generates `lab/artifacts/model_best.pt` (PyTorch checkpoint) and `lab/artifacts/model.onnx`
(exported, which is what the service actually loads).

**Why ONNX and not plain PyTorch for serving:** `onnxruntime` in the `app/` runtime weighs
~15MB against 200MB+ for `torch` (CPU); in a deliverable that needs to run on someone else's
machine without a GPU, that matters. If you prefer plain `torch`, swap this script's final export and
the deps in `../requirements.txt`.

## Promoting a model

`promote.py` copies `artifacts/model.onnx` to `../app/model/artifacts/model.onnx` and fills
in `../app/model/manifest.yaml` — `trained_from_commit` (`git rev-parse --short HEAD`) and
`metrics` (recomputed from `artifacts/predictions/phase4_test_predictions.csv`, the same
file `05_model_comparison.ipynb` reads) are filled in automatically; you only supply
`dataset_version` (which dataset/version this model was trained on):

```bash
./promote.sh --dataset-version "<e.g. a Kaggle dataset URL + date>"
```

`promote.sh` activates the `pneumonia-lab` conda env and calls `promote.py` for you, and
works from any cwd. (Calling `python promote.py ...` directly also works, as long as
`pneumonia-lab` is already active.)

Only ResNet18 has an ONNX export path today (see `05_model_comparison.ipynb`'s Decision
cell), so this promotes that model. It requires `artifacts/model.onnx` (from `train.py` or
`04_dl_cv_transfer_learning.ipynb`) and `artifacts/predictions/phase4_test_predictions.csv`
(from `04_dl_cv_transfer_learning.ipynb`'s test-evaluation cells) to already exist.

`promote.py` does not commit anything — it prints the commands to run next. Check the
`.onnx` size first (`ls -lh`; ≤100MB, `git add` directly works, above that see the Git LFS
discussion), then:

```bash
git add -f ../app/model/artifacts/model.onnx ../app/model/manifest.yaml
git commit -m "..."
```

Without a promoted model, the service runs in placeholder mode (deterministic pseudo-random
prediction — see `app/model/inference.py`), which is the current behavior.
