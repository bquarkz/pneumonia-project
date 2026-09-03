from pathlib import Path

import cv2
import numpy as np
import pandas as pd
from PIL import Image
from scipy.stats import skew
from skimage.feature import graycomatrix, graycoprops

from dataset import IMG_SIZE, CLASS_NAMES

GLCM_LEVELS = 8  # quantized gray levels for GLCM — see module docstring below
CANNY_LOW = 100
CANNY_HIGH = 200


def extract_pixel_features(gray):
    return {
        "Mean_Intensity": float(gray.mean()),
        "Std_Intensity": float(gray.std()),
        "Min_Intensity": float(gray.min()),
        "Max_Intensity": float(gray.max()),
    }


def extract_texture_features(gray, levels=GLCM_LEVELS):
    quantized = (gray // (256 // levels)).astype(np.uint8)
    glcm = graycomatrix(
        quantized, distances=[1], angles=[0], levels=levels, symmetric=True, normed=True
    )
    entropy = float(-np.sum(glcm * np.log2(glcm + 1e-12)))
    return {
        "Contrast": float(graycoprops(glcm, "contrast")[0, 0]),
        "Homogeneity": float(graycoprops(glcm, "homogeneity")[0, 0]),
        "Energy": float(graycoprops(glcm, "energy")[0, 0]),
        "Entropy": entropy,
    }


def extract_edge_features(gray):
    edges = cv2.Canny(gray, CANNY_LOW, CANNY_HIGH)
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    return {
        "Edge_Density": float(edges.mean() / 255),
        "Contour_Count": len(contours),
    }


def extract_region_features(gray):
    left, right = np.array_split(gray, 2, axis=1)
    right_flipped = np.fliplr(right)
    min_width = min(left.shape[1], right_flipped.shape[1])
    left, right_flipped = left[:, :min_width], right_flipped[:, :min_width]
    symmetry = 1 - float(np.mean(np.abs(left.astype(float) - right_flipped.astype(float))) / 255)
    return {
        "Brightness_Dist": float(skew(gray.flatten())),
        "Symmetry": symmetry,
    }


def extract_features(image_path):
    image = Image.open(image_path).convert("L").resize((IMG_SIZE, IMG_SIZE))
    gray = np.array(image)
    features = {"Image_ID": image_path.name, "File_Path": str(image_path)}
    features.update(extract_pixel_features(gray))
    features.update(extract_texture_features(gray))
    features.update(extract_edge_features(gray))
    features.update(extract_region_features(gray))
    return features


def build_feature_table(data_dir, class_names=CLASS_NAMES):
    rows = []
    for class_name in class_names:
        for img_path in sorted((Path(data_dir) / class_name).glob("*.jp*g")):
            row = extract_features(img_path)
            row["Label"] = class_name
            rows.append(row)
    if not rows:
        raise ValueError(f"No images found under {data_dir}/{{{','.join(class_names)}}}")
    return pd.DataFrame(rows)
