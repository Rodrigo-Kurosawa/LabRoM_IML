/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 *
 * OSGi DS factory that contributes the SexClassifierTool side-panel to the
 * View2dContainer tool-panel list (the dockable panel on the right/left side).
 */
package org.weasis.sex.classifier;

import java.util.Hashtable;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.Insertable.Type;
import org.weasis.core.api.gui.InsertableFactory;

/**
 * OSGi DS factory that contributes {@link SexClassifierTool} to the
 * dockable side-panel area of {@code View2dContainer}.
 *
 * <p>The property {@code org.weasis.dicom.viewer2d.View2dContainer=true} is
 * the same key used by all built-in tool factories (ImageTool, DisplayTool…).
 */
@org.osgi.service.component.annotations.Component(
    service = InsertableFactory.class,
    property = {"org.weasis.dicom.viewer2d.View2dContainer=true"})
public class SexClassifierToolFactory implements InsertableFactory {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SexClassifierToolFactory.class);

  /** Singleton instance – Weasis creates only one per container. */
  private SexClassifierTool toolPane = null;

  @Override
  public Type getType() {
    return Type.TOOL;
  }

  @Override
  public Insertable createInstance(Hashtable<String, Object> properties) {
    if (toolPane == null) {
      toolPane = new SexClassifierTool();
    }
    return toolPane;
  }

  @Override
  public void dispose(Insertable tool) {
    toolPane = null;
  }

  @Override
  public boolean isComponentCreatedByThisFactory(Insertable tool) {
    return tool instanceof SexClassifierTool;
  }

  // ================================================================================
  // OSGi lifecycle
  // ================================================================================

  @Activate
  protected void activate(ComponentContext context) {
    LOGGER.info("Sex Classification side-panel activated");
  }

  @Deactivate
  protected void deactivate(ComponentContext context) {
    LOGGER.info("Sex Classification side-panel deactivated");
  }
}
