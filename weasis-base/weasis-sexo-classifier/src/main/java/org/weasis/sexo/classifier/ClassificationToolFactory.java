package org.weasis.sexo.classifier;

import java.util.Hashtable;
import org.osgi.service.component.annotations.Component;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.InsertableFactory;

@Component(
    service = InsertableFactory.class,
    property = {
      "org.weasis.base.viewer2d.View2dContainer=true",
      "org.weasis.dicom.viewer2d.View2dContainer=true"
    })
public class ClassificationToolFactory implements InsertableFactory {

  @Override
  public Insertable createInstance(Hashtable<String, Object> properties) {
    return new ClassificationTool(Insertable.Type.TOOL);
  }

  @Override
  public void dispose(Insertable component) {
    // Nothing to dispose.
  }

  @Override
  public boolean isComponentCreatedByThisFactory(Insertable component) {
    return component instanceof ClassificationTool;
  }

  @Override
  public Insertable.Type getType() {
    return Insertable.Type.TOOL;
  }
}
