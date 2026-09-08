import torch
import torch.nn as nn
from torchvision import models


def build_model(pretrained=True):
    weights = models.ResNet18_Weights.IMAGENET1K_V1 if pretrained else None
    model = models.resnet18(weights=weights)
    model.fc = nn.Linear(model.fc.in_features, 1)  # single logit: P(pneumonia)
    return model


class ResNetWithEmbedding(nn.Module):
    """Wraps a build_model() ResNet18 to also return its 512-dim penultimate-layer
    embedding (the input to `fc`) alongside the usual logit -- used by lab/src/ood.py's
    k-NN out-of-distribution check, not for classification itself."""

    def __init__(self, resnet):
        super().__init__()
        self.resnet = resnet

    def forward(self, x):
        r = self.resnet
        x = r.conv1(x)
        x = r.bn1(x)
        x = r.relu(x)
        x = r.maxpool(x)
        x = r.layer1(x)
        x = r.layer2(x)
        x = r.layer3(x)
        x = r.layer4(x)
        x = r.avgpool(x)
        embedding = torch.flatten(x, 1)
        logits = r.fc(embedding)
        return logits, embedding
