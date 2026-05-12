"""
inference_worker.py – persistent inference worker for the LabRoM Sex Classifier.

Replaces the three one-shot scripts (dicom_extract_pixels.py, pivo_inference.py,
sex_classify.py). The worker is spawned once per Weasis session by PythonWorker
on the Java side, holds both PyTorch models in memory, and answers requests
over stdin / stdout using a simple TAB-separated line protocol.

──────────────────────────────────────────────────────────────────────────────
Protocol (TAB-separated, '\\n'-terminated)
──────────────────────────────────────────────────────────────────────────────

Startup banner on stdout, printed once when the worker is ready to read requests:

    WORKER_READY <version>

Requests (Java → Python, one per line on stdin):

    REQ <id> ping
    REQ <id> shutdown
    REQ <id> extract  <output_dir> <file1> [file2 ...]
    REQ <id> pivot    <model_path> <file1> [file2 ...]
    REQ <id> classify <model_path> <heatmap_dir> <file1> [file2 ...]

Responses (Python → Java). Every response line begins with the request id so
the caller can ignore stray output. Every request ends with one DONE line
(exception: shutdown emits BYE and exits without DONE).

    <id> OK    <path>                          (extract only, per file)
    <id> FAIL  <reason>                        (per-item non-fatal failure)
    <id> PROB  <index> <prob>                  (pivot only, per image)
    <id> IMG   <idx> <label> <prob> <heatmap>  (classify only, per image)
    <id> FINAL <label> <prob>                  (classify only, aggregate)
    <id> PONG                                  (ping)
    <id> BYE                                   (shutdown — process then exits)
    <id> ERR   <reason>                        (top-level handler error)
    <id> DONE                                  (terminator for every request)

Heavy imports (torch, ultralytics, pydicom, …) are deferred until the first
request that needs them, so WORKER_READY appears within ~1 s of process spawn.
Models are cached after the first successful load.
"""

import os
import sys
import io
import traceback
import warnings

warnings.filterwarnings("ignore")

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

# ── Module-level cache: loaded once, reused across requests ─────────────────
_yolo = None
_yolo_path = None
_resnet = None
_resnet_path = None
_resnet_transform = None
_gradcam = None
_gradcam_wrapper = None  # keep a strong ref so the hooks stay alive


def emit(req_id, *fields):
    parts = [req_id] + [str(f) for f in fields]
    sys.stdout.write("\t".join(parts) + "\n")
    sys.stdout.flush()


def warn(msg):
    sys.stderr.write(str(msg) + "\n")
    sys.stderr.flush()


# ──────────────────────────────────────────────────────────────────────────
# ping
# ──────────────────────────────────────────────────────────────────────────
def handle_ping(req_id, args):
    emit(req_id, "PONG")


# ──────────────────────────────────────────────────────────────────────────
# extract — DICOM Secondary Capture → PNG (pydicom + Pillow)
# ──────────────────────────────────────────────────────────────────────────
def _find_jpeg(raw):
    n = len(raw)
    for i in range(n - 2):
        if raw[i] == 0xFF and raw[i + 1] == 0xD8 and raw[i + 2] == 0xFF:
            for j in range(n - 1, i + 3, -1):
                if raw[j] == 0xD9 and raw[j - 1] == 0xFF:
                    return raw[i : j + 1]
    return None


def _extract_one(dicom_path, output_dir, index):
    from PIL import Image
    import numpy as np

    try:
        with open(dicom_path, "rb") as fh:
            raw = fh.read()

        jpeg = _find_jpeg(raw)
        if jpeg is not None:
            try:
                img = Image.open(io.BytesIO(jpeg)).convert("RGB")
                out = os.path.join(output_dir, f"sc_{index:04d}.png")
                img.save(out)
                return ("OK", out)
            except Exception:
                pass

        import pydicom
        ds = pydicom.dcmread(dicom_path, force=True)
        arr = ds.pixel_array

        if arr.ndim == 4:
            arr = arr[0]
        elif arr.ndim == 3 and arr.shape[2] not in (1, 3, 4):
            arr = arr[0]
        if arr.ndim == 3 and arr.shape[2] == 1:
            arr = arr[:, :, 0]

        if arr.ndim == 3 and arr.shape[2] == 3:
            f = arr.astype(float)
            s0 = float(np.std(f[:, :, 0]))
            s1 = float(np.std(f[:, :, 1]))
            s2 = float(np.std(f[:, :, 2]))
            if s0 > 10 and s1 < s0 * 0.5 and s2 < s0 * 0.5:
                mn, mx = f.min(), f.max()
                arr_u8 = (((f - mn) / (mx - mn) * 255).astype("uint8")
                          if mx > mn else f.astype("uint8"))
                img = Image.fromarray(arr_u8, "YCbCr").convert("RGB")
            else:
                mn, mx = arr.min(), arr.max()
                arr_u8 = (((arr.astype(float) - mn) / (mx - mn) * 255).astype("uint8")
                          if mx > mn else arr.astype("uint8"))
                img = Image.fromarray(arr_u8, "RGB")
        elif arr.ndim == 2:
            mn, mx = arr.min(), arr.max()
            arr_u8 = (((arr.astype(float) - mn) / (mx - mn) * 255).astype("uint8")
                      if mx > mn else arr.astype("uint8"))
            img = Image.fromarray(arr_u8).convert("RGB")
        elif arr.ndim == 3 and arr.shape[2] == 4:
            mn, mx = arr.min(), arr.max()
            arr_u8 = (((arr.astype(float) - mn) / (mx - mn) * 255).astype("uint8")
                      if mx > mn else arr.astype("uint8"))
            img = Image.fromarray(arr_u8, "RGBA").convert("RGB")
        else:
            return ("FAIL", f"{dicom_path}: unsupported array shape {arr.shape}")

        out = os.path.join(output_dir, f"sc_{index:04d}.png")
        img.save(out)
        return ("OK", out)

    except Exception as e:
        return ("FAIL", f"{dicom_path}: {e}")


def handle_extract(req_id, args):
    if len(args) < 2:
        emit(req_id, "ERR", "extract: missing output_dir or files")
        return
    output_dir = args[0]
    files = args[1:]
    os.makedirs(output_dir, exist_ok=True)
    for i, path in enumerate(files):
        kind, payload = _extract_one(path, output_dir, i)
        emit(req_id, kind, payload)


# ──────────────────────────────────────────────────────────────────────────
# pivot — ResNet-50 forward pass
# ──────────────────────────────────────────────────────────────────────────
def _load_resnet(model_path):
    global _resnet, _resnet_path, _resnet_transform
    if _resnet is not None and _resnet_path == model_path:
        return _resnet, _resnet_transform

    import torch
    import torch.nn as nn
    from torchvision import models, transforms

    model = models.resnet50()
    model.fc = nn.Sequential(
        nn.Identity(),
        nn.Linear(2048, 256),
        nn.ReLU(),
        nn.Dropout(0.3),
        nn.Linear(256, 2),
    )
    try:
        state = torch.load(model_path, map_location="cpu", weights_only=True)
    except TypeError:
        # torch < 2.4 has no weights_only kwarg
        state = torch.load(model_path, map_location="cpu")
    model.load_state_dict(state)
    model.eval()

    tfm = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225]),
    ])

    _resnet = model
    _resnet_path = model_path
    _resnet_transform = tfm
    return model, tfm


# Batch size cap for ResNet-50 CPU inference. 16 × 3 × 224 × 224 × float32
# ≈ 9.5 MB of input data and ~150 MB of peak activations — comfortable on
# any machine the MSI is meant to run on. Larger batches give diminishing
# returns on CPU because MLAS already vectorises within a batch.
PIVOT_BATCH_SIZE = 16


def handle_pivot(req_id, args):
    if len(args) < 2:
        emit(req_id, "ERR", "pivot: missing model_path or images")
        return
    model_path = args[0]
    images = args[1:]
    n = len(images)

    try:
        import torch
        from PIL import Image
        # Let PyTorch use all physical cores. Default is sometimes 1.
        try:
            torch.set_num_threads(max(1, os.cpu_count() or 1))
        except Exception:
            pass
        model, tfm = _load_resnet(model_path)
    except Exception as e:
        emit(req_id, "ERR", f"pivot: cannot load model: {e}")
        for i in range(n):
            emit(req_id, "PROB", i, "0.0")
        return

    # ── Preprocess every image in parallel ───────────────────────────────────
    # PIL's PNG/JPEG decoder and torchvision's Resize/ToTensor both release
    # the GIL in their C-extension paths, so a thread pool gives genuine
    # parallelism on multi-core CPUs. We cap at 8 workers because PNG decode
    # saturates memory bandwidth past that point on typical client hardware.
    tensors = [None] * n
    errors = {}

    def _prep(idx_path):
        idx, path = idx_path
        try:
            with Image.open(path) as img:
                return idx, tfm(img.convert("RGB")), None
        except Exception as e:
            return idx, None, str(e)

    from concurrent.futures import ThreadPoolExecutor
    workers = max(1, min(os.cpu_count() or 1, 8))
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for idx, tensor, err in pool.map(_prep, enumerate(images)):
            if err is not None:
                errors[idx] = err
                warn(f"  [pivot warn] {images[idx]}: {err}")
            else:
                tensors[idx] = tensor

    # ── Batched forward pass over the successfully-preprocessed tensors ──────
    probs = [None] * n
    valid_indices = [i for i in range(n) if tensors[i] is not None]
    with torch.no_grad():
        for start in range(0, len(valid_indices), PIVOT_BATCH_SIZE):
            chunk = valid_indices[start:start + PIVOT_BATCH_SIZE]
            batch = torch.stack([tensors[i] for i in chunk])  # (B, 3, 224, 224)
            try:
                logits = model(batch)
                softm = torch.softmax(logits, dim=1)[:, 1]
                for k, idx in enumerate(chunk):
                    probs[idx] = float(softm[k].item())
            except Exception as e:
                # If a batch blows up, fall back to per-image inference for
                # that chunk so one bad sample doesn't lose the rest.
                warn(f"  [pivot batch warn] {e}; retrying per-image")
                for idx in chunk:
                    try:
                        t = tensors[idx].unsqueeze(0)
                        probs[idx] = float(torch.softmax(model(t), dim=1)[0, 1].item())
                    except Exception as ee:
                        errors[idx] = str(ee)
                        warn(f"  [pivot warn] {images[idx]}: {ee}")

    # ── Emit in image order so DEBUG logs stay readable ──────────────────────
    for i in range(n):
        if probs[i] is not None:
            emit(req_id, "PROB", i, f"{probs[i]:.6f}")
        else:
            emit(req_id, "PROB", i, "0.0")


# ──────────────────────────────────────────────────────────────────────────
# classify — YOLOv8-cls + Grad-CAM overlay
# ──────────────────────────────────────────────────────────────────────────
def _load_yolo(model_path):
    global _yolo, _yolo_path, _gradcam, _gradcam_wrapper
    if _yolo is not None and _yolo_path == model_path:
        return _yolo
    from ultralytics import YOLO
    _yolo = YOLO(model_path)
    _yolo_path = model_path
    # Invalidate cached Grad-CAM when the model changes
    _gradcam = None
    _gradcam_wrapper = None
    return _yolo


def _get_gradcam():
    """Build the Grad-CAM object once per worker session and reuse it."""
    global _gradcam, _gradcam_wrapper
    if _gradcam is not None:
        return _gradcam
    try:
        import torch.nn as nn
        from pytorch_grad_cam import GradCAM

        class YOLOWrapper(nn.Module):
            def __init__(self, yolo):
                super().__init__()
                self.inner = yolo.model

            def forward(self, x):
                return self.inner(x)

        wrapper = YOLOWrapper(_yolo)
        for p in wrapper.parameters():
            p.requires_grad_(True)
        wrapper.eval()
        target_layer = wrapper.inner.model[-2]
        _gradcam = GradCAM(model=wrapper, target_layers=[target_layer])
        _gradcam_wrapper = wrapper
        return _gradcam
    except Exception as e:
        warn(f"  [gradcam unavailable] {e}")
        return None


def handle_classify(req_id, args):
    if len(args) < 3:
        emit(req_id, "ERR", "classify: missing model_path, heatmap_dir, or images")
        return
    model_path = args[0]
    heatmap_dir = args[1]
    images = args[2:]
    os.makedirs(heatmap_dir, exist_ok=True)

    try:
        model = _load_yolo(model_path)
    except Exception as e:
        emit(req_id, "ERR", f"classify: cannot load model: {e}")
        return

    class_names = [model.names[k] for k in sorted(model.names.keys())]

    cam = _get_gradcam()
    show_cam_on_image = None
    ClassifierOutputTarget = None
    if cam is not None:
        try:
            from pytorch_grad_cam.utils.image import show_cam_on_image as _show
            from pytorch_grad_cam.utils.model_targets import ClassifierOutputTarget as _Tgt
            show_cam_on_image = _show
            ClassifierOutputTarget = _Tgt
        except Exception:
            cam = None

    import torch
    import numpy as np
    import cv2

    try:
        torch.set_num_threads(max(1, os.cpu_count() or 1))
    except Exception:
        pass

    # ── Phase 1: batched YOLO inference ──────────────────────────────────────
    # Ultralytics accepts a list of paths and batches internally. Heatmaps
    # are deferred to phase 2 because Grad-CAM needs per-sample gradients.
    yolo_results = None
    try:
        yolo_results = model(images, verbose=False)
    except Exception as e:
        warn(f"  [classify batch warn] {e}; retrying per-image")

    per_image_probs = [None] * len(images)  # dict of class→prob or None
    fail_msgs = {}
    for i, img_path in enumerate(images):
        res = None
        if yolo_results is not None and i < len(yolo_results):
            res = yolo_results[i]
        else:
            try:
                res = model(img_path, verbose=False)[0]
            except Exception as e:
                fail_msgs[i] = str(e)
                continue
        try:
            per_image_probs[i] = {
                model.names[k]: float(res.probs.data[k]) for k in model.names
            }
        except Exception as e:
            fail_msgs[i] = str(e)

    # ── Phase 2: per-image Grad-CAM + emit ──────────────────────────────────
    all_probs = []
    for i, img_path in enumerate(images):
        if i in fail_msgs:
            emit(req_id, "FAIL", f"{img_path}: {fail_msgs[i]}")
            continue
        probs = per_image_probs[i]
        if probs is None:
            emit(req_id, "FAIL", f"{img_path}: no probabilities")
            continue

        all_probs.append(probs)
        label = max(probs, key=probs.get)
        conf = probs[label]

        heatmap_path = ""
        if cam is not None:
            try:
                img_bgr = cv2.imread(img_path)
                if img_bgr is None:
                    raise ValueError("cv2.imread returned None")
                img_rgb = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2RGB).astype("float32") / 255.0
                tensor = (torch.from_numpy(img_rgb)
                          .permute(2, 0, 1)
                          .unsqueeze(0)
                          .requires_grad_(True))
                ci = class_names.index(label) if label in class_names else 0
                with torch.enable_grad():
                    cam_map = cam(input_tensor=tensor,
                                  targets=[ClassifierOutputTarget(ci)])[0]
                overlay = show_cam_on_image(img_rgb, cam_map, use_rgb=True)
                heatmap_path = os.path.join(heatmap_dir, f"heatmap_{i:04d}.png")
                cv2.imwrite(heatmap_path, cv2.cvtColor(overlay, cv2.COLOR_RGB2BGR))
            except Exception as he:
                warn(f"  [gradcam warn] {img_path}: {he}")
                heatmap_path = ""

        emit(req_id, "IMG", i, label, f"{conf:.6f}", heatmap_path)

    if all_probs and class_names:
        n = len(all_probs)
        means = {c: sum(p.get(c, 0.0) for p in all_probs) / n for c in class_names}
        winner = max(means, key=means.get)
        emit(req_id, "FINAL", winner, f"{means[winner]:.6f}")


# ──────────────────────────────────────────────────────────────────────────
# preload — eagerly load models so the first real request is fast
# ──────────────────────────────────────────────────────────────────────────
def handle_preload(req_id, args):
    """
    Eagerly load both models without running inference. Called once per
    Weasis session from a background thread when the plugin activates, so
    the user's first Classify click does not pay the ~10 s torch/ultralytics
    import + ResNet weight-load cost.

    Args: <pivot_model_path> <classify_model_path>
    Either path may be empty (or refer to a missing file) to skip that side.
    """
    pivot_path = args[0] if len(args) >= 1 else ""
    classify_path = args[1] if len(args) >= 2 else ""

    if pivot_path and os.path.isfile(pivot_path):
        try:
            _load_resnet(pivot_path)
            emit(req_id, "OK", "pivot")
        except Exception as e:
            emit(req_id, "FAIL", f"pivot: {e}")
    else:
        emit(req_id, "FAIL", "pivot: model file missing")

    if classify_path and os.path.isfile(classify_path):
        try:
            _load_yolo(classify_path)
            _get_gradcam()  # builds the Grad-CAM hooks too
            emit(req_id, "OK", "classify")
        except Exception as e:
            emit(req_id, "FAIL", f"classify: {e}")
    else:
        emit(req_id, "FAIL", "classify: model file missing")


HANDLERS = {
    "ping": handle_ping,
    "extract": handle_extract,
    "pivot": handle_pivot,
    "classify": handle_classify,
    "preload": handle_preload,
}


def main():
    sys.stdout.write("WORKER_READY\t1\n")
    sys.stdout.flush()

    for raw in sys.stdin:
        line = raw.rstrip("\r\n")
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) < 3 or parts[0] != "REQ":
            warn(f"  [bad request] {line}")
            continue
        req_id = parts[1]
        cmd = parts[2]
        args = parts[3:]

        if cmd == "shutdown":
            emit(req_id, "BYE")
            return

        handler = HANDLERS.get(cmd)
        if handler is None:
            emit(req_id, "ERR", f"unknown cmd: {cmd}")
        else:
            try:
                handler(req_id, args)
            except Exception as e:
                emit(req_id, "ERR", f"{type(e).__name__}: {e}")
                warn(traceback.format_exc())
        emit(req_id, "DONE")


if __name__ == "__main__":
    main()
