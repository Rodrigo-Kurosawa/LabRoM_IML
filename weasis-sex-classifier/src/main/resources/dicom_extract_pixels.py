"""
dicom_extract_pixels.py – extract pixels from DICOM SC files to PNG
--------------------------------------------------------------------
Called by DicomExtractor.java for Secondary Capture pixel extraction.
Supports any Transfer Syntax via two strategies:

  1. Find the embedded JPEG payload and let Pillow decode it.
     Pillow reads the JPEG APP0 / APP14-Adobe colour-space markers and
     applies the correct YCbCr → RGB conversion at decode time — no
     statistical heuristics needed.

  2. For non-JPEG transfer syntaxes (uncompressed, JPEG-LS, JPEG 2000…)
     fall back to pydicom + a per-channel variance check for YBR data.

Usage:
    python3 dicom_extract_pixels.py <output_dir> <file1.dcm> [file2.dcm ...]

Output (stdout):
    OK:<img_path>    <- one line per successfully saved image
    FAIL:<reason>    <- one line per failure (non-fatal)
"""

import sys
import os
import io
import warnings
warnings.filterwarnings("ignore")


def _find_jpeg(raw_bytes):
    """
    Locate the first JPEG payload (SOI … EOI) inside raw DICOM bytes.
    Returns the bytes slice or None if not found.
    """
    n = len(raw_bytes)
    for i in range(n - 2):
        if raw_bytes[i] == 0xFF and raw_bytes[i + 1] == 0xD8 and raw_bytes[i + 2] == 0xFF:
            # Scan backwards for the last FF D9 (EOI)
            for j in range(n - 1, i + 3, -1):
                if raw_bytes[j] == 0xD9 and raw_bytes[j - 1] == 0xFF:
                    return raw_bytes[i : j + 1]
    return None


def extract_one(dicom_path, output_dir, index):
    from PIL import Image
    import numpy as np

    try:
        # ── Strategy 1: PIL JPEG decode ───────────────────────────────────────────
        # Pillow's JPEG decoder (libjpeg) honours the JFIF APP0 and Adobe APP14
        # markers that encode the colour space.  When the markers say YCbCr, libjpeg
        # converts to sRGB during decoding.  The final .convert('RGB') call catches
        # any residual non-RGB mode (e.g. CMYK, L, PA).
        # This covers JPEG Baseline (1.2.840.10008.1.2.4.50) and Extended
        # (1.2.840.10008.1.2.4.51), which are by far the most common SC formats.
        with open(dicom_path, 'rb') as fh:
            raw = fh.read()

        jpeg_bytes = _find_jpeg(raw)
        if jpeg_bytes is not None:
            try:
                img = Image.open(io.BytesIO(jpeg_bytes)).convert('RGB')
                out_path = os.path.join(output_dir, f"sc_{index:04d}.png")
                img.save(out_path)
                return f"OK:{out_path}"
            except Exception:
                pass  # Malformed JPEG — fall through to pydicom

        # ── Strategy 2: pydicom for non-JPEG transfer syntaxes ────────────────────
        import pydicom

        ds = pydicom.dcmread(dicom_path, force=True)
        arr = ds.pixel_array  # pydicom decompresses as needed

        if arr.ndim == 3 and arr.shape[2] == 3:
            f = arr.astype(float)
            std_0 = float(np.std(f[:, :, 0]))
            std_1 = float(np.std(f[:, :, 1]))
            std_2 = float(np.std(f[:, :, 2]))

            # YBR pattern (uncompressed YBR_FULL): Y in channel 0 has much higher
            # variance than the chroma channels Cb (ch 1) and Cr (ch 2).
            if std_0 > 10 and std_1 < std_0 * 0.5 and std_2 < std_0 * 0.5:
                mn_raw, mx_raw = f.min(), f.max()
                if mx_raw > mn_raw:
                    arr_u8 = ((f - mn_raw) / (mx_raw - mn_raw) * 255).astype('uint8')
                else:
                    arr_u8 = f.astype('uint8')
                img = Image.fromarray(arr_u8, 'YCbCr').convert('RGB')
            else:
                mn, mx = arr.min(), arr.max()
                if mx > mn:
                    arr_u8 = ((arr.astype(float) - mn) / (mx - mn) * 255).astype('uint8')
                else:
                    arr_u8 = arr.astype('uint8')
                img = Image.fromarray(arr_u8, 'RGB')

        elif arr.ndim == 2:
            mn, mx = arr.min(), arr.max()
            if mx > mn:
                arr_u8 = ((arr.astype(float) - mn) / (mx - mn) * 255).astype('uint8')
            else:
                arr_u8 = arr.astype('uint8')
            img = Image.fromarray(arr_u8).convert('RGB')

        elif arr.ndim == 3 and arr.shape[2] == 4:
            mn, mx = arr.min(), arr.max()
            if mx > mn:
                arr_u8 = ((arr.astype(float) - mn) / (mx - mn) * 255).astype('uint8')
            else:
                arr_u8 = arr.astype('uint8')
            img = Image.fromarray(arr_u8, 'RGBA').convert('RGB')

        else:
            return f"FAIL:{dicom_path}: unsupported array shape {arr.shape}"

        out_path = os.path.join(output_dir, f"sc_{index:04d}.png")
        img.save(out_path)
        return f"OK:{out_path}"

    except Exception as e:
        return f"FAIL:{dicom_path}: {e}"


def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <output_dir> <dicom1> [dicom2 ...]",
              file=sys.stderr)
        sys.exit(1)

    output_dir = sys.argv[1]
    os.makedirs(output_dir, exist_ok=True)

    for i, path in enumerate(sys.argv[2:]):
        result = extract_one(path, output_dir, i)
        print(result, flush=True)


if __name__ == "__main__":
    main()
