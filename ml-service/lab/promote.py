"""Promotes lab/artifacts/model.onnx to serve predictions in ml-service/app/.

Copies the classifier's ONNX export (logits + embedding), the frozen OOD embedding
extractor's ONNX export (ood_embedding.onnx, ImageNet-pretrained, not fine-tuned), and
both of their training-set reference embeddings (lab/src/ood.py's two k-NN
out-of-distribution checks each need their own) and fills in
app/model/manifest.yaml with the commit, dataset version, the test-set metrics
recomputed from lab/artifacts/predictions/phase4_test_predictions.csv (the same file
05_model_comparison.ipynb reads), and the OOD guardrail's config (read from
lab/artifacts/ood_config.json, written by 04_dl_cv_transfer_learning.ipynb's
calibration cell) — so app/model/inference.py can read its grayscale/k-NN parameters
from the manifest instead of hardcoding a copy of them. The manifest is the one
contract the model, its metrics, and its OOD config are all published through
together; nothing here is copy-pasted from a notebook's printed output.

Only ResNet18 has an ONNX export path today (see 05_model_comparison.ipynb's Decision
cell) — this script promotes that model only.

Usage (from anywhere; paths resolve relative to this file):
    python promote.py --dataset-version <str> [--model-version <str>]

Does not commit anything — it prints the exact `git add`/`git commit` commands to run next.
"""

import argparse
import json
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
import yaml
from sklearn.metrics import precision_score, recall_score, roc_auc_score

LAB_DIR = Path(__file__).resolve().parent
ONNX_SRC = LAB_DIR / "artifacts" / "model.onnx"
EMBEDDINGS_SRC = LAB_DIR / "artifacts" / "train_embeddings.npy"
OOD_ONNX_SRC = LAB_DIR / "artifacts" / "ood_embedding.onnx"
OOD_EMBEDDINGS_SRC = LAB_DIR / "artifacts" / "train_embeddings_pretrained.npy"
OOD_CONFIG_SRC = LAB_DIR / "artifacts" / "ood_config.json"
PREDICTIONS_CSV = LAB_DIR / "artifacts" / "predictions" / "phase4_test_predictions.csv"
APP_MODEL_DIR = LAB_DIR.parent / "app" / "model"
ONNX_DST = APP_MODEL_DIR / "artifacts" / "model.onnx"
EMBEDDINGS_DST = APP_MODEL_DIR / "artifacts" / "train_embeddings.npy"
OOD_ONNX_DST = APP_MODEL_DIR / "artifacts" / "ood_embedding.onnx"
OOD_EMBEDDINGS_DST = APP_MODEL_DIR / "artifacts" / "train_embeddings_pretrained.npy"
MANIFEST_PATH = APP_MODEL_DIR / "manifest.yaml"
MANIFEST_HEADER = "# ml-service/lab/README.md.\n"


def compute_test_metrics(threshold=0.5):
    df = pd.read_csv(PREDICTIONS_CSV)
    y_true = df["y_true"]
    y_pred_proba = df["y_pred_proba_resnet18"]
    y_pred = (y_pred_proba >= threshold).astype(int)
    return {
        "auc": round(float(roc_auc_score(y_true, y_pred_proba)), 4),
        "recall": round(float(recall_score(y_true, y_pred)), 4),
        "precision": round(float(precision_score(y_true, y_pred)), 4),
    }


def git_commit_hash():
    result = subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"],
        cwd=LAB_DIR,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        sys.exit(f"git rev-parse failed: {result.stderr.strip()}")
    return result.stdout.strip()


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--dataset-version",
        required=True,
        help="which dataset/version this model was trained on (e.g. a Kaggle dataset URL + date)",
    )
    parser.add_argument(
        "--model-version",
        help="defaults to 'resnet18-<short commit hash>'",
    )
    return parser.parse_args()


def main():
    args = parse_args()

    if not ONNX_SRC.exists():
        sys.exit(f"{ONNX_SRC} not found — run lab/src/train.py or 04_dl_cv_transfer_learning.ipynb first.")
    if not EMBEDDINGS_SRC.exists():
        sys.exit(f"{EMBEDDINGS_SRC} not found — run lab/src/train.py first (it's produced alongside model.onnx).")
    if not OOD_ONNX_SRC.exists() or not OOD_EMBEDDINGS_SRC.exists():
        sys.exit(f"{OOD_ONNX_SRC} / {OOD_EMBEDDINGS_SRC} not found — run lab/src/train.py first.")
    if not OOD_CONFIG_SRC.exists():
        sys.exit(f"{OOD_CONFIG_SRC} not found — run 04_dl_cv_transfer_learning.ipynb's OOD calibration cells first.")
    if not PREDICTIONS_CSV.exists():
        sys.exit(f"{PREDICTIONS_CSV} not found — run 04_dl_cv_transfer_learning.ipynb's test-evaluation cells first.")

    metrics = compute_test_metrics()
    ood_config = json.loads(OOD_CONFIG_SRC.read_text())
    commit = git_commit_hash()
    model_version = args.model_version or f"resnet18-{commit}"

    ONNX_DST.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ONNX_SRC, ONNX_DST)
    shutil.copy2(EMBEDDINGS_SRC, EMBEDDINGS_DST)
    shutil.copy2(OOD_ONNX_SRC, OOD_ONNX_DST)
    shutil.copy2(OOD_EMBEDDINGS_SRC, OOD_EMBEDDINGS_DST)

    manifest = {
        "model_version": model_version,
        "trained_from_commit": commit,
        "dataset_version": args.dataset_version,
        "metrics": metrics,
        "ood": ood_config,
        "promoted_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    MANIFEST_PATH.write_text(MANIFEST_HEADER + yaml.dump(manifest, sort_keys=False))

    onnx_size_mb = ONNX_DST.stat().st_size / (1024 * 1024)
    embeddings_size_mb = EMBEDDINGS_DST.stat().st_size / (1024 * 1024)
    ood_onnx_size_mb = OOD_ONNX_DST.stat().st_size / (1024 * 1024)
    ood_embeddings_size_mb = OOD_EMBEDDINGS_DST.stat().st_size / (1024 * 1024)
    print(f"Copied {ONNX_SRC} -> {ONNX_DST} ({onnx_size_mb:.1f} MB)")
    print(f"Copied {EMBEDDINGS_SRC} -> {EMBEDDINGS_DST} ({embeddings_size_mb:.1f} MB)")
    print(f"Copied {OOD_ONNX_SRC} -> {OOD_ONNX_DST} ({ood_onnx_size_mb:.1f} MB)")
    print(f"Copied {OOD_EMBEDDINGS_SRC} -> {OOD_EMBEDDINGS_DST} ({ood_embeddings_size_mb:.1f} MB)")
    print(f"Wrote {MANIFEST_PATH}:\n")
    print(yaml.dump(manifest, sort_keys=False))

    if onnx_size_mb > 100 or ood_onnx_size_mb > 100:
        print(
            f"WARNING: an .onnx file is >100 MB — "
            "see the Git LFS discussion in lab/README.md before committing.",
            file=sys.stderr,
        )

    onnx_rel = ONNX_DST.relative_to(LAB_DIR.parent)
    embeddings_rel = EMBEDDINGS_DST.relative_to(LAB_DIR.parent)
    ood_onnx_rel = OOD_ONNX_DST.relative_to(LAB_DIR.parent)
    ood_embeddings_rel = OOD_EMBEDDINGS_DST.relative_to(LAB_DIR.parent)
    manifest_rel = MANIFEST_PATH.relative_to(LAB_DIR.parent)
    print("Next step (not run automatically):")
    print(f"  git add -f {onnx_rel} {embeddings_rel} {ood_onnx_rel} {ood_embeddings_rel} {manifest_rel}")
    print('  git commit -m "..."')


if __name__ == "__main__":
    main()
