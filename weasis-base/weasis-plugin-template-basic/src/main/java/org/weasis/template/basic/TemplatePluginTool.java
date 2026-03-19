/**
 * Template de plugin Weasis – Painel Lateral (Tool)
 * 
 * Este arquivo define o painel que aparece na lateral do Weasis.
 * Ele estende PluginTool que é uma classe base do Weasis para criar
 * painéis dockáveis na interface.
 */
package org.weasis.template.basic;

import bibliothek.gui.dock.common.CLocation;
import java.awt.Component;
import java.awt.Font;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import org.weasis.core.ui.docking.PluginTool;

/**
 * Painel lateral (Tool) do plugin de template.
 * 
 * Aparece como aba dockável na lateral do viewer 2D.
 */
public class TemplatePluginTool extends PluginTool {
  private static final long serialVersionUID = 1L;

  // Identificadores obrigatórios
  public static final String BUTTON_NAME = "Template Tool";

  /**
   * Construtor padrão.
   * 
   * @param type tipo de ferramenta (TOOL)
   */
  public TemplatePluginTool(Type type) {
    super(BUTTON_NAME, type, 150);  // 150 = largura padrão em pixels
    
    // Montar UI do painel
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    
    // Exemplo: adicionar um label
    JLabel titulo = new JLabel("Template Básico");
    titulo.setFont(titulo.getFont().deriveFont(Font.BOLD, 14f));
    add(titulo);
    
    add(new JLabel("Este é um painel de exemplo."));
    
    // Exemplo: adicionar um botão
    JButton botaoTeste = new JButton("Executar Ação");
    botaoTeste.addActionListener(e -> {
      System.out.println("Botão clicado!");
    });
    add(botaoTeste);
  }

  /**
   * Chamado quando o painel está fechando.
   */
  @Override
  public void closeDockable() {
    System.out.println("Template Tool fechando...");
  }

  @Override
  protected void changeToolWindowAnchor(CLocation clocation) {
    // Sem comportamento especial ao mover o painel
  }
}
