#!/bin/bash

set -e  # para parar se der erro

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
DEST_DIR="../weasis-native"

rm -rf "$DEST_DIR"
mkdir -p "$DEST_DIR"

# 5. Unzip
unzip "$ZIP_FILE" -d "$DEST_DIR"

echo "Extraído para $DEST_DIR"

# 6. Entrar na pasta bin 
BIN_DIR="$DEST_DIR/bin-dist/weasis"

if [ ! -d "$BIN_DIR" ]; then
  echo "Pasta esperada não encontrada: $BIN_DIR"
  exit 1
fi

cd "$BIN_DIR"

echo "Rodando Weasis..."

# 7. Executar Weasis
java -cp "weasis-launcher.jar:felix.jar" org.weasis.launcher.AppLauncher