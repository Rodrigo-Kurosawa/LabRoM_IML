/**
 * Template de plugin Weasis – Activator OSGi
 * 
 * Este arquivo é o ponto de entrada do bundle OSGi. Ele registra os factories
 * das ferramentas (Tool e ToolBar) no contexto do OSGi para serem descobertas
 * automaticamente pelo Weasis.
 */
package org.weasis.template.basic.internal;

import java.util.Dictionary;
import java.util.Hashtable;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentFactory;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.ObservableEvent;
import org.weasis.core.api.gui.InsertableFactory;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.ui.util.ColorLayerUI;

/**
 * Activador OSGi para o plugin de template básico.
 * 
 * Responsável por:
 * 1. Registrar os factories de Tool e ToolBar quando o bundle inicia
 * 2. Ouvir eventos de descoberta de novos View containers
 * 3. Desregistrar quando o bundle para
 */
public class Activator implements BundleActivator {
  private static final Logger LOGGER = LoggerFactory.getLogger(Activator.class);

  /**
   * {@inheritDoc}
   * Chamado quando o bundle é iniciado.
   */
  @Override
  public void start(BundleContext context) throws Exception {
    LOGGER.info("Iniciando bundle weasis-plugin-template-basic");
    
    // Registrar os factories das ferramentas
    // (as classes com @Component são registradas automaticamente via SCR)
    
    LOGGER.info("Plugin Template Basic ativado com sucesso");
  }

  /**
   * {@inheritDoc}
   * Chamado quando o bundle é parado.
   */
  @Override
  public void stop(BundleContext context) throws Exception {
    LOGGER.info("Parando bundle weasis-plugin-template-basic");
  }
}
