/*
 * Sex Classification Plugin – LabRoM_IML
 *
 * PivotDetector: calls the bundled pivo_inference.py script (ResNet-50 only)
 * and saves the pivot window images in pure Java.
 *
 * Python is kept ONLY for the ResNet-50 inference (PyTorch/torchvision).
 * All file I/O, image manipulation and window slicing are done in Java.
 */
package org.weasis.sex.classifier;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the pivot image among a set of Secondary Capture PNGs.
 *
 * <p>The ResNet-50 inference runs via a minimal Python script
 * ({@code pivo_inference.py}) bundled as a resource inside the plugin JAR.
 * Everything else (file enumeration, window slicing, image saving) is Java.
 *
 * <p>Replaces {@code pivo_extractor.py} partially – Python handles only the
 * model forward pass.
 */
public final class PivotDetector {

  private static final Logger LOGGER = LoggerFactory.getLogger(PivotDetector.class);

  /** How many images before and after the pivot to include in the result window */
  public static final int WINDOW_RADIUS = 5;

  /** Name of the bundled inference script inside src/main/resources */
  private static final String INFERENCE_SCRIPT = "pivo_inference.py";

  private static final String MODEL_FILENAME = "pivo.pt";

  private PivotDetector() {}

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
      throws IOException, InterruptedException {

    if (images.isEmpty()) return new ArrayList<>();
    outputDir.mkdirs();

    if (modelPath == null || modelPath.isEmpty()) {
      modelPath = findModelPath();
    }

    // ── 1. Find pivot via Python ResNet-50 ────────────────────────────────────
    int pivotIndex;
    if (new File(modelPath).exists()) {
      pivotIndex = runPythonInference(images, modelPath);
    } else {
      LOGGER.warn("Model not found at '{}'. Falling back to center image.", modelPath);
      pivotIndex = images.size() / 2;
    }

    // ── 2. Slice window and save (pure Java) ──────────────────────────────────
    return saveWindow(images, pivotIndex, outputDir);
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Python bridge – only for ResNet-50 inference
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * Runs the bundled {@code pivo_inference.py} with all image paths as args.
   *
   * <p>Protocol: the script prints one line per image:
   * {@code <index> <probability>}
   * and exits 0. We pick the index with max probability.
   *
   * @return index of the pivot image within {@code images}
   */
  private static int runPythonInference(List<File> images, String modelPath)
      throws IOException, InterruptedException {

    // Extract the bundled script to a temp file
    File scriptFile = extractScript();

    String python = findPython();
    if (python == null) {
      LOGGER.warn("python3 not found. Falling back to center image.");
      return images.size() / 2;
    }

    // Build command: python3 pivo_inference.py <model> <img0> <img1> ...
    List<String> cmd = new ArrayList<>();
    cmd.add(python);
    cmd.add(scriptFile.getAbsolutePath());
    cmd.add(modelPath);
    for (File img : images) {
      cmd.add(img.getAbsolutePath());
    }

    LOGGER.info("Running pivot inference on {} images", images.size());
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(false);
    Process proc = pb.start();

    // Read stdout → pick best index
    int bestIndex = images.size() / 2;
    double bestProb = -1.0;
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty()) continue;
        try {
          String[] parts = line.split("\\s+");
          int idx = Integer.parseInt(parts[0]);
          double prob = Double.parseDouble(parts[1]);
          LOGGER.debug("  [inference] idx={} prob={}", idx, prob);
          if (prob > bestProb) {
            bestProb = prob;
            bestIndex = idx;
          }
        } catch (NumberFormatException ignored) {
          LOGGER.debug("  [inference raw] {}", line);
        }
      }
    }

    // Drain stderr for debugging
    try (BufferedReader err =
        new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
      err.lines().forEach(l -> LOGGER.debug("  [inference err] {}", l));
    }

    int exitCode = proc.waitFor();
    if (exitCode != 0) {
      LOGGER.warn("Inference script exited with code {}. Using index {}.", exitCode, bestIndex);
    } else {
      LOGGER.info("Pivot found at index {} (prob={:.3f})", bestIndex, bestProb);
    }

    scriptFile.deleteOnExit();
    return bestIndex;
  }

  /**
   * Extracts {@code pivo_inference.py} from the plugin JAR to a temp file.
   */
  private static File extractScript() throws IOException {
    try (InputStream is =
        PivotDetector.class.getResourceAsStream("/" + INFERENCE_SCRIPT)) {
      if (is == null) {
        throw new IOException("Bundled script not found: " + INFERENCE_SCRIPT);
      }
      Path tmp = Files.createTempFile("pivo_inference_", ".py");
      Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
      return tmp.toFile();
    }
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
  // Helpers
  // ───────────────────────────────────────────────────────────────────────────

  private static String findPython() {
    for (String candidate : new String[]{"python3", "python"}) {
      try {
        Process p = new ProcessBuilder(candidate, "--version")
            .redirectErrorStream(true).start();
        if (p.waitFor() == 0) return candidate;
      } catch (Exception ignored) {}
    }
    for (String path : new String[]{
        "/usr/bin/python3",
        "/usr/local/bin/python3",
        "/opt/homebrew/bin/python3",
        System.getProperty("user.home") + "/.pyenv/shims/python3"
    }) {
      if (new File(path).canExecute()) return path;
    }
    return null;
  }

  /**
   * Discovers {@code pivo.pt} using three strategies (in order):
   * <ol>
   *   <li>JAR-relative: {@code <jar>/../models/pivo.pt} (works in dev / target/)</li>
   *   <li>Working-directory-relative: {@code models/pivo.pt}</li>
   *   <li>Absolute path to the LabRoM_IML repository models directory</li>
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
    } catch (Exception ignore) {}

    // 2. models/ relative to working directory
    File cwd = new File(System.getProperty("user.dir"), "models/" + MODEL_FILENAME);
    if (cwd.exists()) {
      LOGGER.info("{} found (cwd-relative): {}", MODEL_FILENAME, cwd);
      return cwd.getAbsolutePath();
    }

    // 3. Absolute path inside the LabRoM_IML repository (OSGi / Weasis native runtime)
    File repo = new File(System.getProperty("user.home"),
        "Documents/Iniciação_Científica/LabRoM_IML/weasis-sex-classifier/models/"
        + MODEL_FILENAME);
    if (repo.exists()) {
      LOGGER.info("{} found (repo-absolute): {}", MODEL_FILENAME, repo);
      return repo.getAbsolutePath();
    }

    LOGGER.warn("{} not found. Place it in weasis-sex-classifier/models/", MODEL_FILENAME);
    return "";
  }
}
