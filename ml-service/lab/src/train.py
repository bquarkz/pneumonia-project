import argparse
from pathlib import Path

import numpy as np
import torch
from sklearn.metrics import roc_auc_score
from torch.utils.data import DataLoader

from dataset import IMG_SIZE, PneumoniaXrayDataset
from model import ResNetWithEmbedding, build_model


def evaluate(model, loader, device, loss_fn):
    model.eval()
    losses, labels, probs = [], [], []
    with torch.no_grad():
        for images, targets in loader:
            images, targets = images.to(device), targets.to(device).unsqueeze(1)
            logits = model(images)
            losses.append(loss_fn(logits, targets).item())
            probs.extend(torch.sigmoid(logits).cpu().numpy().ravel().tolist())
            labels.extend(targets.cpu().numpy().ravel().tolist())
    auc = roc_auc_score(labels, probs) if len(set(labels)) > 1 else float("nan")
    return sum(losses) / len(losses), auc


def export_artifacts(model, args, device):
    """Exports the trained model to ONNX (logits + 512-dim embedding) and, separately,
    the embeddings of every `train` image (no augmentation, no shuffling) -- the
    reference distribution lab/src/ood.py's k-NN out-of-distribution check compares
    against. Embeddings ride alongside logits in the same ONNX graph/forward pass so
    the serving app never needs torch to compute them (see app/model/inference.py)."""
    export_model = ResNetWithEmbedding(model).to(device)
    export_model.eval()

    onnx_path = Path(args.onnx_output)
    onnx_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        export_model,
        torch.randn(1, 3, IMG_SIZE, IMG_SIZE, device=device),
        onnx_path,
        input_names=["input"],
        output_names=["logits", "embedding"],
        dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}, "embedding": {0: "batch"}},
        # torch>=2.9's dynamo-based exporter can't go as low as opset 17 (silently
        # falls back to 18 anyway) and, unless told otherwise, writes weights to a
        # separate model.onnx.data file — inference.py only loads model.onnx.
        opset_version=18,
        external_data=False,
    )

    embeddings_path = Path(args.embeddings_output)
    embeddings_path.parent.mkdir(parents=True, exist_ok=True)
    embed_loader = DataLoader(PneumoniaXrayDataset(args.train_dir), batch_size=args.batch_size)
    embeddings = []
    with torch.no_grad():
        for images, _ in embed_loader:
            _, batch_embeddings = export_model(images.to(device))
            embeddings.append(batch_embeddings.cpu().numpy())
    embeddings = np.concatenate(embeddings, axis=0)
    np.save(embeddings_path, embeddings)

    print(f"ONNX: {onnx_path} (outputs: logits, {embeddings.shape[1]}-dim embedding)")
    print(f"Train embeddings: {embeddings_path} ({embeddings.shape[0]} x {embeddings.shape[1]})")


def train(args):
    if torch.cuda.is_available():
        # Also covers AMD GPUs on a ROCm-built PyTorch — ROCm reuses the "cuda" API/device string.
        device = torch.device("cuda")
    elif torch.backends.mps.is_available():
        device = torch.device("mps")
    elif hasattr(torch, "xpu") and torch.xpu.is_available():
        device = torch.device("xpu")
    else:
        device = torch.device("cpu")

    print(f"Training on: {device}")

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Skips training and re-exports ONNX/embeddings from an already-trained checkpoint --
    # e.g. after changing export_artifacts, when the trained weights themselves don't
    # need to change and a full (non-deterministic, ~10 min) retrain would be wasted.
    if getattr(args, "checkpoint", None):
        model = build_model(pretrained=False).to(device)
        model.load_state_dict(torch.load(args.checkpoint, map_location=device))
        model.eval()
        export_artifacts(model, args, device)
        print(f"\nRe-exported from checkpoint: {args.checkpoint}")
        print("To serve this model, see 'Promoting a model' in lab/README.md.")
        return

    train_loader = DataLoader(
        PneumoniaXrayDataset(args.train_dir), batch_size=args.batch_size, shuffle=True
    )
    val_loader = DataLoader(PneumoniaXrayDataset(args.val_dir), batch_size=args.batch_size)

    model = build_model().to(device)
    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr)
    loss_fn = torch.nn.BCEWithLogitsLoss()

    best_auc = -1.0

    for epoch in range(1, args.epochs + 1):
        model.train()
        running_loss = 0.0
        for images, targets in train_loader:
            images, targets = images.to(device), targets.to(device).unsqueeze(1)
            optimizer.zero_grad()
            loss = loss_fn(model(images), targets)
            loss.backward()
            optimizer.step()
            running_loss += loss.item()

        val_loss, val_auc = evaluate(model, val_loader, device, loss_fn)
        print(
            f"epoch {epoch}/{args.epochs} train_loss={running_loss / len(train_loader):.4f} "
            f"val_loss={val_loss:.4f} val_auc={val_auc:.4f}"
        )

        if val_auc > best_auc:
            best_auc = val_auc
            torch.save(model.state_dict(), output_path)

    model.load_state_dict(torch.load(output_path, map_location=device))
    model.eval()

    export_artifacts(model, args, device)

    print(f"\nBest val_auc={best_auc:.4f}. Checkpoint: {output_path}.")
    print("To serve this model, see 'Promoting a model' in lab/README.md.")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--train-dir", required=True, help="dir with NORMAL/ and PNEUMONIA/ subfolders")
    parser.add_argument("--val-dir", required=True, help="dir with NORMAL/ and PNEUMONIA/ subfolders")
    parser.add_argument("--epochs", type=int, default=10)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--lr", type=float, default=1e-4)
    parser.add_argument("--output", default="../artifacts/model_best.pt")
    parser.add_argument("--onnx-output", default="../artifacts/model.onnx")
    parser.add_argument("--embeddings-output", default="../artifacts/train_embeddings.npy")
    parser.add_argument(
        "--checkpoint",
        help="skip training and re-export ONNX/embeddings from this checkpoint instead",
    )
    return parser.parse_args()


if __name__ == "__main__":
    train(parse_args())
