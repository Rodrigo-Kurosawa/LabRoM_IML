# Template de Plugin Weasis

Template básico e limpo para criar novos plugins Weasis com **Toolbar** + **Side Panel** (Tool).

## Estrutura

```
weasis-plugin-template-basic/
├── pom.xml                                  # Configuração Maven
└── src/main/java/org/weasis/template/basic/
    ├── internal/
    │   └── Activator.java                   # Ponto de entrada OSGi
    ├── TemplatePluginTool.java              # Painel lateral (side panel)
    ├── TemplatePluginToolFactory.java       # Factory da Tool
    ├── TemplatePluginToolBar.java           # Barra de ferramentas (toolbar)
    └── TemplatePluginToolBarFactory.java    # Factory da ToolBar
```

## Como Usar

### 1. Renomear para seu projeto

Substitua `template` e `basic` pelo nome do seu plugin:

```bash
# Exemplo: meu-plugin-processamento
mv weasis-plugin-template-basic weasis-meu-plugin-processamento
find . -name "*.java" -o -name "pom.xml" | xargs sed -i 's/template.basic/meu.plugin.processamento/g'
sed -i 's/Template/MeuPlugin/g' src/main/java/**/*.java
```

### 2. Implementar sua lógica

Edite as classes para adicionar sua funcionalidade:

#### **TemplatePluginTool.java** (Painel Lateral)

```java
public TemplatePluginTool(Type type) {
    super(BUTTON_NAME, type, 150);
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    
    // TODO: Adicionar seus componentes Swing aqui
    JButton meuBotao = new JButton("Processar");
    meuBotao.addActionListener(e -> minhaLogica());
    add(meuBotao);
}

private void minhaLogica() {
    // Sua lógica aqui
}
```

#### **TemplatePluginToolBar.java** (Toolbar)

```java
public TemplatePluginToolBar() {
    super(TOOLBAR_NAME);
    
    // TODO: Adicionar seus controles aqui
    JComboBox<String> combo = new JComboBox<>(new String[]{"Opção A", "Opção B"});
    add(combo);
    
    JButton botao = new JButton("Executar");
    botao.addActionListener(e -> executar((String) combo.getSelectedItem()));
    add(botao);
}
```

### 3. Integrar ao Weasis

Adicione o módulo ao `weasis-base/pom.xml`:

```xml
<modules>
  <!-- ... outros módulos ... -->
  <module>weasis-meu-plugin-processamento</module>
</modules>
```

E ao arquivo de configuração `weasis-launcher/conf/base.json`:

```json
{
  "code": "felix.auto.start.102",
  "value": "file:${maven.localRepository}/org/weasis/base/weasis-meu-plugin-processamento/${app.version}/weasis-meu-plugin-processamento-${app.version}.jar",
  "description": "Meu Plugin de Processamento",
  "type": "A",
  "category": "FELIX_INSTALL"
}
```

### 4. Compilar e Deployar

```bash
cd ~/Documents/Iniciação_Científica/Software/LabRoM_IML

# Compilar apenas seu plugin
mvn install -DskipTests -pl weasis-base/weasis-meu-plugin-processamento -am

# Ou compilar tudo
mvn clean install -DskipTests -T 4
```

## Padrões Comuns

### Adicionar eventos / comunicação entre Tool e ToolBar

Use um padrão **Pub/Sub** similar ao `ClassificationManager`:

```java
// Manager.java
public class MeuPluginManager {
    private List<Consumer<MeuEvento>> listeners = new ArrayList<>();
    
    public void addListener(Consumer<MeuEvento> listener) {
        listeners.add(listener);
    }
    
    public void publicarEvento(MeuEvento evt) {
        listeners.forEach(l -> l.accept(evt));
    }
}

// ToolBar.java → chama manager
botao.addActionListener(e -> {
    MeuEvento evt = new MeuEvento("dados");
    MeuPluginManager.getInstance().publicarEvento(evt);
});

// Tool.java → escuta manager
MeuPluginManager.getInstance().addListener(evt -> {
    atualizarUI(evt);
});
```

### Chamar Python / ProcessBuilder

```java
ProcessBuilder pb = new ProcessBuilder("python3", "script.py", "--arg", "valor");
Map<String, String> env = pb.environment();
env.put("PYTHONIOENCODING", "utf-8");
env.put("MINHA_VAR", "/caminho/para/arquivos");

Process p = pb.start();
// ler stdout/erro, etc
```

### Usar componentes do Weasis

```java
// Obter o viewer 2D ativo
ImageViewerPlugin viewer = AppProperties.getSelectedImagePane();

// Acessar imagem sendo visualizada
org.weasis.core.ui.model.layer.LayerItem layerItem = viewer.getSelectedLayer();
```

## Referências

- [OSGi Declarative Services](https://osgi.org/specification/osgi.cmpn/8.0.0/service.component.html)
- [Weasis Plugin Architecture](https://github.com/nroduit/Weasis/wiki)
- `weasis-sexo-classifier/` — exemplo funcional completo com dual view

## Dicas

1. **Debug**: Adicione `System.out.println()` ou use `org.slf4j.Logger`
2. **Classpath**: Se tiver `ClassNotFoundException`, verifique `pom.xml` dependencies
3. **OSGi Reload**: Delete `~/.weasis/cache/` antes de relançar para forçar reload
4. **Logs**: Verifique `/tmp/weasis.log` ou console para mensagens de erro

---

**Próximos passos**: Veja `weasis-sexo-classifier/` para um exemplo de plugin completo com:
- **Dual view horizontal** (imagem original + heatmap lado a lado)
- **Seleção de pasta com JFileChooser**
- **Processamento Python com ProcessBuilder**
- **JSON parsing** para resultados
