/*
 * Sex Classification Plugin – LabRoM_IML / Weasis fork
 *
 * OSGi DS component that registers the toolbar with Weasis's InsertableFactory
 * mechanism.  The property "org.weasis.dicom.viewer2d.View2dContainer=true"
 * makes Weasis add this toolbar only when the DICOM 2-D viewer is active.
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
 * OSGi DS factory that contributes {@link SexClassifierToolBar} to the
 * {@code View2dContainer} toolbar row.
 *
 * <p>The {@code property} entry below is the key Weasis uses to decide which
 * containers should host this toolbar.
 */
@org.osgi.service.component.annotations.Component(
    service = InsertableFactory.class,
    property = {
        "org.weasis.dicom.viewer2d.View2dContainer=true",
        "org.weasis.base.viewer2d.View2dContainer=true"
    })
public class SexClassifierToolBarFactory implements InsertableFactory {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(SexClassifierToolBarFactory.class);

  @Override
  public Type getType() {
    return Type.TOOLBAR;
  }

  @Override
  public Insertable createInstance(Hashtable<String, Object> properties) {
    // Index 95: sits between KeyObjectToolBar(90) and LauncherToolBar(130)
    return new SexClassifierToolBar(95);
  }

  @Override
  public boolean isComponentCreatedByThisFactory(Insertable component) {
    return component instanceof SexClassifierToolBar;
  }

  @Override
  public void dispose(Insertable bar) {
    // Nothing to clean up for a simple button toolbar
  }

  // ================================================================================
  // OSGi lifecycle
  // ================================================================================

  @Activate
  protected void activate(ComponentContext context) {
    LOGGER.info("Sex Classification toolbar activated");
  }

  @Deactivate
  protected void deactivate(ComponentContext context) {
    LOGGER.info("Sex Classification toolbar deactivated");
  }
}
