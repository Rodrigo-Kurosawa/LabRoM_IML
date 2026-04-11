/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 *
 * Toolbar button "Sex Classification" that appears at the top of the viewer,
 * next to the other tool-bar buttons, once a DICOM series is open.
 */
package org.weasis.sex.classifier;

import javax.swing.JButton;
import org.weasis.core.ui.util.WtoolBar;

/**
 * Top toolbar button for the Sex Classification feature.
 *
 * <p>This toolbar is contributed to {@code View2dContainer} via the OSGi service
 * {@link SexClassifierToolBarFactory} and is displayed in the horizontal toolbar row at the top of
 * the viewer.
 */
public class SexClassifierToolBar extends WtoolBar {

  /** Must match the name used in {@link SexClassifierToolBarFactory#isComponentCreatedByThisFactory}. */
  public static final String NAME = "Sex Classification"; // NON-NLS

  /**
   * @param index position index relative to the other toolbars (higher = further right).
   *              Using 95 places it just before LauncherToolBar (130).
   */
  public SexClassifierToolBar(int index) {
    super(NAME, index);

    final JButton classifyButton = new JButton(NAME);
    classifyButton.setToolTipText("Run sex classification on the current DICOM series"); // NON-NLS
    classifyButton.addActionListener(
        e -> SexClassifierAction.runClassification(classifyButton));

    add(classifyButton);
  }
}
