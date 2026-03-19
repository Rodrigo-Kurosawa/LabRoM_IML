/**
 * Template de plugin Weasis – Toolbar
 * 
 * A toolbar é a barra de botões que aparece no topo do viewer.
 */
package org.weasis.template.basic;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import org.weasis.core.ui.util.WtoolBar;

/**
 * Toolbar (barra de ferramentas) do plugin de template.
 * 
 * Aparece no topo do viewer 2D com botões e controles.
 */
public class TemplatePluginToolBar extends WtoolBar {
  private static final long serialVersionUID = 1L;

  public static final String TOOLBAR_NAME = "Template Toolbar";

  /**
   * Construtor. Monta os controles da toolbar.
   */
  public TemplatePluginToolBar() {
    super(TOOLBAR_NAME, 30);

    // Exemplo 1: Label
    JLabel label = new JLabel("Opção:");
    add(label);

    // Exemplo 2: ComboBox
    JComboBox<String> combo = new JComboBox<>(new String[]{"Opção 1", "Opção 2", "Opção 3"});
    add(combo);

    addSeparator();

    // Exemplo 3: Botão de ação
    JButton botaoExecutar = new JButton("Executar");
    botaoExecutar.addActionListener(e -> {
      String opcao = (String) combo.getSelectedItem();
      System.out.println("Botão clicado com opção: " + opcao);
    });
    add(botaoExecutar);
  }
}
