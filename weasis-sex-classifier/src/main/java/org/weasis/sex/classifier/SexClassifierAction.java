/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 */
package org.weasis.sex.classifier;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates the Sex Classification pipeline asynchronously.
 *
 * <p>Model lookup order for {@code pivo.pt}:
 * <ol>
 *   <li>{@code weasis-sex-classifier/models/pivo.pt} (repo, resolved from JAR location)</li>
 *   <li>{@code models/pivo.pt} relative to Weasis working directory</li>
 * </ol>
 */
public final class SexClassifierAction {

  private static final Logger LOGGER = LoggerFactory.getLogger(SexClassifierAction.class);

  private SexClassifierAction() {}

  // ───────────────────────────────────────────────────────────────────────────
  // Public API
  // ───────────────────────────────────────────────────────────────────────────

  public static void runPipelineAsync(List<File> viewerFiles, Consumer<PipelineResult> onDone) {
    runPipelineAsync(viewerFiles, null, onDone);
  }

  public static void runPipelineAsync(
      List<File> viewerFiles, String modelPath, Consumer<PipelineResult> onDone) {

    new SwingWorker<PipelineResult, Void>() {
      @Override protected PipelineResult doInBackground() {
        return runPipeline(viewerFiles, modelPath);
      }
      @Override protected void done() {
        try {
          final PipelineResult r = get();
          SwingUtilities.invokeLater(() -> onDone.accept(r));
        } catch (Exception ex) {
          LOGGER.error("Pipeline worker failed", ex);
          SwingUtilities.invokeLater(() ->
              onDone.accept(PipelineResult.error("Internal error: " + ex.getMessage())));
        }
      }
    }.execute();
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Pipeline
  // ───────────────────────────────────────────────────────────────────────────

  private static PipelineResult runPipeline(List<File> viewerFiles, String modelPath) {
    if (viewerFiles == null || viewerFiles.isEmpty()) {
      return PipelineResult.error("No files found in the viewer.");
    }

    // Validate: at least one DICOM file present
    boolean anyDicom = viewerFiles.stream().anyMatch(DicomExtractor::isDicom);
    if (!anyDicom) {
      return PipelineResult.error(
          "Unsupported format.<br>"
          + "Load a DICOM series in the viewer.");
    }

    // Working directory
    Path tmpDir;
    try {
      tmpDir = Files.createTempDirectory("sex_classifier_");
    } catch (Exception e) {
      return PipelineResult.error("Could not create temporary directory: " + e.getMessage());
    }

    try {
      // Step 1+2: Detect SC and extract images (pure Java)
      File scDir = tmpDir.resolve("sc").toFile();
      LOGGER.info("Scanning {} file(s) for Secondary Captures…", viewerFiles.size());
      List<File> pngs = DicomExtractor.extractSecondaryCaptures(viewerFiles, scDir);

      if (pngs.isEmpty()) {
        // Build a diagnostic error message
        int total = DicomExtractor.lastTotal;
        int dicom = DicomExtractor.lastDicom;
        int sc    = DicomExtractor.lastSc;
        int pix   = DicomExtractor.lastPixOk;

        String diag;
        if (dicom == 0) {
          diag = "No DICOM files found among the " + total + " collected file(s).";
        } else if (sc == 0) {
          diag = "No Secondary Capture (SC) found.<br>"
               + "DICOM files: " + dicom + " | SC detected: 0<br>"
               + "Check if Modality is 'SC' in your DICOM.";
        } else {
          diag = "SC detected: " + sc + " | Pixels extracted: " + pix + "<br>"
               + "Failed to extract pixels from SC.<br>"
               + "The compression format may not be supported.";
        }
        return PipelineResult.error(diag);
      }

      LOGGER.info("{} SC image(s) extracted.", pngs.size());

      // Step 3: Pivot detection (Python / ResNet-50)
      if (modelPath == null || modelPath.isEmpty()) {
        modelPath = findModelPath();
      }
      File pivoDir = tmpDir.resolve("pivo").toFile();
      List<File> result = PivotDetector.detectAndSaveWindow(pngs, pivoDir, modelPath);

      if (result.isEmpty()) {
        return PipelineResult.error("Pivot detection produced no images.");
      }

      return PipelineResult.success(pivoDir, result);

    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return PipelineResult.error("Pipeline interrupted.");
    } catch (Exception e) {
      LOGGER.error("Pipeline error", e);
      return PipelineResult.error("Pipeline error: " + e.getMessage());
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Model path resolution (pivo.pt inside repository models/)
  // ───────────────────────────────────────────────────────────────────────────

  private static String findModelPath() {
    // 1. Relative to JAR location → ../../models/pivo.pt
    // (weasis-sex-classifier/target/*.jar → weasis-sex-classifier/models/pivo.pt)
    try {
      File jarFile = new File(
          SexClassifierAction.class.getProtectionDomain()
              .getCodeSource().getLocation().toURI());
      // JAR is at <project>/target/weasis-sex-classifier-*.jar
      // models/ is at <project>/models/
      File modelCandidate = new File(jarFile.getParentFile().getParentFile(), "models/pivo.pt");
      if (modelCandidate.exists()) {
        LOGGER.info("Using model: {}", modelCandidate);
        return modelCandidate.getAbsolutePath();
      }
    } catch (URISyntaxException | NullPointerException ignore) {}

    // 2. models/ relative to working directory
    File cwd = new File(System.getProperty("user.dir"), "models/pivo.pt");
    if (cwd.exists()) return cwd.getAbsolutePath();

    // 3. Absolute path inside Weasis native bundle
    File native1 = new File(System.getProperty("user.home"),
        "Documents/Iniciação_Científica/LabRoM_IML/weasis-sex-classifier/models/pivo.pt");
    if (native1.exists()) return native1.getAbsolutePath();

    LOGGER.warn("pivo.pt not found. Place it in weasis-sex-classifier/models/pivo.pt");
    return ""; // PivotDetector falls back to centre image
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Result type
  // ───────────────────────────────────────────────────────────────────────────

  public static final class PipelineResult {
    public final boolean    success;
    public final File       pivoDir;
    public final List<File> images;
    public final String     error;

    private PipelineResult(boolean s, File d, List<File> i, String e) {
      success = s; pivoDir = d; images = i; error = e;
    }
    public static PipelineResult success(File d, List<File> i) {
      return new PipelineResult(true, d, i, null);
    }
    public static PipelineResult error(String msg) {
      return new PipelineResult(false, null, null, msg);
    }
  }
}
