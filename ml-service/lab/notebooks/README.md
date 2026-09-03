# Notebooks

Five notebooks implement the capstone's ML methodology end to end, meant to be read and run
in numeric order. Each is self-contained — its own Background and Conclusion — and hands off
exactly one artifact to the next. A model's own results (metrics, confusion matrix, feature
importance) are reported only in the notebook that produced them; cross-model comparison
happens exclusively in `05_model_comparison.ipynb`.

Dataset: `lab/data/{train,val,test}/{NORMAL,PNEUMONIA}/`, imbalanced ~2.89:1 toward
`PNEUMONIA` — `train` 1341/3875 (5216 images), `val` 8/8 (16 images, a small fixture used
only for early stopping in 04 / folded into the CV pool in 03), `test` 234/390 (624 images,
touched exactly once per modeling notebook, purely for final evaluation).

## Pipeline

**`01_feature_engineering.ipynb`** — extracts 12 hand-engineered pixel/texture/edge/region
features per X-ray (via `lab/src/features.py`) from the raw image folders above, saving
`lab/data/features/{train,val,test}.csv`. → feeds 02 and 03.

**`02_eda.ipynb`** — purely descriptive: class balance per split, per-feature distributions
(boxplots) by class, an intensity-band pneumonia-rate table, and a feature-correlation
heatmap, all over Phase 1's CSVs. Fits nothing. → its observed feature ranking is
cross-checked against 03's SHAP results.

**`03_ml_modeling.ipynb`** — fits two classical models directly on Phase 1's feature tables
(no raw images, no `lab/src` imports): M4 (Logistic Regression baseline) and M6 (XGBoost
primary, tuned via `RandomizedSearchCV`), selected via 5-fold CV over the pooled `train`+`val`
set, evaluated once on `test`. These exist as baselines to quantify how much transfer
learning gains over hand-engineered features, not as deployment candidates. Saves
`lab/artifacts/predictions/phase3_test_predictions.csv`. → feeds 05.

**`04_dl_cv_transfer_learning.ipynb`** — fine-tunes ResNet18 (`lab/src/model.py`) on raw
pixels via transfer learning, calling `lab/src/train.py`'s `train()` function directly
(same training loop the CLI script uses), evaluated once on `test` with the identical metric
suite as 03. Saves `lab/artifacts/model_best.pt`, `lab/artifacts/model.onnx`, and
`lab/artifacts/predictions/phase4_test_predictions.csv`. → feeds 05.

**`05_model_comparison.ipynb`** — loads both prediction CSVs, recomputes an identical metric
suite for M4, M6, and ResNet18 on the exact same `test` images, and states the ROC-AUC
winner. Trains or fits nothing; the actual promotion steps live in `lab/README.md`'s
"Promoting a model" section, not in this notebook.

## Current result

ResNet18 (04) is the intended production model. As of the last run, on the shared 624-image
`test` split: ResNet18 ROC-AUC 0.9422 vs. M6's 0.8040 vs. M4's 0.7679 — a decisive margin,
detailed in 05's Conclusion.
