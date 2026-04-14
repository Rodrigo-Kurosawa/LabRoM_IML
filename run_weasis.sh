#!/bin/bash
# run_weasis.sh — Build e execução do Weasis com o plugin LabRoM/IML.
#
# Uso:
#   ./run_weasis.sh          # build completo + execução
#   ./run_weasis.sh --fast   # recompila só o plugin e reinicia
#
# Funciona em macOS, Linux e qualquer SO com bash, Java e Maven.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST_DIR="$SCRIPT_DIR/weasis-native"
BIN_DIR="$DEST_DIR/bin-dist/weasis"
BUNDLE_DIR="$BIN_DIR/bundle"
MODELS_SRC="$SCRIPT_DIR/weasis-sex-classifier/models"
MODELS_DEST="$BIN_DIR/models"

# ---------------------------------------------------------------------------
# Detectar JAVA_HOME (cross-platform)
# ---------------------------------------------------------------------------
_find_java_home() {
  # 1. Já definido e válido
  if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    return
  fi

  # 2. ~/tools/ — JDK em formato macOS (.jdk/Contents/Home)
  local found
  found=$(ls -d "$HOME/tools/"*.jdk/Contents/Home 2>/dev/null | head -n 1)
  if [ -n "$found" ] && [ -x "$found/bin/java" ]; then
    export JAVA_HOME="$found"; return
  fi

  # 3. ~/tools/ — JDK em formato Linux (diretório direto com bin/java)
  found=$(find "$HOME/tools" -maxdepth 3 -name "java" -path "*/bin/java" 2>/dev/null \
          | head -n 1 | sed 's|/bin/java$||')
  if [ -n "$found" ] && [ -x "$found/bin/java" ]; then
    export JAVA_HOME="$found"; return
  fi

  # 4. macOS: /usr/libexec/java_home
  if /usr/libexec/java_home &>/dev/null 2>&1; then
    export JAVA_HOME=$(/usr/libexec/java_home); return
  fi

  # 5. macOS: /Library/Java/JavaVirtualMachines
  found=$(ls -d /Library/Java/JavaVirtualMachines/*/Contents/Home 2>/dev/null | head -n 1)
  if [ -n "$found" ] && [ -x "$found/bin/java" ]; then
    export JAVA_HOME="$found"; return
  fi

  # 6. Linux/genérico: derivar do 'java' no PATH
  if command -v java &>/dev/null; then
    local java_bin
    java_bin=$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)
    local candidate="${java_bin%/bin/java}"
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"; return
    fi
  fi
}

_find_java_home

if [ -z "$JAVA_HOME" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "ERRO: Java não encontrado."
  echo "  Defina JAVA_HOME manualmente:  export JAVA_HOME=<caminho_do_jdk>"
  echo "  Ou instale o JDK e verifique que 'java' está no PATH."
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"
echo "Usando Java: $(java -version 2>&1 | head -n 1)  [JAVA_HOME=$JAVA_HOME]"

# ---------------------------------------------------------------------------
# Encontrar Maven (mvn) — preferir versões >= 3.8 instaladas em ~/tools
# ---------------------------------------------------------------------------
_find_mvn() {
  # ~/tools: qualquer apache-maven-*/bin/mvn
  local found
  found=$(find "$HOME/tools" -maxdepth 3 -name "mvn" -path "*/apache-maven-*/bin/mvn" \
          2>/dev/null | sort -V | tail -n 1)
  if [ -n "$found" ] && [ -x "$found" ]; then
    echo "$found"; return
  fi
  # Fallback: mvn do PATH
  command -v mvn 2>/dev/null || true
}

MVN=$(_find_mvn)
if [ -z "$MVN" ] || [ ! -x "$MVN" ]; then
  echo "ERRO: Maven (mvn) não encontrado."
  echo "  Instale o Maven >= 3.8 e certifique-se de que está no PATH,"
  echo "  ou coloque-o em ~/tools/apache-maven-<versão>/."
  exit 1
fi
echo "Usando Maven: $("$MVN" --version 2>&1 | head -n 1)"

# ---------------------------------------------------------------------------
# Copiar modelos (.pt) para bin-dist/weasis/models/
# (o plugin Java descobre os modelos relativamente ao JAR nesta pasta)
# ---------------------------------------------------------------------------
_copy_models() {
  if [ ! -d "$MODELS_SRC" ]; then
    echo "AVISO: pasta models/ não encontrada em: $MODELS_SRC"
    return
  fi
  if [ ! -d "$BIN_DIR" ]; then
    return  # bin-dist ainda não existe; será copiado após o build
  fi
  mkdir -p "$MODELS_DEST"
  local count=0
  while IFS= read -r -d '' pt; do
    cp -f "$pt" "$MODELS_DEST/"
    count=$((count + 1))
  done < <(find "$MODELS_SRC" -name "*.pt" -print0)
  [ "$count" -gt 0 ] && echo "Modelos copiados para $MODELS_DEST  ($count arquivo(s))"
}

# ---------------------------------------------------------------------------
# Criar/verificar venv Python em ~/.weasis/labrom-env/
# O plugin procura automaticamente este caminho; nenhum caminho hardcoded no Java.
# ---------------------------------------------------------------------------
_setup_python() {
  local venv_dir="$HOME/.weasis/labrom-env"

  if [ -x "$venv_dir/bin/python3" ]; then
    echo "Venv Python OK: $venv_dir"
    return
  fi

  # Procura Python 3.9+
  local python_cmd=""
  for c in python3 python3.12 python3.11 python3.10 python3.9 python; do
    if command -v "$c" &>/dev/null; then
      local ver
      ver=$("$c" -c \
        "import sys; print(sys.version_info.major*10+sys.version_info.minor)" 2>/dev/null)
      if [ "${ver:-0}" -ge 39 ]; then
        python_cmd="$c"
        break
      fi
    fi
  done

  if [ -z "$python_cmd" ]; then
    echo "AVISO: Python 3.9+ não encontrado — o plugin de classificação não funcionará."
    echo "       Instale Python 3.9+ e execute este script novamente."
    return
  fi

  echo "Criando ambiente Python em $venv_dir (pode levar alguns minutos)..."
  mkdir -p "$(dirname "$venv_dir")"
  "$python_cmd" -m venv "$venv_dir" --clear
  "$venv_dir/bin/pip" install --upgrade pip --quiet
  "$venv_dir/bin/pip" install \
    ultralytics torch torchvision opencv-python pydicom pillow grad-cam --quiet
  echo "Ambiente Python configurado com sucesso em: $venv_dir"
}

# ---------------------------------------------------------------------------
# Modo --fast: recompila só o plugin e reinicia o Weasis
# ---------------------------------------------------------------------------
if [[ "$1" == "--fast" ]]; then
  echo "[FAST] Compilando plugin weasis-sex-classifier..."
  cd "$SCRIPT_DIR"
  "$MVN" install -pl weasis-sex-classifier -DskipTests -o -q 2>&1 \
    | grep -v "WARNING" || \
  "$MVN" install -pl weasis-sex-classifier -am -DskipTests -q 2>&1 \
    | grep -v "WARNING"

  JAR=$(ls "$SCRIPT_DIR/weasis-sex-classifier/target/weasis-sex-classifier-"*.jar \
        2>/dev/null | head -n 1)
  if [ -z "$JAR" ]; then
    echo "ERRO: JAR não encontrado após compilação."
    exit 1
  fi

  if [ ! -d "$BUNDLE_DIR" ]; then
    echo "ERRO: $BUNDLE_DIR não encontrado."
    echo "      Execute ./run_weasis.sh sem --fast pelo menos uma vez."
    exit 1
  fi

  cp "$JAR" "$BUNDLE_DIR/"
  echo "[FAST] Plugin injetado: $(basename "$JAR")"

  _copy_models
  _setup_python

  rm -rf ~/.weasis/cache
  echo "[FAST] Cache OSGi limpo. Iniciando Weasis..."
  cd "$BIN_DIR"
  java -cp "weasis-launcher.jar:felix.jar" org.weasis.launcher.AppLauncher
  exit 0
fi

# ---------------------------------------------------------------------------
# Build completo
# ---------------------------------------------------------------------------
echo "Iniciando build do Weasis..."

# Mata instâncias anteriores
pkill -f "weasis-launcher" 2>/dev/null && echo "Processo Weasis anterior encerrado." || true
sleep 1

cd "$SCRIPT_DIR"
"$MVN" clean install -DskipTests

echo "Build concluído. Empacotando distribuição..."

cd weasis-distributions
"$MVN" package -DskipTests

ZIP_FILE=$(find . -name "weasis-native*.zip" | head -n 1)
if [ -z "$ZIP_FILE" ]; then
  echo "ERRO: ZIP do weasis-native não encontrado!"
  exit 1
fi
echo "Encontrado: $ZIP_FILE"

rm -rf "$DEST_DIR"
mkdir -p "$DEST_DIR"
unzip "$ZIP_FILE" -d "$DEST_DIR"
echo "Extraído para $DEST_DIR"

# Injetar plugins customizados
echo "Injetando plugins customizados..."
for PLUGIN_JAR in "$SCRIPT_DIR/weasis-sex-classifier/target/"*.jar; do
  [ -f "$PLUGIN_JAR" ] && cp "$PLUGIN_JAR" "$BUNDLE_DIR/" \
    && echo "  Injetado: $(basename "$PLUGIN_JAR")"
done

# Copiar modelos e configurar Python
_copy_models
_setup_python

if [ ! -d "$BIN_DIR" ]; then
  echo "ERRO: $BIN_DIR não encontrado após extração."
  exit 1
fi

echo "Iniciando Weasis..."
cd "$BIN_DIR"
java -cp "weasis-launcher.jar:felix.jar" org.weasis.launcher.AppLauncher
