# Training sample

Unlike `sample-data/` (repo root — images for testing upload through the running
application), this folder is a tiny fixture used by `lab/src/test_features.py`'s unit tests
for `lab/src/features.py`'s feature-extraction functions — not a sample for exercising
`lab/src/train.py`'s training pipeline.

Actual structure — one `NORMAL` image and one `PNEUMONIA` image per split (class identified
by filename, per this dataset's own naming: `NORMAL*` vs. `person<N>_bacteria_*`/
`person<N>_virus_*`):

```
lab/data/sample/
  Train/
    NORMAL2-IM-1328-0001.jpeg
    person9_bacteria_38.jpeg
  Val/
    NORMAL2-IM-1427-0001.jpeg
    person1946_bacteria_4874.jpeg
  Test/
    NORMAL2-IM-0297-0001.jpeg
    person99_bacteria_473.jpeg
```

`test_features.py` only reads `Train/NORMAL2-IM-1328-0001.jpeg` directly, by path; `Val/` and
`Test/` exist for parity but aren't consumed by any test yet.

This layout is **not** compatible with `PneumoniaXrayDataset` / `train.py --train-dir`, which
expect `<dir>/NORMAL/*.jp*g` and `<dir>/PNEUMONIA/*.jp*g` subfolders — for that, point at the
full dataset under `lab/data/{train,val,test}/` (already covered by `.gitignore`, download
separately).

TODO: cite the exact source/license of the dataset used here, with a link, before final delivery.
