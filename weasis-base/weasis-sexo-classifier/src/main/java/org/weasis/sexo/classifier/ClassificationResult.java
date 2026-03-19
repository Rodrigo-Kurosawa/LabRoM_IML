/*
 * Resultado de uma classificação de sexo.
 */
package org.weasis.sexo.classifier;

/**
 * Contém o resultado de uma classificação de sexo por imagem óssea (crânio/pelve),
 * incluindo sexo, confiança, número de imagens processadas e path do heatmap.
 */
public class ClassificationResult {
    private final boolean erro;
    private String sexo;           // "Masculino" ou "Feminino"
    private double confianca;      // 0.0 to 1.0
    private int numImagens;        // número de imagens processadas
    private String heatmapPath;    // caminho para arquivo .png do heatmap
    private String mensagemErro;   // mensagem de erro se erro=true

    /**
     * Cria resultado de sucesso.
     */
    public ClassificationResult(String sexo, double confianca, int numImagens, String heatmapPath) {
        this.erro = false;
        this.sexo = sexo;
        this.confianca = confianca;
        this.numImagens = numImagens;
        this.heatmapPath = heatmapPath;
    }

    /**
     * Cria resultado de erro.
     */
    public ClassificationResult(String mensagemErro) {
        this.erro = true;
        this.mensagemErro = mensagemErro;
    }

    public boolean isErro() {
        return erro;
    }

    public String getSexo() {
        return sexo;
    }

    public double getConfianca() {
        return confianca;
    }

    public int getNumImagens() {
        return numImagens;
    }

    public String getHeatmapPath() {
        return heatmapPath;
    }

    public String getErro() {
        return mensagemErro;
    }

    @Override
    public String toString() {
        if (erro) {
            return "ClassificationResult [ERRO: " + mensagemErro + "]";
        }
        return "ClassificationResult [sexo=" + sexo + ", confianca=" + confianca
                + ", numImagens=" + numImagens + ", heatmap=" + heatmapPath + "]";
    }
}
