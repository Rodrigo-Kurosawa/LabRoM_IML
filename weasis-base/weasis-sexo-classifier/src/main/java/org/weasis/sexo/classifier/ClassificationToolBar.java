package org.weasis.sexo.classifier;

import javax.swing.JButton;
import org.weasis.core.ui.util.WtoolBar;

public class ClassificationToolBar extends WtoolBar {
  private static final long serialVersionUID = 1L;

  public static final String TOOLBAR_NAME = "Classificacao Sexo";

  public ClassificationToolBar() {
    super(TOOLBAR_NAME, 35);

    JButton classifyButton = new JButton("Classificar Sexo");
    classifyButton.setFocusable(false);
    add(classifyButton);
  }
}
