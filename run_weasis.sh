#!/bin/bash

set -e  # para parar se der erro

# ---------------------------------------------------------------------------
# Detectar JAVA_HOME automaticamente
# ---------------------------------------------------------------------------
if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  # 1. Tentar ~/tools/jdk-*.jdk (localização do JDK do usuário)
  FOUND_JDK=$(ls -d "$HOME/tools/"*.jdk/Contents/Home 2>/dev/null | head -n 1)
  if [ -n "$FOUND_JDK" ] && [ -x "$FOUND_JDK/bin/java" ]; then
    export JAVA_HOME="$FOUND_JDK"
  # 2. Tentar /usr/libexec/java_home (macOS padrão)
  elif /usr/libexec/java_home &>/dev/null; then
    export JAVA_HOME=$(/usr/libexec/java_home)
  # 3. Tentar /Library/Java/JavaVirtualMachines
  else
    FOUND_JDK=$(ls -d /Library/Java/JavaVirtualMachines/*/Contents/Home 2>/dev/null | head -n 1)
    [ -n "$FOUND_JDK" ] && export JAVA_HOME="$FOUND_JDK"
  fi
fi

if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "ERRO: JAVA_HOME não encontrado. Defina manualmente: export JAVA_HOME=<caminho do JDK>"
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"
echo "Usando JAVA_HOME: $JAVA_HOME"
echo "Java: $(java -version 2>&1 | head -n 1)"

# ---------------------------------------------------------------------------
# Modo: --fast  →  só compila o plugin e injeta no bundle já extraído
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST_DIR="$SCRIPT_DIR/../weasis-native"
BIN_DIR="$DEST_DIR/bin-dist/weasis"
BUNDLE_DIR="$BIN_DIR/bundle"

if [[ "$1" == "--fast" ]]; then
  echo "[FAST MODE] Compilando apenas o plugin weasis-sex-classifier..."
  cd "$SCRIPT_DIR"
  mvn install -pl weasis-sex-classifier -am -DskipTests -q
  JAR=$(ls "$SCRIPT_DIR/weasis-sex-classifier/target/weasis-sex-classifier-"*.jar 2>/dev/null | head -n 1)
  if [ -z "$JAR" ]; then
    echo "ERRO: JAR do plugin não encontrado após a compilação."
    exit 1
  fi
  if [ ! -d "$BUNDLE_DIR" ]; then
    echo "ERRO: Bundle do Weasis não encontrado em $BUNDLE_DIR"
    echo "      Execute ./run_weasis.sh sem --fast pelo menos uma vez para gerar o bundle."
    exit 1
  fi
  cp "$JAR" "$BUNDLE_DIR/"
  echo "[FAST MODE] Plugin injetado em $BUNDLE_DIR"
  echo "[FAST MODE] Iniciando Weasis..."
  cd "$BIN_DIR"
  java -cp "weasis-launcher.jar:felix.jar" org.weasis.launcher.AppLauncher
  exit 0
fi

echo "Iniciando build do Weasis..."

# 1. Build do projeto
mvn clean install -DskipTests

echo "Build concluído"

# 2. Ir para weasis-distributions
cd weasis-distributions

mvn package -DskipTests

# 3. Verificar se o zip existe
ZIP_FILE=$(find . -name "weasis-native*.zip" | head -n 1)

if [ -z "$ZIP_FILE" ]; then
  echo "ZIP do weasis-native não encontrado!"
  exit 1
fi

echo "Encontrado: $ZIP_FILE"

# 4. Criar pasta destino
rm -rf "$DEST_DIR"
mkdir -p "$DEST_DIR"

# 5. Unzip
unzip "$ZIP_FILE" -d "$DEST_DIR"

echo "Extraído para $DEST_DIR"

# 6. Injetar plugins customizados
echo "Injetando plugins customizados..."
for PLUGIN_JAR in "$SCRIPT_DIR/weasis-sex-classifier/target/"*.jar; do
  [ -f "$PLUGIN_JAR" ] && cp "$PLUGIN_JAR" "$BUNDLE_DIR/" && echo "  Injetado: $(basename "$PLUGIN_JAR")"
done

# 7. Entrar na pasta bin
if [ ! -d "$BIN_DIR" ]; then
  echo "Pasta esperada não encontrada: $BIN_DIR"
  exit 1
fi

cd "$BIN_DIR"

echo "Rodando Weasis..."

# 8. Executar Weasis
java -cp "weasis-launcher.jar:felix.jar" org.weasis.launcher.AppLauncher