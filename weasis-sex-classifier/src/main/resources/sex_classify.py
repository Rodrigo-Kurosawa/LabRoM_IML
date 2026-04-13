"""
sex_classify.py – YOLOv8 classification + Grad-CAM heatmaps
------------------------------------------------------------
Called by SexClassifier.java. Runs inference on every pivot image with
best.pt (YOLO classification model), computes a soft-vote final result,
and optionally generates Grad-CAM heatmap overlays.

Usage:
    python3 sex_classify.py <model_path> <heatmap_dir> <img0.png> [img1.png ...]

Output (stdout, TAB-separated fields):
    IMG:<index>\t<label>\t<prob>\t<heatmap_path>  — one line per image
    FINAL:<label>\t<prob>                          — soft-vote aggregation
    FAIL:<reason>                                  — non-fatal per-image error
"""

import sys
import os
import warnings
warnings.filterwarnings("ignore")


def main():
    if len(sys.argv) < 4:
        print(f"Usage: {sys.argv[0]} <model_path> <heatmap_dir> <img0> ...",
              file=sys.stderr)
        sys.exit(1)

    model_path  = sys.argv[1]
    heatmap_dir = sys.argv[2]
    image_paths = sys.argv[3:]

    os.makedirs(heatmap_dir, exist_ok=True)

    # ── Required imports ──────────────────────────────────────────────────────
    try:
        from ultralytics import YOLO
        import torch
        import torch.nn as nn
        import numpy as np
        import cv2
    except ImportError as e:
        print(
            f"FAIL:Missing dependency: {e}. "
            "Install with: pip install ultralytics torch opencv-python grad-cam",
            flush=True)
        sys.exit(1)

    # ── Load YOLO model ───────────────────────────────────────────────────────
    try:
        model = YOLO(model_path)
    except Exception as e:
        print(f"FAIL:Cannot load model '{model_path}': {e}", flush=True)
        sys.exit(1)

    # ── Optional: Grad-CAM setup ──────────────────────────────────────────────
    grad_cam_ok = False
    wrapper = None
    target_layer = None
    show_cam_on_image = None

    try:
        from pytorch_grad_cam import GradCAM
        from pytorch_grad_cam.utils.image import show_cam_on_image as _show
        from pytorch_grad_cam.utils.model_targets import ClassifierOutputTarget

        class YOLOWrapper(nn.Module):
            """Thin wrapper so pytorch-grad-cam can call forward()."""
            def __init__(self, yolo):
                super().__init__()
                self.inner = yolo.model

            def forward(self, x):
                return self.inner(x)

        wrapper = YOLOWrapper(model)
        for p in wrapper.parameters():
            p.requires_grad_(True)
        wrapper.eval()
        target_layer = wrapper.inner.model[-2]  # penultimate conv block
        show_cam_on_image = _show
        grad_cam_ok = True
    except Exception:
        pass  # heatmaps will be skipped silently

    # ── Discover class names (sorted by index) ────────────────────────────────
    class_names = [model.names[k] for k in sorted(model.names.keys())]

    # ── Per-image loop ────────────────────────────────────────────────────────
    all_probs = []  # list[dict{label: prob}]

    for i, img_path in enumerate(image_paths):
        try:
            res   = model(img_path, verbose=False)[0]
            probs = {model.names[k]: float(res.probs.data[k]) for k in model.names}
            all_probs.append(probs)

            label = max(probs, key=probs.get)
            conf  = probs[label]

            # ── Grad-CAM heatmap ──────────────────────────────────────────────
            heatmap_path = ""
            if grad_cam_ok:
                try:
                    img_bgr = cv2.imread(img_path)
                    if img_bgr is None:
                        raise ValueError("cv2.imread returned None")
                    img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB).astype("float32") / 255.0
                    tensor  = (
                        torch.from_numpy(img_rgb)
                        .permute(2, 0, 1)
                        .unsqueeze(0)
                        .requires_grad_(True)
                    )
                    class_idx = class_names.index(label) if label in class_names else 0
                    with torch.enable_grad():
                        with GradCAM(model=wrapper, target_layers=[target_layer]) as cam:
                            cam_map = cam(
                                input_tensor=tensor,
                                targets=[ClassifierOutputTarget(class_idx)],
                            )[0]
                    overlay = show_cam_on_image(img_rgb, cam_map, use_rgb=True)
                    heatmap_path = os.path.join(heatmap_dir, f"heatmap_{i:04d}.png")
                    cv2.imwrite(heatmap_path,
                                cv2.cvtColor(overlay, cv2.COLOR_RGB2BGR))
                except Exception as he:
                    heatmap_path = ""

            print(f"IMG:{i}\t{label}\t{conf:.6f}\t{heatmap_path}", flush=True)

        except Exception as e:
            print(f"FAIL:{img_path}: {e}", flush=True)

    # ── Soft-vote final result ────────────────────────────────────────────────
    if all_probs and class_names:
        n = len(all_probs)
        means = {c: sum(p.get(c, 0.0) for p in all_probs) / n for c in class_names}
        winner = max(means, key=means.get)
        print(f"FINAL:{winner}\t{means[winner]:.6f}", flush=True)


if __name__ == "__main__":
    main()
