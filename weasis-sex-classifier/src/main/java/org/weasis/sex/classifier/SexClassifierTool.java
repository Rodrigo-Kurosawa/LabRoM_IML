/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 *
 * Side-panel Tool that appears in the dockable panel area on the left/right
 * of the viewer (same area as ImageTool, DisplayTool, SegmentationTool, etc.).
 *
 * It shows a "Sex Classification" panel with a button to trigger the analysis
 * and a label to display the result.
 */
package org.weasis.sex.classifier;

import bibliothek.gui.dock.common.CLocation;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.ui.docking.PluginTool;

/**
 * Dockable side-panel for the Sex Classification plugin.
 *
 * <p>Extends {@link PluginTool} – all built-in tool panels (ImageTool,
 * DisplayTool, SegmentationTool) follow this same pattern.
 */
public class SexClassifierTool extends PluginTool {

  /** Label shown on the docking tab / button. */
  public static final String BUTTON_NAME = "Sex Classification"; // NON-NLS

  private final JScrollPane rootPane = new JScrollPane();
  private final JLabel resultLabel = new JLabel("–", SwingConstants.CENTER);

  public SexClassifierTool() {
    // Position index 25: appears between ImageTool(20) and SegmentationTool(30)
    super(BUTTON_NAME, Insertable.Type.TOOL, 25);
    setDockableWidth(290);
    init();
  }

  private void init() {
    JPanel contentPanel = new JPanel();
    contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
    contentPanel.setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));

    // --- Header label ---
    JLabel headerLabel = new JLabel("Sex Classification", SwingConstants.CENTER);
    headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 13f));
    headerLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
    headerLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
    contentPanel.add(headerLabel);

    // --- Result display ---
    resultLabel.setFont(resultLabel.getFont().deriveFont(Font.PLAIN, 12f));
    resultLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
    resultLabel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Result"), // NON-NLS
            BorderFactory.createEmptyBorder(4, 4, 4, 4)));
    contentPanel.add(resultLabel);

    // --- Classify button ---
    JButton classifyButton = new JButton("Classify"); // NON-NLS
    classifyButton.setAlignmentX(Component.CENTER_ALIGNMENT);
    classifyButton.setToolTipText("Run sex classification on the loaded DICOM series"); // NON-NLS
    classifyButton.addActionListener(
        e -> {
          resultLabel.setText("Running…"); // NON-NLS
          SexClassifierAction.runClassificationAsync(
              classifyButton,
              result -> resultLabel.setText(result));
        });
    contentPanel.add(classifyButton);

    rootPane.setBorder(BorderFactory.createEmptyBorder()); // remove default border line
    rootPane.setViewportView(contentPanel);
  }

  @Override
  public Component getToolComponent() {
    return getToolComponentFromJScrollPane(rootPane);
  }

  @Override
  protected void changeToolWindowAnchor(CLocation clocation) {
    // No layout change required when the panel is moved
  }
}
