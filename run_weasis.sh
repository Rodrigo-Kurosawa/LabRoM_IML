#!/bin/bash
# ---------------------------------------------------------------------------
# run_weasis.sh — Inicia o Weasis a partir da distribuição compilada no projeto
# Uso: ./run_weasis.sh
# ---------------------------------------------------------------------------

# Diretório onde este script está localizado (funciona de qualquer lugar)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

# ── Localizar Java ──────────────────────────────────────────────────────────
# Detecta a localização real do executável java e deriva JAVA_HOME a partir dele.
# Isso ignora qualquer JAVA_HOME mal configurado no ambiente.
if command -v java &>/dev/null; then
  _java_bin="$(command -v java)"
  # Deriva JAVA_HOME removendo /bin/java do final
  _detected="${_java_bin%/bin/java}"
  if [ "$_detected" != "$_java_bin" ]; then
    JAVA_HOME="$_detected"
  elif [ -n "$JAVA_HOME" ]; then
    # JAVA_HOME está setado mas java não estava em .../bin/java — usa como está
    : 
  elif /usr/libexec/java_home &>/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home)"
  else
    echo "❌ Não foi possível determinar JAVA_HOME. Defina manualmente."
    exit 1
  fi
elif /usr/libexec/java_home &>/dev/null 2>&1; then
  JAVA_HOME="$(/usr/libexec/java_home)"
else
  echo "❌ Java não encontrado. Instale o JDK ou defina JAVA_HOME."
  exit 1
fi

# Normaliza: remove /bin caso JAVA_HOME venha com ele por engano
JAVA_HOME="${JAVA_HOME%/bin}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

echo "☕ Java: $JAVA_HOME"

# ── Localizar o ZIP de distribuição ────────────────────────────────────────
DIST_ZIP="$SCRIPT_DIR/weasis-distributions/target/native-dist/weasis-native.zip"

if [ ! -f "$DIST_ZIP" ]; then
  echo "❌ Distribuição não encontrada: $DIST_ZIP"
  echo "   Execute 'mvn clean package -P native' na raiz do projeto primeiro."
  exit 1
fi

WEASIS_HOME="/tmp/weasis_dist"

echo "🗑️  Limpando cache OSGi..."
rm -rf ~/.weasis/cache

echo "📦 Extraindo distribuição..."
rm -rf "$WEASIS_HOME"
unzip -q "$DIST_ZIP" -d "$WEASIS_HOME"

echo "🚀 Iniciando Weasis..."
echo "Pressione Ctrl+C para finalizar"
echo ""

cd "$WEASIS_HOME/bin-dist/weasis"
CLASSPATH="weasis-launcher.jar:felix.jar:$(find bundle -name '*.jar' | paste -sd: -)"
export CLASSPATH

"$JAVA_HOME/bin/java" org.weasis.launcher.AppLauncher
ec_code=$?

echo ""
echo "🧹 Limpando workspace..."
rm -rf "$WEASIS_HOME"
if [ $ec_code -eq 0 ]; then
  echo "✅ Weasis finalizado"
else
  echo "⚠️  Código de saída: $ec_code"
fi
