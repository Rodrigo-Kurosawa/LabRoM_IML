/*
 * Sex Classification Plugin – LabRoM_IML
 *
 * SexClassifier: routes YOLOv8 (final.pt) classification + Grad-CAM through
 * the persistent {@link PythonWorker}.
 */
package org.weasis.sex.classifier;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs sex classification (YOLOv8 {@code final.pt}) on pivot images and
 * generates Grad-CAM heatmaps. Inference is delegated to the persistent
 * {@link PythonWorker} so the cost of importing torch/ultralytics is paid
 * only once per Weasis session, not once per pipeline run.
 */
public final class SexClassifier {

  private static final Logger LOGGER = LoggerFactory.getLogger(SexClassifier.class);

  private static final String MODEL_FILENAME = "final.pt";

  private SexClassifier() {
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Result types
  // ───────────────────────────────────────────────────────────────────────────

  /** Classification result for a single pivot image. */
  public static final class ImageResult {
    public final int    index;
    public final File   imageFile;   // original pivot image
    public final String label;       // e.g. "M" or "F"
    public final double probability; // [0, 1]
    public final File   heatmap;     // Grad-CAM overlay; null if not generated

    public ImageResult(int index, File imageFile,
                       String label, double probability, File heatmap) {
      this.index       = index;
      this.imageFile   = imageFile;
      this.label       = label;
      this.probability = probability;
      this.heatmap     = heatmap;
    }
  }

  /** Aggregated result for the full pivot window. */
  public static final class ClassificationResult {
    public final List<ImageResult> perImage;
    public final String            finalLabel;
    public final double            finalProbability;
    public final String            error; // null on success

    private ClassificationResult(List<ImageResult> pi, String fl, double fp, String err) {
      this.perImage         = pi;
      this.finalLabel       = fl;
      this.finalProbability = fp;
      this.error            = err;
    }

    public static ClassificationResult success(List<ImageResult> pi, String fl, double fp) {
      return new ClassificationResult(pi, fl, fp, null);
    }

    public static ClassificationResult error(String msg) {
      return new ClassificationResult(new ArrayList<>(), null, 0.0, msg);
    }

    public boolean isSuccess() {
      return error == null;
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Public API
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Classifies every image in {@code images} with {@code final.pt} and writes
   * Grad-CAM heatmaps to {@code heatmapDir}.
   *
   * @param images     pivot-window PNG files (output of {@link PivotDetector})
   * @param heatmapDir directory where heatmap PNGs are written
   * @param modelPath  path to {@code final.pt}; {@code null} uses auto-discovery
   * @return aggregated classification result
   */
  public static ClassificationResult classify(
      List<File> images, File heatmapDir, String modelPath) {

    if (images == null || images.isEmpty()) {
      return ClassificationResult.error("No images to classify.");
    }
    heatmapDir.mkdirs();

    if (modelPath == null || modelPath.isEmpty()) {
      modelPath = findModelPath();
    }
    if (!new File(modelPath).exists()) {
      return ClassificationResult.error(
          MODEL_FILENAME + " not found at: " + modelPath
              + ".<br>Place the model file there and retry.");
    }

    LOGGER.info("Classifying {} image(s) via PythonWorker", images.size());
    PythonWorker.ClassifyResponse resp =
        PythonWorker.getInstance().classify(images, heatmapDir, modelPath);

    List<ImageResult> perImage = new ArrayList<>();
    for (String[] entry : resp.imgLines) {
      try {
        int idx       = Integer.parseInt(entry[0]);
        String label  = entry[1];
        double prob   = Double.parseDouble(entry[2]);
        File heatmap  = null;
        if (entry[3] != null && !entry[3].isEmpty()) {
          File hf = new File(entry[3]);
          if (hf.exists()) heatmap = hf;
        }
        File imgFile = idx < images.size() ? images.get(idx) : null;
        perImage.add(new ImageResult(idx, imgFile, label, prob, heatmap));
        LOGGER.debug("  [classify] idx={} label={} prob={}", idx, label, prob);
      } catch (NumberFormatException ignored) {
      }
    }

    if (perImage.isEmpty()) {
      String msg = (resp.error != null)
          ? "Classification error: " + resp.error
          : "Classifier returned no results. Check that ultralytics is installed.";
      return ClassificationResult.error(msg);
    }

    String finalLabel = resp.finalLabel;
    double finalProb  = resp.finalProb;

    // Fallback: compute final from per-image if worker did not emit FINAL line
    if (finalLabel == null) {
      Map<String, Double> sums = new LinkedHashMap<>();
      for (ImageResult r : perImage) {
        sums.merge(r.label, r.probability, Double::sum);
      }
      finalLabel = sums.entrySet().stream()
          .max(Map.Entry.comparingByValue())
          .map(Map.Entry::getKey).orElse("?");
      finalProb = sums.get(finalLabel) / perImage.size();
    }

    LOGGER.info("Classification complete: {} ({}) over {} image(s)",
        finalLabel, String.format("%.1f%%", finalProb * 100), perImage.size());
    return ClassificationResult.success(perImage, finalLabel, finalProb);
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Helpers
  // ───────────────────────────────────────────────────────────────────────────

  static String findModelPath() {
    // 1. Relative to the plugin JAR (works when running from target/)
    try {
      File jar = new File(
          SexClassifier.class.getProtectionDomain()
              .getCodeSource().getLocation().toURI());
      File candidate = new File(
          jar.getParentFile().getParentFile(), "models/" + MODEL_FILENAME);
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
