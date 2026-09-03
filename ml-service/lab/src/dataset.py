from pathlib import Path

import torch
from PIL import Image
from torch.utils.data import Dataset
from torchvision import transforms

# Preprocessing constants — MUST stay in sync with the copy in app/model/inference.py.
# Duplicated (not imported) because lab/ (conda env) and app/ (the slim runtime image)
# never share a Python path.
IMG_SIZE = 224
MEAN = [0.485, 0.456, 0.406]
STD = [0.229, 0.224, 0.225]
CLASS_NAMES = ["NORMAL", "PNEUMONIA"]  # index order fixes what the model's output means


def build_transform():
    return transforms.Compose(
        [
            transforms.Resize((IMG_SIZE, IMG_SIZE)),
            transforms.ToTensor(),
            transforms.Normalize(mean=MEAN, std=STD),
        ]
    )


class PneumoniaXrayDataset(Dataset):
    # Expects `data_dir/NORMAL/*.jp*g` and `data_dir/PNEUMONIA/*.jp*g`.
    def __init__(self, data_dir, transform=None):
        self.transform = transform or build_transform()
        self.samples = []
        for label, class_name in enumerate(CLASS_NAMES):
            for img_path in sorted((Path(data_dir) / class_name).glob("*.jp*g")):
                self.samples.append((img_path, label))
        if not self.samples:
            raise ValueError(f"No images found under {data_dir}/{{{','.join(CLASS_NAMES)}}}")

    def __len__(self):
        return len(self.samples)

    def __getitem__(self, index):
        img_path, label = self.samples[index]
        image = Image.open(img_path).convert("RGB")
        # A plain Python float here would make DataLoader's default_collate stack a
        # float64 batch tensor, which the MPS backend can't move to device at all.
        return self.transform(image), torch.tensor(label, dtype=torch.float32)
