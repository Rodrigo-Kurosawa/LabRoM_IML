/*
 * Sex Classification Plugin – LabRoM_IML
 *
 * SexClassifier: calls the bundled sex_classify.py script (YOLOv8 / best.pt)
 * to classify each pivot image and generate Grad-CAM heatmaps.
 */
package org.weasis.sex.classifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs sex classification (YOLOv8 {@code best.pt}) on pivot images and
 * generates Grad-CAM heatmaps, both via the bundled {@code sex_classify.py}.
 */
public final class SexClassifier {

  private static final Logger LOGGER = LoggerFactory.getLogger(SexClassifier.class);

  private static final String CLASSIFY_SCRIPT  = "sex_classify.py";
  private static final String MODEL_FILENAME   = "best.pt";

  private SexClassifier() {}

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
    public final String            error;  // null on success

    private ClassificationResult(List<ImageResult> pi,
                                  String fl, double fp, String err) {
      this.perImage         = pi;
      this.finalLabel       = fl;
      this.finalProbability = fp;
      this.error            = err;
    }

    public static ClassificationResult success(
        List<ImageResult> pi, String fl, double fp) {
      return new ClassificationResult(pi, fl, fp, null);
    }

    public static ClassificationResult error(String msg) {
      return new ClassificationResult(new ArrayList<>(), null, 0.0, msg);
    }

    public boolean isSuccess() { return error == null; }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Public API
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Classifies every image in {@code images} with {@code best.pt} and writes
   * Grad-CAM heatmaps to {@code heatmapDir}.
   *
   * @param images      pivot-window PNG files (output of {@link PivotDetector})
   * @param heatmapDir  directory where heatmap PNGs are written
   * @param modelPath   path to {@code best.pt}; {@code null} uses auto-discovery
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
          "best.pt not found at: " + modelPath
          + ".<br>Place the model file there and retry.");
    }

    String python = DicomExtractor.findPython();
    if (python == null) {
      return ClassificationResult.error(
          "python3 not found. Cannot run the classifier.");
    }

    File scriptFile;
    try {
      scriptFile = extractScript();
    } catch (IOException e) {
      return ClassificationResult.error(
          "Cannot extract bundled script: " + e.getMessage());
    }

    List<String> cmd = new ArrayList<>();
    cmd.add(python);
    cmd.add(scriptFile.getAbsolutePath());
    cmd.add(modelPath);
    cmd.add(heatmapDir.getAbsolutePath());
    for (File f : images) cmd.add(f.getAbsolutePath());

    LOGGER.info("Running sex_classify.py on {} image(s)", images.size());

    List<ImageResult> perImage = new ArrayList<>();
    String finalLabel = null;
    double finalProb  = 0.0;

    try {
      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.redirectErrorStream(true);
      Process proc = pb.start();

      try (BufferedReader br =
               new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
        String line;
        while ((line = br.readLine()) != null) {
          line = line.trim();
          if (line.startsWith("IMG:")) {
            // IMG:<idx>\t<label>\t<prob>\t<heatmap_path_or_empty>
            // TAB-separated so class names with spaces are handled correctly.
            String[] parts = line.substring(4).split("\t", 4);
            if (parts.length >= 3) {
              try {
                int    idx      = Integer.parseInt(parts[0].trim());
                String label    = parts[1].trim();
                double prob     = Double.parseDouble(parts[2].trim());
                File   heatmap  = null;
                if (parts.length == 4 && !parts[3].trim().isEmpty()) {
                  File hf = new File(parts[3].trim());
                  if (hf.exists()) heatmap = hf;
                }
                File imgFile = idx < images.size() ? images.get(idx) : null;
                perImage.add(new ImageResult(idx, imgFile, label, prob, heatmap));
                LOGGER.debug("  [classify] idx={} label={} prob={}", idx, label, prob);
              } catch (NumberFormatException ignored) {}
            }
          } else if (line.startsWith("FINAL:")) {
            // FINAL:<label>\t<prob>
            String[] parts = line.substring(6).split("\t", 2);
            if (parts.length == 2) {
              finalLabel = parts[0].trim();
              try { finalProb = Double.parseDouble(parts[1].trim()); }
              catch (NumberFormatException ignored) {}
            }
          } else if (line.startsWith("FAIL:")) {
            LOGGER.warn("sex_classify: {}", line.substring(5));
          } else {
            LOGGER.debug("[classify] {}", line);
          }
        }
      }

      boolean finished = proc.waitFor(300, TimeUnit.SECONDS);
      if (!finished) {
        proc.destroyForcibly();
        return ClassificationResult.error("Classification script timed out after 300 s.");
      }
      int exit = proc.exitValue();
      if (exit != 0 && perImage.isEmpty()) {
        return ClassificationResult.error(
            "Classification script exited with code " + exit + ".");
      }

    } catch (Exception e) {
      LOGGER.error("SexClassifier error", e);
      return ClassificationResult.error("Classification error: " + e.getMessage());
    }

    if (perImage.isEmpty()) {
      return ClassificationResult.error(
          "Classifier returned no results. Check that ultralytics is installed.");
    }

    // Fallback: compute final from per-image if script did not emit FINAL line
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

  private static File extractScript() throws IOException {
    try (InputStream is =
             SexClassifier.class.getResourceAsStream("/" + CLASSIFY_SCRIPT)) {
      if (is == null) {
        throw new IOException("Bundled script not found: " + CLASSIFY_SCRIPT);
      }
      Path tmp = Files.createTempFile("sex_classify_", ".py");
      Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
      tmp.toFile().deleteOnExit();
      return tmp.toFile();
    }
  }

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
    } catch (Exception ignore) {}

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
