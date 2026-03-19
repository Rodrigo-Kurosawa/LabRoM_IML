/**
 * Módulo de classificação de sexo por imagens ósseas (crânio / pelve).
 */
package org.weasis.sexo.classifier.internal;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Activador OSGi para o plugin de classificação de sexo.
 */
public class Activator implements BundleActivator {
  private static final Logger LOGGER = LoggerFactory.getLogger(Activator.class);

  @Override
  public void start(BundleContext context) throws Exception {
    LOGGER.info("Iniciando bundle weasis-sexo-classifier");
  }

  @Override
  public void stop(BundleContext context) throws Exception {
    LOGGER.info("Parando bundle weasis-sexo-classifier");
  }
}
