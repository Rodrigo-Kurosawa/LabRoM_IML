/*
 * Sex Classification Plugin – LabRoM_IML
 *
 * PivotDetector: routes ResNet-50 pivot detection through the persistent
 * {@link PythonWorker}, then slices and saves the result window in pure Java.
 */
package org.weasis.sex.classifier;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the pivot image among a set of Secondary Capture PNGs.
 *
 * <p>The ResNet-50 inference runs in the long-lived {@link PythonWorker} so
 * the import / model-load cost is amortized across the whole Weasis session.
 * File enumeration, window slicing, and image saving stay in Java.
 */
public final class PivotDetector {

  private static final Logger LOGGER = LoggerFactory.getLogger(PivotDetector.class);

  /** How many images before and after the pivot to include in the result window */
  public static final int WINDOW_RADIUS = 5;

  /**
   * Stride used when scanning a series for the pivot frame. With stride=2 we
   * run ResNet inference on every other image; the picked index is at worst
   * one slice off the true pivot, which is invisible at WINDOW_RADIUS=5
   * because the window still contains the true pivot and 4–6 neighbours on
   * each side. Set to 1 to disable (scan every image).
   */
  private static final int PIVOT_STRIDE = 2;

  private static final String MODEL_FILENAME = "pivo.pt";

  private PivotDetector() {
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Public API
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Given a list of PNG images, finds the pivot using ResNet-50 and returns
   * the window of images around it saved to {@code outputDir}.
   *
   * @param images    sorted list of PNG files (output of {@link DicomExtractor})
   * @param outputDir directory where the window images are saved
   * @param modelPath path to {@code pivo.pt} – if {@code null}, uses default
   * @return list of result image files, or empty list on failure
   */
  public static List<File> detectAndSaveWindow(List<File> images, File outputDir, String modelPath)
      throws IOException {

    if (images.isEmpty()) return new ArrayList<>();
    outputDir.mkdirs();

    if (modelPath == null || modelPath.isEmpty()) {
      modelPath = findModelPath();
    }

    // ── 1. Find pivot via PythonWorker (ResNet-50) ───────────────────────────
    int pivotIndex;
    if (new File(modelPath).exists()) {
      pivotIndex = runPivotInference(images, modelPath);
    } else {
      LOGGER.warn("Model not found at '{}'. Falling back to center image.", modelPath);
      pivotIndex = images.size() / 2;
    }

    // ── 2. Slice window and save (pure Java) ──────────────────────────────────
    return saveWindow(images, pivotIndex, outputDir);
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Python bridge – delegates to the persistent worker
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Runs the bundled ResNet-50 pivot inference via the persistent worker and
   * returns the index of the image with the highest pivot probability.
   * Falls back to the centre image if the worker has no result.
   *
   * <p>Inference is only run on every {@code PIVOT_STRIDE}-th image to halve
   * the work. The picked index is mapped back to its position in the full
   * series before being returned, so the caller and the window-slicing code
   * are unaffected.
   */
  private static int runPivotInference(List<File> images, String modelPath) {
    // Build the strided sublist and keep a mapping back to original indices.
    List<File>    strided        = new ArrayList<>();
    List<Integer> stridedToOrig  = new ArrayList<>();
    for (int i = 0; i < images.size(); i += PIVOT_STRIDE) {
      strided.add(images.get(i));
      stridedToOrig.add(i);
    }

    LOGGER.info("Running pivot inference on {}/{} image(s) (stride={}) via PythonWorker",
        strided.size(), images.size(), PIVOT_STRIDE);

    List<PythonWorker.PivotProb> probs =
        PythonWorker.getInstance().runPivotInference(strided, modelPath);

    int    bestStridedIdx = strided.size() / 2;
    double bestProb       = -1.0;
    for (PythonWorker.PivotProb pp : probs) {
      int origIdx = (pp.index >= 0 && pp.index < stridedToOrig.size())
          ? stridedToOrig.get(pp.index) : pp.index;
      LOGGER.debug("  [inference] orig_idx={} prob={}", origIdx, pp.prob);
      if (pp.prob > bestProb) {
        bestProb       = pp.prob;
        bestStridedIdx = pp.index;
      }
    }

    int bestOrigIdx = (bestStridedIdx >= 0 && bestStridedIdx < stridedToOrig.size())
        ? stridedToOrig.get(bestStridedIdx) : images.size() / 2;

    if (bestProb < 0) {
      LOGGER.warn("Pivot inference returned no probabilities. Using centre index {}.",
          bestOrigIdx);
    } else {
      LOGGER.info("Pivot found at original index {} (prob={})",
          bestOrigIdx, String.format("%.3f", bestProb));
    }
    return bestOrigIdx;
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Window slicing – pure Java
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Copies {@code WINDOW_RADIUS} images before + pivot + {@code WINDOW_RADIUS}
   * after (circular wrap) into {@code outputDir}.
   *
   * <p>We copy the files verbatim instead of re-encoding them through Java
   * ImageIO.  Re-encoding would add an unnecessary lossy JPEG or lossless PNG
   * round-trip and, more importantly, any ImageIO colour-space quirks could
   * distort the colours that {@link DicomExtractor} already corrected.
   */
  private static List<File> saveWindow(List<File> images, int pivotIndex, File outputDir)
      throws IOException {

    int n = images.size();
    List<File> result = new ArrayList<>();

    for (int offset = -WINDOW_RADIUS; offset <= WINDOW_RADIUS; offset++) {
      int srcIdx = ((pivotIndex + offset) % n + n) % n;
      File src = images.get(srcIdx);

      int order = offset + WINDOW_RADIUS; // 0 … 2 * WINDOW_RADIUS
      String name = String.format("%02d_%s", order, src.getName());
      File dest = new File(outputDir, name);
      Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
      result.add(dest);
    }

    LOGGER.info("Copied {} pivot-window image(s) to {}", result.size(), outputDir);
    return result;
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Model-path resolution
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Discovers {@code pivo.pt} using two strategies (in order):
   * <ol>
   *   <li>JAR-relative: {@code <jar>/../models/pivo.pt} (works in dev / target/)</li>
   *   <li>Working-directory-relative: {@code models/pivo.pt}</li>
   * </ol>
   */
  static String findModelPath() {
    // 1. Relative to the plugin JAR (works when running from target/)
    try {
      File jar = new File(
          PivotDetector.class.getProtectionDomain()
              .getCodeSource().getLocation().toURI());
      File candidate = new File(jar.getParentFile().getParentFile(), "models/" + MODEL_FILENAME);
      if (candidate.exists()) {
        LOGGER.info("{} found (JAR-relative): {}", MODEL_FILENAME, candidate);
        return candidate.getAbsolutePath();
      }
    } catch (Exception ignore) {
    }

    // 2. models/ relative to working directory
    File cwd = new File(System.getProperty("user.dir"), "models/" + MODEL_FILENAME);
    if (cwd.exists()) {
      LOGGER.info("{} found (cwd-relative): {}", MODEL_FILENAME, cwd);
      return cwd.getAbsolutePath();
    }

    LOGGER.warn("{} not found. Run run_weasis.sh to copy models into bin-dist/weasis/models/",
        MODEL_FILENAME);
    return "";
  }
}
