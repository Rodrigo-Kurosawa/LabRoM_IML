/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 */
package org.weasis.sex.classifier;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
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
      List<File> composites = buildComposites(
          pivotImages, classification.perImage, compositeDir, classification);

      String patientId = extractPatientId(viewerFiles);
      return PipelineResult.success(pivoDir, pivotImages, classification, composites, patientId);

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
   * For every pivot image, creates a composite PNG with:
   * <ul>
   *   <li>A header bar at the top showing the general classification result.</li>
   *   <li>Left half: the original pivot image.</li>
   *   <li>Right half: the Grad-CAM heatmap (scaled to the same height).</li>
   * </ul>
   * If a heatmap is missing the pivot image alone fills the slot so the series
   * length stays consistent.
   *
   * <p>Runs in the background worker thread — no EDT calls here.
   */
  private static List<File> buildComposites(
      List<File> pivotImages,
      List<SexClassifier.ImageResult> perImage,
      File outDir,
      SexClassifier.ClassificationResult classification) {

    outDir.mkdirs();
    List<File> composites = new ArrayList<>();

    // Quick look-ups: pivot list index → heatmap file, image result
    java.util.Map<Integer, File>                    heatmapByIndex = new java.util.HashMap<>();
    java.util.Map<Integer, SexClassifier.ImageResult> resultByIndex = new java.util.HashMap<>();
    for (SexClassifier.ImageResult r : perImage) {
      if (r.heatmap != null && r.heatmap.exists()) heatmapByIndex.put(r.index, r.heatmap);
      resultByIndex.put(r.index, r);
    }

    // Colours derived from the final classification (shared across all frames)
    Color barBg    = headerBgColor(classification);
    Color captionBg = barBg.darker();
    String headerText = buildHeaderText(classification);

    for (int i = 0; i < pivotImages.size(); i++) {
      File pivotFile = pivotImages.get(i);
      File dest = new File(outDir, String.format("comp_%04d.png", i));
      try {
        BufferedImage pivot = toRgb(ImageIO.read(pivotFile));
        if (pivot == null) continue;

        int bodyH  = pivot.getHeight();
        int pivotW = pivot.getWidth();

        // ── Scale heatmap to pivot height ─────────────────────────────────
        BufferedImage heatScaled = null;
        File heatFile = heatmapByIndex.get(i);
        if (heatFile != null) {
          BufferedImage heat = toRgb(ImageIO.read(heatFile));
          if (heat != null) {
            int hw = (int) Math.round((double) heat.getWidth() * bodyH / heat.getHeight());
            heatScaled = new BufferedImage(hw, bodyH, BufferedImage.TYPE_INT_RGB);
            Graphics2D gs = heatScaled.createGraphics();
            gs.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gs.drawImage(heat, 0, 0, hw, bodyH, null);
            gs.dispose();
          }
        }

        // ── Compute composite dimensions ──────────────────────────────────
        int sep      = (heatScaled != null) ? 3 : 0;
        int heatW    = (heatScaled != null) ? heatScaled.getWidth() : 0;
        int totalW   = pivotW + sep + heatW;
        int headerH  = Math.min(90, Math.max(54, bodyH / 9));
        int captionH = Math.min(44, Math.max(28, bodyH / 16));
        int totalH   = headerH + bodyH + captionH;

        BufferedImage composite = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = composite.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON);

        // ── Header bar (full width) ───────────────────────────────────────
        g.setColor(barBg);
        g.fillRect(0, 0, totalW, headerH);

        // Auto-fit: shrink font until text fits in one line, then fall back
        // to two lines if even the minimum font size is too wide.
        int headerFontSz = Math.max(14, headerH - 22);
        Font hFont = new Font(Font.SANS_SERIF, Font.BOLD, headerFontSz);
        g.setFont(hFont);
        FontMetrics hfm = g.getFontMetrics();
        int pad = 16; // horizontal padding on each side
        while (hfm.stringWidth(headerText) > totalW - pad && headerFontSz > 11) {
          headerFontSz--;
          hFont = new Font(Font.SANS_SERIF, Font.BOLD, headerFontSz);
          g.setFont(hFont);
          hfm = g.getFontMetrics();
        }

        g.setColor(Color.WHITE);
        if (hfm.stringWidth(headerText) <= totalW - pad) {
          // Single-line: centred
          int htx = (totalW - hfm.stringWidth(headerText)) / 2;
          int hty = (headerH - hfm.getHeight()) / 2 + hfm.getAscent();
          g.drawString(headerText, Math.max(pad / 2, htx), hty);
        } else {
          // Two-line fallback: "General Classification:" / "<label>  (xx.x%)"
          int sepIdx = headerText.indexOf(": ");
          String hLine1 = sepIdx >= 0 ? headerText.substring(0, sepIdx + 1)
                                       : "General Classification:";
          String hLine2 = sepIdx >= 0 ? headerText.substring(sepIdx + 2) : headerText;
          // Shrink further so both lines fit
          while ((hfm.stringWidth(hLine1) > totalW - pad
                  || hfm.stringWidth(hLine2) > totalW - pad)
                 && headerFontSz > 9) {
            headerFontSz--;
            hFont = new Font(Font.SANS_SERIF, Font.BOLD, headerFontSz);
            g.setFont(hFont);
            hfm = g.getFontMetrics();
          }
          int lineH = hfm.getHeight();
          int blockH = lineH * 2 + 2;
          int baseY  = (headerH - blockH) / 2 + hfm.getAscent();
          g.drawString(hLine1,
              Math.max(pad / 2, (totalW - hfm.stringWidth(hLine1)) / 2), baseY);
          g.drawString(hLine2,
              Math.max(pad / 2, (totalW - hfm.stringWidth(hLine2)) / 2),
              baseY + lineH + 2);
        }

        // ── Body row (pivot | separator | heatmap) ────────────────────────
        int bodyY = headerH;
        g.drawImage(pivot, 0, bodyY, null);
        if (heatScaled != null) {
          g.setColor(Color.DARK_GRAY);
          g.fillRect(pivotW, bodyY, sep, bodyH);
          g.drawImage(heatScaled, pivotW + sep, bodyY, null);
        }

        // ── Per-frame caption (full width, under pivot + heatmap) ────────
        int captionY = headerH + bodyH;
        g.setColor(captionBg);
        g.fillRect(0, captionY, totalW, captionH);

        SexClassifier.ImageResult imgResult = resultByIndex.get(i);
        if (imgResult != null) {
          String displayLbl = toDisplayLabel(imgResult.label);
          String captionText = String.format("Frame %d: %s   %.1f%%",
              i + 1, displayLbl, imgResult.probability * 100);
          int captionFontSz = Math.max(12, captionH - 14);
          g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, captionFontSz));
          boolean feminine = imgResult.label != null
              && imgResult.label.toUpperCase().contains("FEMIN");
          g.setColor(feminine ? Color.decode("#e0566b") : Color.WHITE);
          FontMetrics cfm = g.getFontMetrics();
          int ctx = (totalW - cfm.stringWidth(captionText)) / 2;
          int cty = captionY + (captionH - cfm.getHeight()) / 2 + cfm.getAscent();
          g.drawString(captionText, Math.max(6, ctx), cty);
        }

        g.dispose();
        ImageIO.write(composite, "png", dest);
        composites.add(dest);

      } catch (Exception e) {
        LOGGER.warn("Composite build failed for {}: {}", pivotFile.getName(), e.getMessage());
      }
    }

    LOGGER.info("Built {} composite image(s) in {}", composites.size(), outDir);
    return composites;
  }

  /** Returns the header-bar background colour based on the final classification. */
  private static Color headerBgColor(SexClassifier.ClassificationResult c) {
    if (c != null && c.isSuccess() && c.finalLabel != null) {
      String lbl = c.finalLabel.toUpperCase();
      if (lbl.contains("MASCUL")) return Color.decode("#0d2b55"); // dark navy
      if (lbl.contains("FEMIN"))  return Color.decode("#5c1a2e"); // wine red
    }
    return Color.decode("#1a1a2e"); // charcoal default
  }

  /** Builds the human-readable header line from the final classification. */
  private static String buildHeaderText(SexClassifier.ClassificationResult c) {
    if (c == null || !c.isSuccess() || c.finalLabel == null) {
      return "General Classification: —";
    }
    String display = toDisplayLabel(c.finalLabel);
    return String.format("General Classification: %s   (%.1f%%)",
        display, c.finalProbability * 100);
  }

  /**
   * Maps YOLO class names (Portuguese) to display labels (English).
   * Falls back to the raw label if no mapping is found.
   */
  static String toDisplayLabel(String yoloLabel) {
    if (yoloLabel == null) return "Unknown";
    switch (yoloLabel.trim()) {
      // Skull variants
      case "Cranio Masculino":
      case "Cranio Masculina": return "Male Skull";
      case "Cranio Feminino":
      case "Cranio Feminina":  return "Female Skull";
      // Pelvis variants (YOLO may use -o or -a endings)
      case "Pelve Masculino":
      case "Pelve Masculina":  return "Male Pelvis";
      case "Pelve Feminino":
      case "Pelve Feminina":   return "Female Pelvis";
      default:
        // Generic fallback preserving correct English word order
        String s = yoloLabel.trim();
        boolean skull  = s.contains("Crani");
        boolean pelvis = s.contains("Pelv");
        boolean male   = s.contains("Mascul");
        boolean female = s.contains("Femin");
        String sex  = male ? "Male" : female ? "Female" : "";
        String bone = skull ? "Skull" : pelvis ? "Pelvis" : s;
        return (sex + " " + bone).trim();
    }
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

  /** Reads PatientID from the first parseable DICOM file; falls back to a timestamp. */
  private static String extractPatientId(List<File> files) {
    for (File f : files) {
      if (!DicomExtractor.isDicom(f)) continue;
      try (org.dcm4che3.io.DicomInputStream dis =
               new org.dcm4che3.io.DicomInputStream(f)) {
        dis.setIncludeBulkData(org.dcm4che3.io.DicomInputStream.IncludeBulkData.NO);
        org.dcm4che3.data.Attributes attrs = dis.readDataset();
        String pid = attrs.getString(org.dcm4che3.data.Tag.PatientID);
        if (pid != null && !pid.isBlank()) return pid.trim();
      } catch (Exception ignore) {}
    }
    return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date());
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
    public final String                             patientId;
    public final String                             error;

    private PipelineResult(boolean s, File d, List<File> i, List<File> comp,
                            SexClassifier.ClassificationResult c, String pid, String e) {
      success = s; pivoDir = d; images = i; composites = comp;
      classification = c; patientId = pid; error = e;
    }
    public static PipelineResult success(File d, List<File> i,
                                         SexClassifier.ClassificationResult c,
                                         List<File> comp, String pid) {
      return new PipelineResult(true, d, i, comp, c, pid, null);
    }
    public static PipelineResult error(String msg) {
      return new PipelineResult(false, null, null, null, null, null, msg);
    }
  }
}
