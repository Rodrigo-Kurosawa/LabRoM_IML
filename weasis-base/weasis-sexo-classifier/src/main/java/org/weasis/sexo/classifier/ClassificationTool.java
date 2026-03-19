/*
 * Módulo de classificação de sexo por imagens ósseas (crânio / pelve).
 */
package org.weasis.sexo.classifier;

import bibliothek.gui.dock.common.CLocation;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.weasis.core.ui.docking.PluginTool;

/**
 * Painel lateral do Weasis que exibe:
 * <ol>
 *   <li>Caixa de resultado (MASCULINO / FEMININO + confiança) no topo
 *   <li>Duas visualizações lado a lado (horizontal split):
 *       <ul>
 *         <li>Esquerda: imagem original sendo visualizada
 *         <li>Direita: mapa de calor Grad-CAM
 *       </ul>
 * </ol>
 *
 * <p>O painel se registra no {@link ClassificationManager} e é atualizado
 * automaticamente sempre que uma nova classificação é publicada.
 */
public class ClassificationTool extends PluginTool {

    public static final String BUTTON_NAME = "Classificar Sexo";

    // ── UI components ────────────────────────────────────────────────────────
    private final JScrollPane rootPane   = new JScrollPane();
    private final JPanel      contentPanel;

    /** Caixa de resultado no topo */
    private final JPanel  resultBox      = new JPanel(new BorderLayout(0, 4));
    private final JLabel  sexoLabel      = new JLabel("—", SwingConstants.CENTER);
    private final JLabel  confiancaLabel = new JLabel("", SwingConstants.CENTER);

    /** Visualização original (esquerda) */
    private final JLabel originalLabel  = new JLabel("Original", SwingConstants.CENTER);
    
    /** Área do heatmap (direita) */
    private final JLabel heatmapLabel   = new JLabel("Heatmap", SwingConstants.CENTER);
    
    /** Split pane horizontal (original vs heatmap) */
    private final JSplitPane dualViewPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

    /** Listener registrado no manager (guardado para poder desregistrar) */
    private final Consumer<ClassificationResult> resultListener;

    // ────────────────────────────────────────────────────────────────────────

    public ClassificationTool(Type type) {
        super(BUTTON_NAME, type, 150);
        setDockableWidth(300);

        contentPanel = buildContentPanel();
        rootPane.setBorder(BorderFactory.createEmptyBorder());
        rootPane.setViewportView(contentPanel);

        resultListener = result -> SwingUtilities.invokeLater(() -> applyResult(result));
        ClassificationManager.getInstance().addListener(resultListener);
    }

    @Override
    public Component getToolComponent() {
        return getToolComponentFromJScrollPane(rootPane);
    }

    @Override
    protected void changeToolWindowAnchor(CLocation clocation) {
        // sem comportamento especial ao mover o painel
    }

    // ── Construção do painel ─────────────────────────────────────────────────

    private JPanel buildContentPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // ── 1. Caixa de resultado ────────────────────────────────────────────
        resultBox.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(180, 180, 180), 1, true),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        resultBox.setBackground(new Color(245, 245, 245));
        resultBox.setOpaque(true);
        resultBox.setAlignmentX(Component.CENTER_ALIGNMENT);
        resultBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));

        JLabel tituloLabel = new JLabel("Resultado da Classificação", SwingConstants.CENTER);
        tituloLabel.setFont(tituloLabel.getFont().deriveFont(Font.BOLD, 12f));
        tituloLabel.setForeground(new Color(80, 80, 80));

        sexoLabel.setFont(sexoLabel.getFont().deriveFont(Font.BOLD, 22f));
        sexoLabel.setForeground(new Color(50, 50, 50));

        confiancaLabel.setFont(confiancaLabel.getFont().deriveFont(Font.PLAIN, 11f));
        confiancaLabel.setForeground(new Color(100, 100, 100));

        JPanel textStack = new JPanel();
        textStack.setLayout(new BoxLayout(textStack, BoxLayout.Y_AXIS));
        textStack.setOpaque(false);
        sexoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        confiancaLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        textStack.add(sexoLabel);
        textStack.add(confiancaLabel);

        resultBox.add(tituloLabel,  BorderLayout.NORTH);
        resultBox.add(textStack,    BorderLayout.CENTER);

        // ── 2. Dual View: Original vs Heatmap (horizontal split) ──────────────
        dualViewPane.setOrientation(JSplitPane.HORIZONTAL_SPLIT);
        dualViewPane.setDividerLocation(0.5);  // 50/50 split inicial
        dualViewPane.setResizeWeight(0.5);     // ambos redimensionam
        dualViewPane.setContinuousLayout(true);
        
        // Lado esquerdo: original
        JPanel panelEsquerda = new JPanel(new BorderLayout());
        JLabel labelOrigem = new JLabel("Original", SwingConstants.CENTER);
        labelOrigem.setFont(labelOrigem.getFont().deriveFont(Font.BOLD, 10f));
        labelOrigem.setForeground(new Color(80, 80, 80));
        panelEsquerda.add(labelOrigem, BorderLayout.NORTH);
        
        originalLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        originalLabel.setText("<html><center><i>Abra uma imagem<br>no viewer 2D</i></center></html>");
        originalLabel.setBackground(new Color(220, 220, 220));
        originalLabel.setOpaque(true);
        panelEsquerda.add(originalLabel, BorderLayout.CENTER);
        
        // Lado direito: heatmap
        JPanel panelDireita = new JPanel(new BorderLayout());
        JLabel labelHeatmap = new JLabel("Heatmap Grad-CAM", SwingConstants.CENTER);
        labelHeatmap.setFont(labelHeatmap.getFont().deriveFont(Font.BOLD, 10f));
        labelHeatmap.setForeground(new Color(80, 80, 80));
        panelDireita.add(labelHeatmap, BorderLayout.NORTH);
        
        heatmapLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        heatmapLabel.setText("<html><center><i>Execute a classificação<br>para ver o heatmap.</i></center></html>");
        heatmapLabel.setBackground(new Color(220, 220, 220));
        heatmapLabel.setOpaque(true);
        panelDireita.add(heatmapLabel, BorderLayout.CENTER);
        
        dualViewPane.setLeftComponent(panelEsquerda);
        dualViewPane.setRightComponent(panelDireita);
        dualViewPane.setMinimumSize(new Dimension(300, 200));
        dualViewPane.setPreferredSize(new Dimension(600, 300));

        // ── 3. Layout final ──────────────────────────────────────────────────
        panel.add(resultBox);
        panel.add(Box.createVerticalStrut(8));
        panel.add(dualViewPane);

        return panel;
    }
    
    /**
     * Captura a imagem que está sendo visualizada atualmente no 2D viewer
     * e a exibe no lado esquerdo do dual view.
     */
    private void updateOriginalImage() {
        // TODO: Conectar ao ImageViewerCanvas do Weasis para capturar a imagem atual
        // Por enquanto, mantém como placeholder
        originalLabel.setText("<html><center><i>Imagem capturada</i></center></html>");
    }

    // ── Atualização com o resultado ──────────────────────────────────────────

    private void applyResult(ClassificationResult result) {
        if (result.isErro()) {
            sexoLabel.setText("ERRO");
            sexoLabel.setForeground(new Color(180, 30, 30));
            confiancaLabel.setText(result.getErro());
            heatmapLabel.setIcon(null);
            heatmapLabel.setText("<html><center><i>" + result.getErro() + "</i></center></html>");
            resultBox.setBackground(new Color(255, 230, 230));
            return;
        }

        // ── Atualiza caixa de resultado
        boolean masculino = "masculino".equalsIgnoreCase(result.getSexo());
        Color bgColor  = masculino ? new Color(220, 235, 255) : new Color(255, 220, 235);
        Color txtColor = masculino ? new Color(20, 60, 140)   : new Color(140, 20, 80);

        resultBox.setBackground(bgColor);
        sexoLabel.setText(result.getSexo().toUpperCase());
        sexoLabel.setForeground(txtColor);
        confiancaLabel.setText(String.format(
                "Confiança: %.1f%%  |  %d imagem(ns)", result.getConfianca() * 100, result.getNumImagens()));

        // ── Atualiza heatmap
        String heatmapPath = result.getHeatmapPath();
        if (heatmapPath != null && !heatmapPath.isBlank()) {
            try {
                BufferedImage img  = ImageIO.read(new File(heatmapPath));
                int panelW = Math.max(rootPane.getViewport().getWidth() - 20, 260);
                double scale = (double) panelW / img.getWidth();
                int scaledH  = (int) (img.getHeight() * scale);
                Image scaled = img.getScaledInstance(panelW, scaledH, Image.SCALE_SMOOTH);
                heatmapLabel.setIcon(new ImageIcon(scaled));
                heatmapLabel.setText(null);
            } catch (Exception e) {
                heatmapLabel.setIcon(null);
                heatmapLabel.setText("<html><center><i>Heatmap não disponível.</i></center></html>");
            }
        } else {
            heatmapLabel.setIcon(null);
            heatmapLabel.setText("<html><center><i>Heatmap não gerado.</i></center></html>");
        }

        contentPanel.revalidate();
        contentPanel.repaint();
    }

    // ── Ciclo de vida ────────────────────────────────────────────────────────

    @Override
    public void closeDockable() {
        ClassificationManager.getInstance().removeListener(resultListener);
        super.closeDockable();
    }
}
