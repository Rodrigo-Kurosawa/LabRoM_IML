/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 *
 * Side panel: scrollable history of classification result cards.
 * Classification is triggered from the toolbar action, not from here.
 */
package org.weasis.sex.classifier;

import bibliothek.gui.dock.common.CLocation;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
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

public class SexClassifierTool extends PluginTool {

  private static final Logger LOGGER = LoggerFactory.getLogger(SexClassifierTool.class);

  public static final String BUTTON_NAME = "Sex Classification"; // NON-NLS

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
  private final JPanel     mainPanel;
  private final JLabel     statusLabel  = new JLabel(" ", SwingConstants.CENTER);
  private final JPanel     cardsPanel   = new JPanel();
  private final JScrollPane cardsScroll;

  public SexClassifierTool() {
    super(BUTTON_NAME, Insertable.Type.TOOL, 25);
    setDockableWidth(400);

    cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
    cardsScroll = new JScrollPane(cardsPanel);
    cardsScroll.setBorder(BorderFactory.createTitledBorder("Results"));
    cardsScroll.getVerticalScrollBar().setUnitIncrement(16);

    mainPanel = new JPanel(new BorderLayout(0, 8));
    INSTANCES.add(this);
    buildUI();
  }

  // ── Build UI ──────────────────────────────────────────────────────────────

  private void buildUI() {
    mainPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

    JLabel title = new JLabel("Sex Classification", SwingConstants.CENTER);
    title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
    title.setAlignmentX(Component.CENTER_ALIGNMENT);

    statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
    statusLabel.setForeground(Color.GRAY);
    statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

    JPanel top = new JPanel();
    top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
    top.add(title);
    top.add(Box.createVerticalStrut(4));
    top.add(statusLabel);

    mainPanel.add(top, BorderLayout.NORTH);
    mainPanel.add(cardsScroll, BorderLayout.CENTER);
  }

  // ── PluginTool ────────────────────────────────────────────────────────────

  @Override public Component getToolComponent() { return mainPanel; }

  @Override protected void changeToolWindowAnchor(CLocation clocation) {}

  // ── Results display ───────────────────────────────────────────────────────

  void showResult(SexClassifierAction.PipelineResult result) {
    // Open composites in the viewer
    List<File> toOpen =
        (result.composites != null && !result.composites.isEmpty())
        ? result.composites : result.images;
    if (toOpen != null && !toOpen.isEmpty()) openInMainViewer(toOpen);

    SexClassifier.ClassificationResult cl = result.classification;
    if (cl == null || !cl.isSuccess()) {
      String err = (cl != null && cl.error != null) ? cl.error : "classifier unavailable";
      setStatus("\u2717 " + err, Color.RED.darker());
      return;
    }

    // Insert new card at the top of the history list
    JPanel card = buildResultCard(result);
    cardsPanel.add(card, 0);
    cardsPanel.revalidate();
    cardsPanel.repaint();
    cardsScroll.revalidate();
    cardsScroll.repaint();

    setStatus(" ", Color.GRAY);
    mainPanel.revalidate();
    mainPanel.repaint();
  }

  // ── Result card ───────────────────────────────────────────────────────────

  private JPanel buildResultCard(SexClassifierAction.PipelineResult result) {
    SexClassifier.ClassificationResult cl = result.classification;
    String displayLabel = SexClassifierAction.toDisplayLabel(cl.finalLabel);
    boolean feminine = cl.finalLabel != null
        && cl.finalLabel.toUpperCase().contains("FEMIN");

    Color bgColor = feminine
        ? Color.decode("#5c1a2e")   // wine red
        : Color.decode("#0d2b55"); // dark navy

    // Outer wrapper: bottom margin between cards
    JPanel wrapper = new JPanel(new BorderLayout());
    wrapper.setOpaque(false);
    wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
    wrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));

    // Inner card with coloured background
    JPanel card = new JPanel(new BorderLayout(10, 0));
    card.setBackground(bgColor);
    card.setOpaque(true);
    card.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(bgColor.brighter(), 1),
        BorderFactory.createEmptyBorder(8, 8, 8, 8)));

    // ── Thumbnail (left) ──────────────────────────────────────────────────
    JLabel thumb = new JLabel();
    thumb.setPreferredSize(new Dimension(100, 90));
    thumb.setMinimumSize(new Dimension(100, 90));
    thumb.setHorizontalAlignment(SwingConstants.CENTER);
    thumb.setVerticalAlignment(SwingConstants.CENTER);
    thumb.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200, 100), 1));
    thumb.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    thumb.setToolTipText("Click to open in viewer");

    File pivotImg = centerPivot(result.images);
    if (pivotImg != null) {
      ImageIcon icon = makeThumbnail(pivotImg, 100, 90);
      thumb.setIcon(icon);
    }
    thumb.addMouseListener(new java.awt.event.MouseAdapter() {
      @Override public void mouseClicked(java.awt.event.MouseEvent e) {
        List<File> toOpen =
            (result.composites != null && !result.composites.isEmpty())
            ? result.composites : result.images;
        if (toOpen != null && !toOpen.isEmpty()) openInMainViewer(toOpen);
      }
      @Override public void mouseEntered(java.awt.event.MouseEvent e) {
        thumb.setBorder(BorderFactory.createLineBorder(Color.WHITE, 2));
      }
      @Override public void mouseExited(java.awt.event.MouseEvent e) {
        thumb.setBorder(BorderFactory.createLineBorder(
            new Color(200, 200, 200, 100), 1));
      }
    });

    // ── Right side: label + prob + export ────────────────────────────────
    JLabel lbl = new JLabel(displayLabel);
    lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 14f));
    lbl.setForeground(Color.WHITE);
    lbl.setAlignmentX(Component.LEFT_ALIGNMENT);

    String pid = (result.patientId != null && !result.patientId.isBlank())
        ? result.patientId : "—";
    JLabel patientLbl = new JLabel("Patient: " + pid);
    patientLbl.setFont(patientLbl.getFont().deriveFont(Font.ITALIC, 11f));
    patientLbl.setForeground(new Color(210, 210, 210));
    patientLbl.setAlignmentX(Component.LEFT_ALIGNMENT);

    JLabel prob = new JLabel(String.format("%.1f%% confidence", cl.finalProbability * 100));
    prob.setFont(prob.getFont().deriveFont(11f));
    prob.setForeground(new Color(220, 220, 220));
    prob.setAlignmentX(Component.LEFT_ALIGNMENT);

    JButton exportBtn = new JButton("Export Heatmap");
    exportBtn.setFont(exportBtn.getFont().deriveFont(10f));
    exportBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
    exportBtn.addActionListener(e -> exportHeatmap(result));

    JPanel right = new JPanel();
    right.setOpaque(false);
    right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
    right.add(lbl);
    right.add(Box.createVerticalStrut(2));
    right.add(patientLbl);
    right.add(Box.createVerticalStrut(3));
    right.add(prob);
    right.add(Box.createVerticalStrut(8));
    right.add(exportBtn);

    card.add(thumb, BorderLayout.WEST);
    card.add(right, BorderLayout.CENTER);

    wrapper.add(card, BorderLayout.CENTER);
    return wrapper;
  }

  // ── Export ────────────────────────────────────────────────────────────────

  private void exportHeatmap(SexClassifierAction.PipelineResult result) {
    if (result.classification == null || !result.classification.isSuccess()) {
      JOptionPane.showMessageDialog(mainPanel,
          "No classification result to export.",
          "Export Heatmap", JOptionPane.WARNING_MESSAGE);
      return;
    }

    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle("Select base directory for export");
    if (chooser.showSaveDialog(mainPanel) != JFileChooser.APPROVE_OPTION) return;

    File baseDir = chooser.getSelectedFile();
    String displayLbl =
        SexClassifierAction.toDisplayLabel(result.classification.finalLabel);
    String patientId =
        (result.patientId != null && !result.patientId.isBlank())
        ? result.patientId : "unknown";

    // <base>/<classification>/<patient_id>/
    File outDir = new File(baseDir, displayLbl + File.separator + patientId);
    outDir.mkdirs();

    int count = 0;
    for (SexClassifier.ImageResult r : result.classification.perImage) {
      if (r.heatmap != null && r.heatmap.exists()) {
        try {
          Files.copy(r.heatmap.toPath(),
              new File(outDir, r.heatmap.getName()).toPath(),
              StandardCopyOption.REPLACE_EXISTING);
          count++;
        } catch (Exception e) {
          LOGGER.warn("Could not copy heatmap {}: {}", r.heatmap.getName(), e.getMessage());
        }
      }
    }

    if (count > 0) {
      JOptionPane.showMessageDialog(mainPanel,
          count + " heatmap(s) exported to:\n" + outDir.getAbsolutePath(),
          "Export Heatmap", JOptionPane.INFORMATION_MESSAGE);
    } else {
      JOptionPane.showMessageDialog(mainPanel,
          "No heatmaps available.\n"
          + "Heatmaps are only generated when grad-cam is installed.",
          "Export Heatmap", JOptionPane.WARNING_MESSAGE);
    }
  }

  // ── Viewer integration ────────────────────────────────────────────────────

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

  private static File centerPivot(List<File> images) {
    if (images == null || images.isEmpty()) return null;
    int idx = Math.min(PivotDetector.WINDOW_RADIUS, images.size() - 1);
    return images.get(idx);
  }

  private static ImageIcon makeThumbnail(File imageFile, int maxW, int maxH) {
    try {
      BufferedImage img = ImageIO.read(imageFile);
      if (img == null) return null;
      double scale = Math.min((double) maxW / img.getWidth(),
                              (double) maxH / img.getHeight());
      int tw = Math.max(1, (int) (img.getWidth()  * scale));
      int th = Math.max(1, (int) (img.getHeight() * scale));
      Image scaled = img.getScaledInstance(tw, th, Image.SCALE_SMOOTH);
      return new ImageIcon(scaled);
    } catch (Exception e) {
      LOGGER.warn("Cannot make thumbnail: {}", e.getMessage());
      return null;
    }
  }

  void setStatus(String text, Color color) {
    statusLabel.setText(text);
    statusLabel.setForeground(color);
    statusLabel.repaint();
    mainPanel.revalidate();
    mainPanel.repaint();
  }
}
