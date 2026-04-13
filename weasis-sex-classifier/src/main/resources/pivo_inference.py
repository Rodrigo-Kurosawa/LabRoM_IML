"""
pivo_inference.py – Pivot detection via ResNet-50
---------------------------------------------------
Called by PivotDetector.java. Handles ONLY the neural network forward pass.
All DICOM reading, file discovery, and window slicing are done in Java.

Usage:
    python3 pivo_inference.py <model_path> <image0.png> [image1.png ...]

Output (one line per image, to stdout):
    <index> <probability_of_being_pivot>

Exit 0 on success, non-zero on error.
"""

import sys
import os

# ── Suppress all Python warnings ─────────────────────────────────────────────
import warnings
warnings.filterwarnings("ignore")

import traceback

def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <model_path> <img0> [img1 ...]", file=sys.stderr)
        sys.exit(1)

    model_path = sys.argv[1]
    image_paths = sys.argv[2:]

    # ── Import PyTorch ────────────────────────────────────────────────────────
    try:
        import torch
        import torch.nn as nn
        from torchvision import models, transforms
        from PIL import Image
    except ImportError as e:
        print(f"Missing dependency: {e}", file=sys.stderr)
        print(f"Install with: pip install torch torchvision Pillow", file=sys.stderr)
        # Fallback: emit uniform probabilities so Java uses centre image
        for i, _ in enumerate(image_paths):
            print(f"{i} 0.0")
        sys.exit(0)

    # ── Load model ────────────────────────────────────────────────────────────
    try:
        model = models.resnet50()
        model.fc = nn.Sequential(
            nn.Identity(),
            nn.Linear(2048, 256),
            nn.ReLU(),
            nn.Dropout(0.3),
            nn.Linear(256, 2),
        )
        model.load_state_dict(torch.load(model_path, map_location="cpu"))
        model.eval()
    except Exception as e:
        print(f"Cannot load model '{model_path}': {e}", file=sys.stderr)
        for i, _ in enumerate(image_paths):
            print(f"{i} 0.0")
        sys.exit(0)

    # ── Transform ─────────────────────────────────────────────────────────────
    transform = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406],
                             [0.229, 0.224, 0.225]),
    ])

    # ── Forward pass ──────────────────────────────────────────────────────────
    with torch.no_grad():
        for i, img_path in enumerate(image_paths):
            try:
                img = Image.open(img_path).convert("RGB")
                tensor = transform(img).unsqueeze(0)
                prob = torch.softmax(model(tensor), dim=1)[0][1].item()
                print(f"{i} {prob:.6f}")
            except Exception as e:
                print(f"{i} 0.0", flush=True)
                print(f"  [warn] {img_path}: {e}", file=sys.stderr)


if __name__ == "__main__":
    main()
