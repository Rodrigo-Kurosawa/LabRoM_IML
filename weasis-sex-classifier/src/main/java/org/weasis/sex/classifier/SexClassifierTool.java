/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 *
 * Side panel: controls, final verdict, per-image classification table.
 * Pivot images → main viewer.  Grad-CAM heatmaps → adjacent viewer.
 */
package org.weasis.sex.classifier;

import bibliothek.gui.dock.common.CLocation;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
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
import javax.swing.JScrollPane;
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
          tool.showResult(result);
        } else {
          tool.setStatus("\u2717 " + result.error, Color.RED.darker());
        }
      });
    }
  }

  // ── UI fields ─────────────────────────────────────────────────────────────
  private final JPanel  mainPanel;
  private final JLabel  statusLabel  = new JLabel("Ready.", SwingConstants.CENTER);
  private final JLabel  infoLabel    = new JLabel(
      "Load a DICOM series in the viewer.", SwingConstants.CENTER);

  // Verdict panel (shown after classification)
  private final JPanel  verdictPanel = new JPanel(new GridBagLayout());
  private final JLabel  verdictLabel = new JLabel("", SwingConstants.CENTER);
  private final JLabel  verdictConf  = new JLabel("", SwingConstants.CENTER);

  // Per-image rows (shown in scroll pane)
  private final JPanel     rowsPanel  = new JPanel();
  private final JScrollPane tableScroll;

  private JButton runBtn;

  public SexClassifierTool() {
    super(BUTTON_NAME, Insertable.Type.TOOL, 25);
    setDockableWidth(380);

    rowsPanel.setLayout(new BoxLayout(rowsPanel, BoxLayout.Y_AXIS));
    tableScroll = new JScrollPane(rowsPanel);
    tableScroll.setBorder(BorderFactory.createTitledBorder("Per-image results"));
    tableScroll.getVerticalScrollBar().setUnitIncrement(16);
    tableScroll.setPreferredSize(new Dimension(360, 200));

    buildVerdictPanel();

    mainPanel = new JPanel(new BorderLayout(0, 6));
    INSTANCES.add(this);
    buildUI();
  }

  // ── Build UI ──────────────────────────────────────────────────────────────

  private void buildVerdictPanel() {
    verdictPanel.setBorder(BorderFactory.createTitledBorder("Result"));
    verdictPanel.setVisible(false);

    verdictLabel.setFont(verdictLabel.getFont().deriveFont(Font.BOLD, 28f));
    verdictConf.setFont(verdictConf.getFont().deriveFont(12f));
    verdictConf.setForeground(Color.DARK_GRAY);

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = 0; gbc.gridy = 0;
    gbc.insets = new Insets(6, 4, 2, 4);
    gbc.fill = GridBagConstraints.HORIZONTAL;
    verdictPanel.add(verdictLabel, gbc);
    gbc.gridy = 1;
    gbc.insets = new Insets(0, 4, 6, 4);
    verdictPanel.add(verdictConf, gbc);
  }

  private void buildUI() {
    mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

    // ── Controls strip ────────────────────────────────────────────────────
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

    // ── South block: verdict + per-image table ────────────────────────────
    JPanel south = new JPanel(new BorderLayout(0, 6));
    south.add(verdictPanel,  BorderLayout.NORTH);
    south.add(tableScroll,   BorderLayout.CENTER);

    mainPanel.add(controls, BorderLayout.NORTH);
    mainPanel.add(south,    BorderLayout.CENTER);
  }

  // ── PluginTool ────────────────────────────────────────────────────────────

  @Override public Component getToolComponent() { return mainPanel; }

  @Override protected void changeToolWindowAnchor(CLocation clocation) {}

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

    // Reset result panels
    verdictPanel.setVisible(false);
    rowsPanel.removeAll();
    tableScroll.setVisible(false);
    mainPanel.revalidate();
    mainPanel.repaint();

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
        showResult(result);
      } else {
        setStatus("\u2717 " + result.error, Color.RED.darker());
      }
    });
  }

  // ── Results display ───────────────────────────────────────────────────────

  void showResult(SexClassifierAction.PipelineResult result) {
    List<File> images = result.images;
    SexClassifier.ClassificationResult classification = result.classification;

    LOGGER.info("showResult() — {} pivot image(s)", images == null ? 0 : images.size());

    if (images == null || images.isEmpty()) {
      setStatus("No images found.", Color.RED.darker());
      return;
    }

    // Open composite images (pivot | heatmap) in the main viewer.
    // Fall back to plain pivot images if composites were not built.
    List<File> toOpen = (result.composites != null && !result.composites.isEmpty())
        ? result.composites : images;
    openInMainViewer(toOpen);

    // Show classification results
    if (classification != null && classification.isSuccess()) {
      showVerdict(classification);
      showPerImageTable(classification.perImage);
      setStatus(
          String.format("\u2713 %s (%.1f%%) \u2014 %d image(s)",
              classification.finalLabel,
              classification.finalProbability * 100,
              classification.perImage.size()),
          new Color(0, 120, 0));
    } else {
      String errMsg = (classification != null && classification.error != null)
          ? classification.error : "classifier unavailable";
      verdictLabel.setText("\u2717 Error");
      verdictLabel.setForeground(Color.RED.darker());
      verdictConf.setText("<html><center>" + errMsg + "</center></html>");
      verdictConf.setForeground(Color.RED.darker());
      verdictPanel.setVisible(true);
      verdictPanel.revalidate();
      verdictPanel.repaint();
      setStatus(
          "\u2713 " + images.size() + " pivot image(s) opened \u2014 classification failed",
          new Color(0, 100, 0));
    }
  }

  // ── Verdict panel ─────────────────────────────────────────────────────────

  private void showVerdict(SexClassifier.ClassificationResult result) {
    String label = result.finalLabel;
    double prob  = result.finalProbability;

    // Colour: blue for masculine, red for feminine
    boolean isMale = label.toUpperCase().contains("MASCUL") || label.equalsIgnoreCase("M");
    Color c = isMale ? new Color(30, 90, 200) : new Color(200, 40, 60);

    verdictLabel.setText(label);
    verdictLabel.setForeground(c);
    verdictConf.setText(String.format("%.1f%% confidence (soft vote over %d images)",
        prob * 100, result.perImage.size()));

    verdictPanel.setVisible(true);
    verdictPanel.revalidate();
    verdictPanel.repaint();
  }

  // ── Per-image table ───────────────────────────────────────────────────────

  private void showPerImageTable(List<SexClassifier.ImageResult> perImage) {
    rowsPanel.removeAll();

    // Header row
    JPanel header = makeRow(
        new Color(60, 60, 60), Color.WHITE,
        "Image", "Label", "Prob", Font.BOLD);
    rowsPanel.add(header);

    for (int i = 0; i < perImage.size(); i++) {
      SexClassifier.ImageResult r = perImage.get(i);
      Color bg = (i % 2 == 0) ? Color.WHITE : new Color(245, 245, 250);
      String imgName = r.imageFile != null ? r.imageFile.getName() : "img_" + r.index;
      String probStr = String.format("%.1f%%", r.probability * 100);
      boolean male = r.label.toUpperCase().contains("MASCUL") || r.label.equalsIgnoreCase("M");
      Color labelColor = male ? new Color(30, 90, 200) : new Color(200, 40, 60);
      JPanel row = makeRow(bg, Color.BLACK, imgName, r.label, probStr, Font.PLAIN);
      // Colour the label cell
      for (Component comp : row.getComponents()) {
        if (comp instanceof JLabel lbl && r.label.equals(lbl.getText())) {
          lbl.setForeground(labelColor);
          lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        }
      }
      rowsPanel.add(row);
    }

    tableScroll.setVisible(true);
    rowsPanel.revalidate();
    rowsPanel.repaint();
    tableScroll.revalidate();
    tableScroll.repaint();
    mainPanel.revalidate();
    mainPanel.repaint();
  }

  private static JPanel makeRow(Color bg, Color fg,
                                 String col1, String col2, String col3,
                                 int fontStyle) {
    JPanel row = new JPanel(new GridBagLayout());
    row.setBackground(bg);
    row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

    GridBagConstraints g = new GridBagConstraints();
    g.fill  = GridBagConstraints.HORIZONTAL;
    g.insets = new Insets(1, 4, 1, 4);

    g.gridx = 0; g.weightx = 1.0;
    row.add(styledLabel(col1, fg, fontStyle, SwingConstants.LEFT), g);

    g.gridx = 1; g.weightx = 0.25;
    row.add(styledLabel(col2, fg, fontStyle, SwingConstants.CENTER), g);

    g.gridx = 2; g.weightx = 0.3;
    row.add(styledLabel(col3, fg, fontStyle, SwingConstants.RIGHT), g);

    return row;
  }

  private static JLabel styledLabel(String text, Color fg, int style, int align) {
    JLabel l = new JLabel(text, align);
    l.setForeground(fg);
    l.setFont(l.getFont().deriveFont(style, 11f));
    return l;
  }

  // ── Viewer integration ────────────────────────────────────────────────────

  /** Opens {@code images} as a new series in the Weasis main viewer. */
  @SuppressWarnings("unchecked")
  private void openInMainViewer(List<File> images) {
    try {
      MediaReader<MediaElement> firstReader =
          ViewerPluginBuilder.getMedia(images.get(0).toPath());
      if (firstReader == null) {
        LOGGER.warn("No MediaReader for {}", images.get(0));
        return;
      }

      MediaSeries<MediaElement> series = firstReader.getMediaSeries();
      for (int i = 1; i < images.size(); i++) {
        MediaReader<MediaElement> r =
            ViewerPluginBuilder.getMedia(images.get(i).toPath());
        if (r != null) {
          MediaElement[] elems = r.getMediaElement();
          if (elems != null) {
            for (MediaElement elem : elems) series.addMedia(elem);
          }
        }
      }

      ViewerPluginBuilder.openSequenceInDefaultPlugin(
          series, ViewerPluginBuilder.DefaultDataModel, true, true);

    } catch (Exception e) {
      LOGGER.warn("Cannot open images in viewer: {}", e.getMessage());
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
