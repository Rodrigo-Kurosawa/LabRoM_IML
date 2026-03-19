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
public class ClassificationToolBarFactory implements InsertableFactory {

  @Override
  public Insertable createInstance(Hashtable<String, Object> properties) {
    return new ClassificationToolBar();
  }

  @Override
  public void dispose(Insertable component) {
    // Nothing to dispose.
  }

  @Override
  public boolean isComponentCreatedByThisFactory(Insertable component) {
    return component instanceof ClassificationToolBar;
  }

  @Override
  public Insertable.Type getType() {
    return Insertable.Type.TOOLBAR;
  }
}
