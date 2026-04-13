/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 */
package org.weasis.sex.classifier;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
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
      List<File> pivotImages = PivotDetector.detectAndSaveWindow(pngs, pivoDir, modelPath);

      if (pivotImages.isEmpty()) {
        return PipelineResult.error("Pivot detection produced no images.");
      }

      // Step 4: Sex classification (YOLOv8 / best.pt) + Grad-CAM heatmaps
      File heatmapDir = tmpDir.resolve("heatmaps").toFile();
      String bestModelPath = SexClassifier.findModelPath();
      SexClassifier.ClassificationResult classification =
          SexClassifier.classify(pivotImages, heatmapDir, bestModelPath);

      if (!classification.isSuccess()) {
        LOGGER.warn("Classification failed: {}", classification.error);
      }

      // Step 5: Build side-by-side composites (pivot | heatmap) for synchronized viewing
      File compositeDir = tmpDir.resolve("composites").toFile();
      List<File> composites = buildComposites(pivotImages, classification.perImage, compositeDir);

      return PipelineResult.success(pivoDir, pivotImages, classification, composites);

    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return PipelineResult.error("Pipeline interrupted.");
    } catch (Exception e) {
      LOGGER.error("Pipeline error", e);
      return PipelineResult.error("Pipeline error: " + e.getMessage());
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Composite builder – pivot image | heatmap side by side
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * For every pivot image, creates a side-by-side PNG: original on the left,
   * Grad-CAM heatmap on the right (scaled to the same height).  If a heatmap
   * is missing for a given index the pivot image is saved unchanged so the
   * series stays complete.
   *
   * <p>This runs in the background worker thread — no EDT work here.
   */
  private static List<File> buildComposites(
      List<File> pivotImages,
      List<SexClassifier.ImageResult> perImage,
      File outDir) {

    outDir.mkdirs();
    List<File> composites = new ArrayList<>();

    // Build a fast index: pivot-list-index → heatmap file
    java.util.Map<Integer, File> heatmapByIndex = new java.util.HashMap<>();
    for (SexClassifier.ImageResult r : perImage) {
      if (r.heatmap != null && r.heatmap.exists()) {
        heatmapByIndex.put(r.index, r.heatmap);
      }
    }

    for (int i = 0; i < pivotImages.size(); i++) {
      File pivotFile = pivotImages.get(i);
      File dest = new File(outDir, String.format("comp_%04d.png", i));
      try {
        BufferedImage pivot = toRgb(ImageIO.read(pivotFile));
        if (pivot == null) continue;

        File heatFile = heatmapByIndex.get(i);
        BufferedImage composite;

        if (heatFile != null) {
          BufferedImage heat = toRgb(ImageIO.read(heatFile));
          if (heat != null) {
            // Scale heatmap to match pivot height, preserving aspect ratio
            int h  = pivot.getHeight();
            int hw = (int) Math.round((double) heat.getWidth() * h / heat.getHeight());
            BufferedImage heatScaled = new BufferedImage(hw, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D gs = heatScaled.createGraphics();
            gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gs.drawImage(heat, 0, 0, hw, h, null);
            gs.dispose();

            // Thin separator line between the two halves
            int sep = 2;
            composite = new BufferedImage(
                pivot.getWidth() + sep + hw, h, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D gc = composite.createGraphics();
            gc.setColor(java.awt.Color.DARK_GRAY);
            gc.fillRect(0, 0, composite.getWidth(), composite.getHeight());
            gc.drawImage(pivot, 0, 0, null);
            gc.drawImage(heatScaled, pivot.getWidth() + sep, 0, null);
            gc.dispose();
          } else {
            composite = pivot;
          }
        } else {
          composite = pivot; // no heatmap: show pivot alone
        }

        ImageIO.write(composite, "png", dest);
        composites.add(dest);

      } catch (Exception e) {
        LOGGER.warn("Composite build failed for {}: {}", pivotFile.getName(), e.getMessage());
      }
    }

    LOGGER.info("Built {} composite image(s) in {}", composites.size(), outDir);
    return composites;
  }

  private static BufferedImage toRgb(BufferedImage img) {
    if (img == null) return null;
    if (img.getType() == BufferedImage.TYPE_INT_RGB) return img;
    BufferedImage rgb = new BufferedImage(
        img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
    rgb.createGraphics().drawImage(img, 0, 0, null);
    return rgb;
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Model path resolution
  // ───────────────────────────────────────────────────────────────────────────

  private static String findModelPath() {
    return PivotDetector.findModelPath();
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Result type
  // ───────────────────────────────────────────────────────────────────────────

  public static final class PipelineResult {
    public final boolean                            success;
    public final File                               pivoDir;
    public final List<File>                         images;      // raw pivot PNGs
    public final List<File>                         composites;  // pivot|heatmap side-by-side
    public final SexClassifier.ClassificationResult classification;
    public final String                             error;

    private PipelineResult(boolean s, File d, List<File> i, List<File> comp,
                            SexClassifier.ClassificationResult c, String e) {
      success = s; pivoDir = d; images = i; composites = comp; classification = c; error = e;
    }
    public static PipelineResult success(File d, List<File> i,
                                         SexClassifier.ClassificationResult c,
                                         List<File> comp) {
      return new PipelineResult(true, d, i, comp, c, null);
    }
    public static PipelineResult error(String msg) {
      return new PipelineResult(false, null, null, null, null, msg);
    }
  }
}
