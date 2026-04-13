/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 *
 * Side panel with an inline scrollable image gallery.
 * Results are shown directly inside this panel — no floating dialogs.
 */
package org.weasis.sex.classifier;

import bibliothek.gui.dock.common.CLocation;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaReader;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.ui.docking.PluginTool;
import org.weasis.core.ui.editor.ViewerPluginBuilder;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.dicom.viewer2d.EventManager;

public class SexClassifierTool extends PluginTool {

  private static final Logger LOGGER = LoggerFactory.getLogger(SexClassifierTool.class);

  public static final String BUTTON_NAME = "Sex Classification"; // NON-NLS

  // Registry so the toolbar button can push results to any open panel instance
  private static final java.util.concurrent.CopyOnWriteArrayList<SexClassifierTool> INSTANCES =
      new java.util.concurrent.CopyOnWriteArrayList<>();

  public static void pushResult(SexClassifierAction.PipelineResult result) {
    for (SexClassifierTool tool : INSTANCES) {
      SwingUtilities.invokeLater(() -> {
        if (result.success) {
          tool.showImages(result.images);
        } else {
          tool.setStatus("\u2717 " + result.error, Color.RED.darker());
        }
      });
    }
  }

  // ── UI ────────────────────────────────────────────────────────────────────
  private final JPanel mainPanel;
  private final JLabel statusLabel = new JLabel("Ready.", SwingConstants.CENTER);
  private final JLabel infoLabel   = new JLabel(
      "Load a DICOM series in the viewer.", SwingConstants.CENTER);
  private       JButton runBtn;

  public SexClassifierTool() {
    super(BUTTON_NAME, Insertable.Type.TOOL, 25);
    setDockableWidth(380);
    mainPanel = new JPanel(new BorderLayout(0, 4));
    INSTANCES.add(this);
    buildUI();
  }

  // ── Build UI ──────────────────────────────────────────────────────────────

  private void buildUI() {
    mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

    // Controls — compact strip at the top
    JPanel controls = new JPanel();
    controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));

    JLabel title = new JLabel("Sex Classification", SwingConstants.CENTER);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
    title.setAlignmentX(Component.CENTER_ALIGNMENT);
    controls.add(title);
    controls.add(Box.createVerticalStrut(6));

    infoLabel.setFont(infoLabel.getFont().deriveFont(Font.ITALIC, 11f));
    infoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
    infoLabel.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createTitledBorder("Active series"),
        BorderFactory.createEmptyBorder(2, 4, 2, 4)));
    controls.add(infoLabel);
    controls.add(Box.createVerticalStrut(6));

    runBtn = new JButton("\u25b6  Classify current series"); // NON-NLS
    runBtn.setFont(runBtn.getFont().deriveFont(Font.BOLD, 12f));
    runBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
    runBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
    runBtn.addActionListener(e -> runPipeline());
    controls.add(runBtn);
    controls.add(Box.createVerticalStrut(4));

    statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
    statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
    statusLabel.setForeground(Color.GRAY);
    controls.add(statusLabel);

    mainPanel.add(controls, BorderLayout.NORTH);
  }

  // ── PluginTool ────────────────────────────────────────────────────────────

  @Override
  public Component getToolComponent() {
    return mainPanel;
  }

  @Override
  protected void changeToolWindowAnchor(CLocation clocation) {
    // no relayout needed
  }

  // ── Pipeline trigger ──────────────────────────────────────────────────────

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private List<File> getViewerFiles() {
    Set<File> files = new LinkedHashSet<>();
    File firstFile = null;
    try {
      ViewCanvas<?> pane = EventManager.getInstance().getSelectedViewPane();
      if (pane != null) {
        MediaSeries series = pane.getSeries();
        if (series != null) {
          for (Object m : (Iterable<?>) series.getMedias(null, null)) {
            if (m instanceof MediaElement) {
              URI uri = ((MediaElement) m).getMediaURI();
              if (uri != null && "file".equals(uri.getScheme())) {
                File f = new File(uri);
                if (f.exists()) {
                  files.add(f);
                  if (firstFile == null) firstFile = f;
                }
              }
            }
          }
        }
      }
      // Scan siblings so an adjacent SC series in the same study folder is included
      if (firstFile != null) {
        File dir = firstFile.getParentFile();
        if (dir != null && dir.isDirectory()) {
          File[] siblings = dir.listFiles();
          if (siblings != null) {
            for (File sib : siblings) {
              if (sib.isFile()) files.add(sib);
            }
          }
        }
      }
    } catch (Exception e) {
      LOGGER.warn("Could not read viewer files: {}", e.getMessage());
    }
    LOGGER.info("getViewerFiles(): {} file(s)", files.size());
    return new ArrayList<>(files);
  }

  private void runPipeline() {
    List<File> viewerFiles = getViewerFiles();

    if (viewerFiles.isEmpty()) {
      setStatus("\u26a0 No series loaded in the viewer.", Color.ORANGE.darker());
      return;
    }
    if (viewerFiles.stream().noneMatch(DicomExtractor::isDicom)) {
      setStatus("\u2717 Unsupported format. Load a DICOM series.", Color.RED.darker());
      return;
    }

    runBtn.setEnabled(false);
    setStatus("\u23f3 Starting pipeline\u2026", Color.GRAY);

    long startMs = System.currentTimeMillis();
    javax.swing.Timer progressTimer = new javax.swing.Timer(1000, null);
    progressTimer.addActionListener(tick -> setStatus(
        String.format("\u23f3 Processing\u2026 %ds (may take 1-2 min)",
            (System.currentTimeMillis() - startMs) / 1000),
        Color.GRAY));
    progressTimer.start();

    SexClassifierAction.runPipelineAsync(viewerFiles, result -> {
      progressTimer.stop();
      runBtn.setEnabled(true);
      if (result.success) {
        showImages(result.images);
      } else {
        setStatus("\u2717 " + result.error, Color.RED.darker());
      }
    });
  }

  // ── Main-viewer integration ───────────────────────────────────────────────

  /** Opens the pivot-window images in the Weasis main viewer. */
  void showImages(List<File> images) {
    LOGGER.info("showImages() — {} file(s)", images == null ? 0 : images.size());
    if (images == null || images.isEmpty()) {
      setStatus("No images found.", Color.RED.darker());
      return;
    }
    setStatus("\u2713 " + images.size() + " image(s) — opening in viewer\u2026",
        new Color(0, 120, 0));
    openInMainViewer(images);
  }

  @SuppressWarnings("unchecked")
  private void openInMainViewer(List<File> images) {
    try {
      MediaReader<MediaElement> firstReader =
          ViewerPluginBuilder.getMedia(images.get(0).toPath());
      if (firstReader == null) {
        LOGGER.warn("No MediaReader found for {}", images.get(0));
        setStatus("\u2717 Cannot open images in viewer.", Color.RED.darker());
        return;
      }

      MediaSeries<MediaElement> series = firstReader.getMediaSeries();
      for (int i = 1; i < images.size(); i++) {
        MediaReader<MediaElement> reader =
            ViewerPluginBuilder.getMedia(images.get(i).toPath());
        if (reader != null) {
          MediaElement[] elems = reader.getMediaElement();
          if (elems != null) {
            for (MediaElement elem : elems) {
              series.addMedia(elem);
            }
          }
        }
      }

      ViewerPluginBuilder.openSequenceInDefaultPlugin(
          series, ViewerPluginBuilder.DefaultDataModel, true, true);

    } catch (Exception e) {
      LOGGER.warn("Cannot open pivot images in main viewer: {}", e.getMessage());
      setStatus("\u2717 Cannot open in viewer: " + e.getMessage(), Color.RED.darker());
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  void setStatus(String text, Color color) {
    statusLabel.setText(text);
    statusLabel.setForeground(color);
    statusLabel.repaint();
    mainPanel.revalidate();
    mainPanel.repaint();
  }
}
