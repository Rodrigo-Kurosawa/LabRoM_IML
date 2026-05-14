/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 *
 * Top toolbar button. Reads series from the active viewer (no file picker).
 */
package org.weasis.sex.classifier;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.dicom.viewer2d.EventManager;
import org.weasis.core.ui.util.WtoolBar;

/**
 * Top toolbar button that triggers sex classification on the current viewer
 * series.
 */
public class SexClassifierToolBar extends WtoolBar {

  private static final Logger LOGGER = LoggerFactory.getLogger(SexClassifierToolBar.class);

  public static final String NAME = "Sex Classification"; // NON-NLS

  public SexClassifierToolBar(int index) {
    super(NAME, index);

    final AnimatedSpinnerIcon spinner = new AnimatedSpinnerIcon(16);
    final JButton btn = new JButton(NAME);
    btn.setToolTipText("Classify the series currently loaded in the viewer"); // NON-NLS
    btn.addActionListener(e -> {
      List<File> files = getViewerFiles();

      if (files.isEmpty()) {
        JOptionPane.showMessageDialog(
            SwingUtilities.getWindowAncestor(btn),
            "No series loaded in the viewer.\nOpen a DICOM series first.", // NON-NLS
            NAME, JOptionPane.WARNING_MESSAGE);
        return;
      }

      boolean anyDicom = files.stream().anyMatch(DicomExtractor::isDicom);
      if (!anyDicom) {
        JOptionPane.showMessageDialog(
            SwingUtilities.getWindowAncestor(btn),
            "Unsupported format.\nLoad a DICOM Secondary Capture (SC) series.", // NON-NLS
            NAME, JOptionPane.ERROR_MESSAGE);
        return;
      }

      btn.setEnabled(false);
      btn.setText("");
      btn.setIcon(spinner);
      spinner.start(btn);
      SexClassifierTool.pushLoading();

      SexClassifierAction.runPipelineAsync(files, result -> {
        spinner.stop();
        btn.setIcon(null);
        btn.setText(NAME);
        btn.setEnabled(true);
        // Push results to the side panel — that is where they are displayed
        SexClassifierTool.pushResult(result);
        // Only surface a dialog on error (success is visible in the side panel)
        if (!result.success) {
          JOptionPane.showMessageDialog(
              SwingUtilities.getWindowAncestor(btn),
              "<html>" + result.error + "</html>", // NON-NLS
              NAME, JOptionPane.ERROR_MESSAGE);
        }
      });
    });

    add(btn);
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private List<File> getViewerFiles() {
    Set<File> files = new LinkedHashSet<>();
    try {
      ViewCanvas<?> view = EventManager.getInstance().getSelectedViewPane();
      if (view == null)
        return new ArrayList<>();
      MediaSeries series = view.getSeries();
      if (series == null)
        return new ArrayList<>();
      Iterable<?> medias = series.getMedias(null, null);
      for (Object m : medias) {
        if (m instanceof MediaElement) {
          URI uri = ((MediaElement) m).getMediaURI();
          if (uri != null && "file".equals(uri.getScheme())) {
            File f = new File(uri);
            if (f.exists())
              files.add(f);
          }
        }
      }
    } catch (Exception e) {
      LOGGER.warn("Could not read viewer files: {}", e.getMessage());
    }
    return new ArrayList<>(files);
  }

  // ── Spinning icon for the button ──────────────────────────────────────────

  private static class AnimatedSpinnerIcon implements Icon {

    private int angle = 0;
    private final int size;
    private final Timer timer;
    private Component target;

    AnimatedSpinnerIcon(int size) {
      this.size = size;
      timer = new Timer(40, ev -> {
        angle = (angle + 10) % 360;
        if (target != null) target.repaint();
      });
    }

    void start(Component c) { target = c; timer.start(); }
    void stop()             { timer.stop(); target = null; }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                          RenderingHints.VALUE_ANTIALIAS_ON);
      int r = size / 2 - 2;
      int cx = x + size / 2, cy = y + size / 2;
      g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g2.setColor(new Color(180, 180, 180, 90));
      g2.drawOval(cx - r, cy - r, r * 2, r * 2);
      g2.setColor(new Color(80, 140, 200));
      g2.drawArc(cx - r, cy - r, r * 2, r * 2, angle, 270);
      g2.dispose();
    }

    @Override public int getIconWidth()  { return size; }
    @Override public int getIconHeight() { return size; }
  }
}
