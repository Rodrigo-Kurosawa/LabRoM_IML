/**
 * Template de plugin Weasis – Factory da Tool
 */
package org.weasis.template.basic;

import org.osgi.service.component.annotations.Component;
import org.weasis.core.api.gui.InsertableFactory;

/**
 * Factory que cria instâncias de TemplatePluginTool.
 */
@Component(
  service = InsertableFactory.class,
  property = {
    "org.weasis.base.viewer2d.View2dContainer=true"
  }
)
public class TemplatePluginToolFactory implements InsertableFactory {

  @Override
  public int getComponentPriority() {
    return 40;
  }

  @Override
  public String getUIName() {
    return TemplatePluginTool.BUTTON_NAME;
  }

  @Override
  public TemplatePluginTool createInstance(Object... args) {
    return new TemplatePluginTool(InsertableFactory.Type.TOOL);
  }

  @Override
  public void dispose(Object option) {
    // Cleanup
  }

  @Override
  public boolean isComponentCreatedByThisFactory(InsertableFactory.Insertable component) {
    return component instanceof TemplatePluginTool;
  }
}
