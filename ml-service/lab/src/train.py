import argparse
from pathlib import Path

import torch
from sklearn.metrics import roc_auc_score
from torch.utils.data import DataLoader

from dataset import IMG_SIZE, PneumoniaXrayDataset
from model import build_model


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

    train_loader = DataLoader(
        PneumoniaXrayDataset(args.train_dir), batch_size=args.batch_size, shuffle=True
    )
    val_loader = DataLoader(PneumoniaXrayDataset(args.val_dir), batch_size=args.batch_size)

    model = build_model().to(device)
    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr)
    loss_fn = torch.nn.BCEWithLogitsLoss()

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
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

    onnx_path = Path(args.onnx_output)
    onnx_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        torch.randn(1, 3, IMG_SIZE, IMG_SIZE, device=device),
        onnx_path,
        input_names=["input"],
        output_names=["logits"],
        dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}},
        # torch>=2.9's dynamo-based exporter can't go as low as opset 17 (silently
        # falls back to 18 anyway) and, unless told otherwise, writes weights to a
        # separate model.onnx.data file — inference.py only loads model.onnx.
        opset_version=18,
        external_data=False,
    )

    print(f"\nBest val_auc={best_auc:.4f}. Checkpoint: {output_path}. ONNX: {onnx_path}")
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
    return parser.parse_args()


if __name__ == "__main__":
    train(parse_args())
