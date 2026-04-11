/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 *
 * Central action class that holds the actual classification logic.
 * Both the toolbar button and the side-panel button call into this class,
 * keeping the business logic in a single location.
 *
 * NOTE: Replace the placeholder logic inside performClassification() with
 * the real model inference call (e.g. calling your Python pipeline via
 * ProcessBuilder, or embedding an ONNX/DJL Java model).
 */
package org.weasis.sex.classifier;

import java.awt.Component;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class that performs sex classification and reports the result.
 *
 * <p>The heavy work is done off the EDT via {@link SwingWorker} so the UI
 * never freezes during processing.
 */
public final class SexClassifierAction {

  private static final Logger LOGGER = LoggerFactory.getLogger(SexClassifierAction.class);

  private SexClassifierAction() {
    // utility class
  }

  // ---------------------------------------------------------------------------
  // Synchronous entry-point (used by the top toolbar)
  // ---------------------------------------------------------------------------

  /**
   * Runs classification and shows a dialog with the result.
   *
   * @param invoker the button that triggered the action (used for dialog placement)
   */
  public static void runClassification(Component invoker) {
    runClassificationAsync(
        invoker,
        result ->
            SwingUtilities.invokeLater(
                () ->
                    javax.swing.JOptionPane.showMessageDialog(
                        invoker,
                        "Classification result: " + result, // NON-NLS
                        "Sex Classification", // NON-NLS
                        javax.swing.JOptionPane.INFORMATION_MESSAGE)));
  }

  // ---------------------------------------------------------------------------
  // Async entry-point (used by the side-panel)
  // ---------------------------------------------------------------------------

  /**
   * Runs classification off the EDT and delivers the string result to the
   * supplied {@code callback} on the EDT.
   *
   * @param invoker  component used for context (event manager access)
   * @param callback receives the classification result string
   */
  public static void runClassificationAsync(Component invoker, Consumer<String> callback) {
    new SwingWorker<String, Void>() {

      @Override
      protected String doInBackground() {
        return performClassification();
      }

      @Override
      protected void done() {
        try {
          String result = get();
          SwingUtilities.invokeLater(() -> callback.accept(result));
        } catch (Exception ex) {
          LOGGER.error("Sex classification failed", ex);
          SwingUtilities.invokeLater(() -> callback.accept("Error – see log")); // NON-NLS
        }
      }
    }.execute();
  }

  // ---------------------------------------------------------------------------
  // Classification logic – REPLACE THIS with the real model call
  // ---------------------------------------------------------------------------

  /**
   * Placeholder – put the real inference logic here.
   *
   * <p>Suggestions:
   * <ul>
   *   <li>Call a Python subprocess via {@link ProcessBuilder} passing the DICOM path</li>
   *   <li>Use Deep Java Library (DJL) to load an ONNX/TorchScript model</li>
   *   <li>Read DICOM tags from the currently selected series via EventManager</li>
   * </ul>
   *
   * @return a human-readable string with the result (e.g. "Male", "Female", "Undetermined")
   */
  private static String performClassification() {
    // TODO: integrate with the real classification model
    LOGGER.info("Sex classification requested (stub implementation)");

    // Simulated delay to demonstrate async behaviour
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    return "Not implemented yet"; // NON-NLS – replace with real result
  }
}
