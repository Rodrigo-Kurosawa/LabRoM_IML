/*
 * Gerenciador centralizado de resultados de classificação.
 */
package org.weasis.sexo.classifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Singleton que gerencia novos resultados de classificação através de um
 * padrão pub/sub. Componentes podem se registrar como listeners para receber
 * notificações quando uma nova classificação é publicada.
 */
public class ClassificationManager {
    private static final ClassificationManager INSTANCE = new ClassificationManager();

    private final List<Consumer<ClassificationResult>> listeners = new ArrayList<>();

    private ClassificationManager() {
        // private para singleton pattern
    }

    public static ClassificationManager getInstance() {
        return INSTANCE;
    }

    /**
     * Registra um listener que será notificado sempre que um novo resultado for publicado.
     */
    public void addListener(Consumer<ClassificationResult> listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Remove um listener registrado.
     */
    public void removeListener(Consumer<ClassificationResult> listener) {
        listeners.remove(listener);
    }

    /**
     * Publica um novo resultado de classificação para todos os listeners registrados.
     */
    public void publishResult(ClassificationResult result) {
        if (result != null) {
            listeners.forEach(listener -> {
                try {
                    listener.accept(result);
                } catch (Exception e) {
                    System.err.println("Erro ao notificar listener: " + e.getMessage());
                    e.printStackTrace();
                }
            });
        }
    }

    /**
     * Limpa todos os listeners (útil para teardown).
     */
    public void clearListeners() {
        listeners.clear();
    }
}
