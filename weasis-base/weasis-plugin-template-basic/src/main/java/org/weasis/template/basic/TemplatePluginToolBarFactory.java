/**
 * Template de plugin Weasis – Factory da ToolBar
 */
package org.weasis.template.basic;

import org.osgi.service.component.annotations.Component;
import org.weasis.core.api.gui.InsertableFactory;

/**
 * Factory que cria instâncias de TemplatePluginToolBar.
 */
@Component(
  service = InsertableFactory.class,
  property = {
    "org.weasis.base.viewer2d.View2dContainer=true"
  }
)
public class TemplatePluginToolBarFactory implements InsertableFactory {

  @Override
  public int getComponentPriority() {
    return 50;
  }

  @Override
  public String getUIName() {
    return TemplatePluginToolBar.TOOLBAR_NAME;
  }

  @Override
  public TemplatePluginToolBar createInstance(Object... args) {
    return new TemplatePluginToolBar();
  }

  @Override
  public void dispose(Object option) {
    // Cleanup
  }

  @Override
  public boolean isComponentCreatedByThisFactory(InsertableFactory.Insertable component) {
    return component instanceof TemplatePluginToolBar;
  }
}
