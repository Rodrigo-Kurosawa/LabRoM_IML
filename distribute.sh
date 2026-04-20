#!/bin/bash
# =============================================================================
# distribute.sh — Empacotador multi-plataforma do Weasis (LabRoM/IML)
#
# Uso:
#   ./distribute.sh [opções]
#
# Opções:
#   --jdk <path>     Caminho para um JDK 25 com jpackage  (padrão: auto-detect)
#   --output <path>  Pasta de saída dos instaladores      (padrão: ./dist-output)
#   --platform mac|linux|windows  Forçar plataforma alvo (padrão: atual)
#   --no-package     Gera apenas a imagem de app, sem instalador
#   --mac-cert <nome>  Nome do certificado de assinatura macOS
#   --help           Exibe esta ajuda
#
# Pré-requisitos:
#   macOS  : jdk >= 25 com jpackage; Xcode CLI Tools
#   Linux  : jdk >= 25 com jpackage; dpkg-deb (fakeroot + dpkg-deb)
#   Windows: jdk >= 25 com jpackage; WiX Toolset >= 4 (para gerar MSI)
#   (Linux cruzado): Docker + QEMU (ver seção README deste script)
# =============================================================================

set -eo pipefail

# ─── Cores ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'
info()    { echo -e "${CYAN}[INFO]${RESET}  $*"; }
success() { echo -e "${GREEN}[OK]${RESET}    $*"; }
warn()    { echo -e "${YELLOW}[AVISO]${RESET} $*"; }
step()    { echo -e "\n${BOLD}── $* ──${RESET}"; }
die()     { echo -e "${RED}[ERRO]${RESET}  $*" >&2; exit 1; }

# ─── Localização do script ───────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN_DIST="${SCRIPT_DIR}/bin-dist"
BUILD_SCRIPT="${SCRIPT_DIR}/build/script/package-weasis.sh"
BUILD_PROPS="${SCRIPT_DIR}/build/script/build.properties"

# ─── LabRoM/IML: diretórios do plugin ────────────────────────────────────────
LABROM_DIR="$(dirname "$SCRIPT_DIR")/LabRoM_IML"
SEX_CLASSIFIER_DIR="${LABROM_DIR}/weasis-sex-classifier"

# ─── Defaults ────────────────────────────────────────────────────────────────
JDK_PATH=""
OUTPUT_PATH="${SCRIPT_DIR}/dist-output"
FORCE_PLATFORM=""
PACKAGE="YES"
MAC_CERT=""

# ─── Parse args ──────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      grep '^#' "$0" | grep -v '#!/' | sed 's/^# \?//'
      exit 0 ;;
    -j|--jdk)       JDK_PATH="$2";       shift 2 ;;
    -o|--output)    OUTPUT_PATH="$2";    shift 2 ;;
    --platform)     FORCE_PLATFORM="$2"; shift 2 ;;
    --no-package)   PACKAGE="NO";        shift ;;
    --mac-cert)     MAC_CERT="$2";       shift 2 ;;
    *) die "Opção desconhecida: $1. Use --help para ajuda." ;;
  esac
done

# ─── Verificações básicas ─────────────────────────────────────────────────────
[[ -d "$BIN_DIST" ]]    || die "Pasta bin-dist não encontrada em: $BIN_DIST"
[[ -f "$BUILD_SCRIPT" ]] || die "package-weasis.sh não encontrado em: $BUILD_SCRIPT"
[[ -f "$BUILD_PROPS" ]]  || die "build.properties não encontrado em: $BUILD_PROPS"

# ─── Auto-detect JDK ─────────────────────────────────────────────────────────
if [[ -z "$JDK_PATH" ]]; then
  # Tenta JAVA_HOME primeiro
  if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "$JAVA_HOME/bin/jpackage" ]]; then
    JDK_PATH="$JAVA_HOME"
  # macOS: busca JDKs em ~/tools e /Library/Java
  elif [[ -d "$HOME/tools" ]]; then
    JDK_PATH=$(find "$HOME/tools" -maxdepth 2 -name "jpackage" 2>/dev/null | head -1 | sed 's|/bin/jpackage||')
  fi
  # Fallback: detecta via 'java' no PATH
  if [[ -z "$JDK_PATH" ]] && command -v java &>/dev/null; then
    JAVA_BIN=$(readlink -f "$(command -v java)" 2>/dev/null || command -v java)
    JDK_PATH="${JAVA_BIN%/bin/java}"
    # Em macOS, o Contents/Home pode estar um nível acima
    [[ -x "$JDK_PATH/bin/jpackage" ]] || JDK_PATH="$(dirname "$JDK_PATH")"
  fi
fi

[[ -x "${JDK_PATH}/bin/jpackage" ]] || \
  die "jpackage não encontrado em ${JDK_PATH}/bin/jpackage.\nForneça o caminho correto com --jdk <path_do_jdk>"

success "JDK encontrado: ${JDK_PATH}"

# ─── Detectar plataforma atual ────────────────────────────────────────────────
detect_platform() {
  case "$(uname -s)" in
    Darwin*)  echo "macosx" ;;
    Linux*)   echo "linux"  ;;
    MINGW*|MSYS*|CYGWIN*) echo "windows" ;;
    *) die "Sistema operacional não suportado: $(uname -s)" ;;
  esac
}
CURRENT_PLATFORM=$(detect_platform)
TARGET_PLATFORM="${FORCE_PLATFORM:-$CURRENT_PLATFORM}"
info "Plataforma alvo: ${BOLD}${TARGET_PLATFORM}${RESET}"

# ─── Ler versão do Weasis ─────────────────────────────────────────────────────
WEASIS_VERSION=$(grep -i "weasis.version=" "$BUILD_PROPS" | sed 's/^.*=//')
info "Versão Weasis: ${BOLD}${WEASIS_VERSION}${RESET}"

# ─── Preparar pasta de saída ──────────────────────────────────────────────────
mkdir -p "$OUTPUT_PATH"
info "Saída dos instaladores: ${OUTPUT_PATH}"

# ─── Função: build para plataforma atual (Mac ou Linux) ──────────────────────
build_native() {
  local extra_args=()

  [[ "$PACKAGE" = "NO" ]] && extra_args+=("--no-installer")

  if [[ "$TARGET_PLATFORM" = "macosx" ]] && [[ -n "$MAC_CERT" ]]; then
    extra_args+=("--mac-signing-key-user-name" "$MAC_CERT")
  fi

  # No macOS, limpa xattrs (com.apple.FinderInfo, quarantine) que bloqueiam o codesign
  if [[ "$TARGET_PLATFORM" = "macosx" ]] && command -v xattr &>/dev/null; then
    info "Limpando atributos estendidos (xattrs) de bin-dist para codesign..."
    xattr -rc "$BIN_DIST" 2>/dev/null || true
  fi

  # O jpackage/codesign do macOS falha em caminhos com caracteres não-ASCII.
  # Por segurança, sempre usamos um path temporário no macOS e copiamos depois.
  local BUILD_OUT="$OUTPUT_PATH"
  if [[ "$TARGET_PLATFORM" = "macosx" ]]; then
    BUILD_OUT="/tmp/weasis-build-$$"
    mkdir -p "$BUILD_OUT"
  fi

  info "Executando package-weasis.sh..."
  bash "$BUILD_SCRIPT" \
    --jdk "$JDK_PATH" \
    --input "$BIN_DIST" \
    --output "$BUILD_OUT" \
    "${extra_args[@]}"

  # Copia do staging para o destino final (se necessário)
  if [[ "$BUILD_OUT" != "$OUTPUT_PATH" ]]; then
    info "Copiando resultado de ${BUILD_OUT} para ${OUTPUT_PATH}..."
    mkdir -p "$OUTPUT_PATH"
    cp -Rf "${BUILD_OUT}/"* "${OUTPUT_PATH}/" 2>/dev/null || true
    rm -rf "$BUILD_OUT"
  fi
}

# ─── Função: build Linux via Docker (cross-platform) ─────────────────────────
build_linux_docker() {
  local arch="${1:-linux/amd64}"  # linux/amd64 ou linux/arm64

  if ! command -v docker &>/dev/null; then
    die "Docker não encontrado no PATH.

  Para gerar instaladores Linux a partir do macOS você precisa do Docker Desktop.
  Instale em: https://www.docker.com/products/docker-desktop/

  Depois de instalar e iniciar o Docker Desktop, rode este script novamente.

  Alternativa sem Docker — use GitHub Actions para gerar o .deb/.rpm
  automaticamente num runner Ubuntu (veja DISTRIBUICAO.md)."
  fi

  # Verifica se o daemon está rodando
  if ! docker info &>/dev/null 2>&1; then
    die "Docker instalado mas não está rodando.
  Abra o Docker Desktop e aguarde ele iniciar completamente, depois tente novamente."
  fi

  local DOCKER_DIR="${SCRIPT_DIR}/build/docker"
  [[ -f "${DOCKER_DIR}/Dockerfile" ]] || die "Dockerfile não encontrado em ${DOCKER_DIR}"

  info "Construindo imagem Docker para ${arch}..."
  docker buildx build --load --platform "$arch" \
    -t weasis/builder:latest "${DOCKER_DIR}"

  # Prepara área de trabalho temporária dentro do docker dir
  local WORK="${DOCKER_DIR}"
  rm -rf "${WORK}/bin-dist" "${WORK}/installer"
  cp -Rf "${BIN_DIST}" "${WORK}/bin-dist"
  mkdir -p "${WORK}/installer"

  info "Rodando empacotador dentro do container ${arch}..."
  docker run --platform "$arch" --rm \
    -v "${WORK}:/work" \
    weasis/builder:latest \
    bash -c "
      export JAVA_TOOL_OPTIONS=-Djdk.lang.Process.launchMechanism=vfork
      mkdir -p /work/installer
      /work/build/script/package-weasis.sh \
        --jdk /opt/java/openjdk \
        --input /work/bin-dist \
        --output /work/installer \
        --temp /work/temp
    "

  # Copiar resultados
  cp -Rf "${WORK}/installer/"* "${OUTPUT_PATH}/" 2>/dev/null || true
  success "Artefatos Linux (${arch}) copiados para: ${OUTPUT_PATH}"
}

# ─── LabRoM/IML: funções de setup ────────────────────────────────────────────

# Cria o venv Python em $1 com todas as dependências do plugin.
_setup_python_venv() {
  local venv_dir="$1"
  local python_cmd=""

  # No Linux, preferir python3.12 para compatibilidade com Ubuntu 24.04 LTS (alvo principal).
  # Python 3.13+ requer libpython3.13.so no destino — Ubuntu 24.04 não tem essa versão.
  if [[ "$(uname -s)" = "Linux" ]]; then
    _PY_CANDIDATES="python3.12 python3.11 python3.10 python3.9 python3 python"
  else
    _PY_CANDIDATES="python3 python3.12 python3.11 python3.10 python3.9 python"
  fi

  for candidate in $_PY_CANDIDATES; do
    if command -v "$candidate" &>/dev/null; then
      local ver
      ver=$("$candidate" -c \
        "import sys; print(sys.version_info.major*10+sys.version_info.minor)" 2>/dev/null)
      if [[ "${ver:-0}" -ge 39 ]]; then
        python_cmd="$candidate"
        break
      fi
    fi
  done

  if [[ -z "$python_cmd" ]]; then
    warn "Python 3.9+ não encontrado nesta máquina."
    warn "O ambiente Python NÃO será embutido na distribuição."
    warn "Execute './setup-python.sh' no PC de destino antes de usar o plugin."
    return 0
  fi

  # Avisa se não for 3.12 no Linux (pode não funcionar no Ubuntu 24.04)
  local py_minor
  py_minor=$("$python_cmd" -c "import sys; print(sys.version_info.minor)" 2>/dev/null)
  local py_major
  py_major=$("$python_cmd" -c "import sys; print(sys.version_info.major)" 2>/dev/null)
  if [[ "$(uname -s)" = "Linux" && "${py_major:-0}" -eq 3 && "${py_minor:-0}" -gt 12 ]]; then
    warn "Python ${py_major}.${py_minor} detectado. Ubuntu 24.04 não tem libpython${py_major}.${py_minor}."
    warn "Instale python3.12 no build: sudo apt install python3.12 python3.12-venv"
  fi

  info "Criando venv isolado em: weasis/python-env/  (aguarde — instala PyTorch et al.)"
  "$python_cmd" -m venv --copies "$venv_dir" --clear 2>/dev/null \
    || "$python_cmd" -m venv "$venv_dir" --clear

  local pip_bin="$venv_dir/bin/pip"
  [[ -x "$pip_bin" ]] || pip_bin="$venv_dir/Scripts/pip.exe"

  if [[ ! -x "$pip_bin" ]]; then
    warn "pip não encontrado no venv criado. Verifique a instalação de Python."
    return 0
  fi

  "$pip_bin" install --upgrade pip --quiet
  "$pip_bin" install \
    ultralytics \
    "torch" \
    opencv-python \
    grad-cam \
    --quiet

  local py_bin="$venv_dir/bin/python3"
  [[ -x "$py_bin" ]] || py_bin="$venv_dir/Scripts/python.exe"
  local ul_ver
  ul_ver=$("$py_bin" -c "import ultralytics; print(ultralytics.__version__)" 2>/dev/null \
           || echo "instalado")
  success "Venv Python pronto  (ultralytics ${ul_ver})"
}

# Gera setup-python.sh (Unix) e setup-python.bat (Windows) dentro de bin-dist.
_create_setup_scripts() {
  # ── Unix ──
  cat > "$BIN_DIST/setup-python.sh" << 'SETUP_SH'
#!/bin/bash
# setup-python.sh  –  Configura o ambiente Python para o LabRoM Sex Classifier.
# Execute UMA VEZ em PCs novos:   bash setup-python.sh
# O venv é criado em ~/.weasis/labrom-env/ e detectado automaticamente pelo plugin.
set -e
VENV_DIR="$HOME/.weasis/labrom-env"

python_cmd=""
for c in python3 python3.12 python3.11 python3.10 python3.9 python; do
  if command -v "$c" &>/dev/null; then
    ver=$("$c" -c "import sys; print(sys.version_info.major*10+sys.version_info.minor)" 2>/dev/null)
    [ "${ver:-0}" -ge 39 ] && { python_cmd="$c"; break; }
  fi
done

if [ -z "$python_cmd" ]; then
  echo "ERRO: Python 3.9+ não encontrado. Instale Python e tente novamente."
  exit 1
fi

echo "Criando ambiente Python em: $VENV_DIR"
mkdir -p "$(dirname "$VENV_DIR")"
"$python_cmd" -m venv --copies "$VENV_DIR" --clear 2>/dev/null \
  || "$python_cmd" -m venv "$VENV_DIR" --clear

"$VENV_DIR/bin/pip" install --upgrade pip --quiet
"$VENV_DIR/bin/pip" install \
  ultralytics torch opencv-python grad-cam --quiet

echo "Ambiente Python configurado com sucesso em: $VENV_DIR"
SETUP_SH
  chmod +x "$BIN_DIST/setup-python.sh"

  # ── Windows ──
  cat > "$BIN_DIST/setup-python.bat" << 'SETUP_BAT'
@echo off
REM setup-python.bat  –  Configura o ambiente Python para o LabRoM Sex Classifier.
REM Execute UMA VEZ em PCs novos (duplo-clique ou cmd).
REM O venv e criado em %USERPROFILE%\.weasis\labrom-env\ e detectado automaticamente.
set VENV=%USERPROFILE%\.weasis\labrom-env
python -m venv --copies "%VENV%"
"%VENV%\Scripts\pip" install --upgrade pip
"%VENV%\Scripts\pip" install ultralytics torch opencv-python grad-cam
echo Ambiente Python configurado com sucesso em: %VENV%
pause
SETUP_BAT

  success "Scripts de setup gerados: setup-python.sh  /  setup-python.bat"
}

# Retorna o diretório "app/" dentro da imagem jpackage gerada.
# É onde ficam os JARs e onde o venv deve ser instalado.
_app_dir_in_image() {
  local platform="$1"   # macosx | linux | windows
  local out="$2"        # caminho de saída do jpackage
  case "$platform" in
    macosx)  echo "${out}/LabRoM_IML.app/Contents/app" ;;
    linux)   echo "${out}/LabRoM_IML/lib/app" ;;
    windows) echo "${out}/LabRoM_IML/app" ;;
    *)       echo "" ;;
  esac
}

# Instala o venv Python directamente dentro da imagem do app já gerada.
# Isso evita que jpackage tente re-assinar os binários do Python (codesign).
_install_venv_in_app() {
  local app_dir="$1"
  if [[ -z "$app_dir" || ! -d "$app_dir" ]]; then
    warn "App dir não encontrado: ${app_dir}. Pulando instalação do venv."
    return 0
  fi
  step "Instalando ambiente Python dentro do app (pós-jpackage)"
  _setup_python_venv "${app_dir}/python-env"
}

# Fase principal LabRoM: JAR + modelos (sem venv — este vai para dentro do app após jpackage)
setup_labrom() {
  step "LabRoM/IML — preparando plugin Sex Classifier"

  if [[ ! -d "$SEX_CLASSIFIER_DIR" ]]; then
    warn "Diretório não encontrado: $SEX_CLASSIFIER_DIR"
    warn "Pulando fase LabRoM."
    return 0
  fi

  # 1. Compilar plugin (se o JAR ainda não existir)
  local jar_src
  jar_src=$(find "$SEX_CLASSIFIER_DIR/target" \
    -name "weasis-sex-classifier-*.jar" ! -name "*-sources.jar" 2>/dev/null | head -1)

  if [[ -z "$jar_src" ]]; then
    info "JAR não encontrado — compilando com mvn package..."
    (cd "$SEX_CLASSIFIER_DIR" && \
      JAVA_HOME="$JDK_PATH" mvn package -DskipTests -q 2>&1) \
      || warn "mvn package falhou. Certifique-se de ter o JAR em target/ antes de distribuir."
    jar_src=$(find "$SEX_CLASSIFIER_DIR/target" \
      -name "weasis-sex-classifier-*.jar" ! -name "*-sources.jar" 2>/dev/null | head -1)
  fi

  if [[ -n "$jar_src" ]]; then
    cp -f "$jar_src" "$BIN_DIST/weasis/bundle/"
    success "Plugin JAR → bundle/  ($(basename "$jar_src"))"
  else
    warn "JAR não encontrado. Distribua manualmente depois de compilar."
  fi

  # 2. Copiar modelos (.pt) → bin-dist/weasis/models/
  local models_src="$SEX_CLASSIFIER_DIR/models"
  if [[ -d "$models_src" ]]; then
    mkdir -p "$BIN_DIST/weasis/models"
    find "$models_src" -name "*.pt" -exec cp -f {} "$BIN_DIST/weasis/models/" \;
    local n_models
    n_models=$(find "$BIN_DIST/weasis/models" -name "*.pt" | wc -l | tr -d ' ')
    success "Modelos (.pt) → weasis/models/  ($n_models arquivo(s))"
  else
    warn "Pasta models/ não encontrada em: $models_src"
  fi

  # 3. Remover qualquer python-env residual do input do jpackage.
  #    O venv é criado DENTRO do app após o build (evita conflito de codesign).
  if [[ -d "$BIN_DIST/weasis/python-env" ]]; then
    info "Removendo python-env residual de bin-dist/weasis/ (será recriado pós-build)..."
    rm -rf "$BIN_DIST/weasis/python-env"
  fi

  # 4. Gerar scripts de setup para PCs que precisem recriar o venv manualmente
  _create_setup_scripts
}

# ─── Execução principal ───────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}════════════════════════════════════════${RESET}"
echo -e "${BOLD}   Weasis Distribuição — ${TARGET_PLATFORM}${RESET}"
echo -e "${BOLD}════════════════════════════════════════${RESET}"
echo ""

# Executar fase LabRoM antes do jpackage
setup_labrom
echo ""

case "$TARGET_PLATFORM" in
  macosx)
    if [[ "$CURRENT_PLATFORM" != "macosx" ]]; then
      die "Para gerar instaladores macOS (.pkg), execute este script em um Mac."
    fi
    build_native
    _install_venv_in_app "$(_app_dir_in_image macosx "$OUTPUT_PATH")"
    success "Instalador macOS (.pkg) gerado em: ${OUTPUT_PATH}"
    ;;

  linux)
    if [[ "$CURRENT_PLATFORM" = "linux" ]]; then
      # Passo 1: gera apenas a imagem do app
      bash "$BUILD_SCRIPT" --jdk "$JDK_PATH" --input "$BIN_DIST" --output "$OUTPUT_PATH" --no-installer

      # Passo 2: instala o venv Python dentro da imagem
      _LINUX_APP_DIR="$(_app_dir_in_image linux "$OUTPUT_PATH")"
      _install_venv_in_app "$_LINUX_APP_DIR"

      if [[ "$PACKAGE" = "YES" ]]; then
        # Passo 3: move o venv para fora da imagem antes de empacotar.
        # O jpackage roda dpkg -S em cada .so do app — com PyTorch isso são
        # centenas de arquivos e torna o build muito lento.
        _VENV_TMP="/tmp/labrom-venv-$$"
        if [[ -d "${_LINUX_APP_DIR}/python-env" ]]; then
          mv "${_LINUX_APP_DIR}/python-env" "$_VENV_TMP"
        fi

        # Passo 4: gera o .deb sem o venv (rápido — sem scan de .so do torch)
        bash "$BUILD_SCRIPT" --jdk "$JDK_PATH" --input "$BIN_DIST" --output "$OUTPUT_PATH" --installer-only

        # Passo 5: reinjecta o venv no .deb via repack e restaura na imagem local
        if [[ -d "$_VENV_TMP" ]]; then
          _DEB_FILE=$(ls "$OUTPUT_PATH"/*.deb 2>/dev/null | head -1)
          if [[ -n "$_DEB_FILE" ]]; then
            _REPACK_DIR="/tmp/labrom-repack-$$"
            info "Reempacotando .deb com o venv Python..."
            dpkg-deb -R "$_DEB_FILE" "$_REPACK_DIR"
            cp -Rf "$_VENV_TMP" "$_REPACK_DIR/opt/labrom-iml/lib/app/python-env"

            # ── Tornar o venv relocatável para o PC de destino ──────────────
            _VENV_IN_DEB="$_REPACK_DIR/opt/labrom-iml/lib/app/python-env"

            # Detecta versão do Python no venv (ex: "python3.12")
            _PY_VER_DIR=$(ls "$_VENV_IN_DEB/lib/" 2>/dev/null | head -1)
            _PY_SHORTVER="${_PY_VER_DIR#python}"   # "3.12"

            # Corrige pyvenv.cfg para apontar para o Python do sistema de destino
            _PYVENV_CFG="$_VENV_IN_DEB/pyvenv.cfg"
            if [[ -f "$_PYVENV_CFG" && -n "$_PY_SHORTVER" ]]; then
              sed -i "s|^home = .*|home = /usr/bin|"                               "$_PYVENV_CFG"
              sed -i "s|^base-executable = .*|base-executable = /usr/bin/python${_PY_SHORTVER}|" "$_PYVENV_CFG"
              sed -i "s|^base-prefix = .*|base-prefix = /usr|"                     "$_PYVENV_CFG"
              sed -i "s|^base-exec-prefix = .*|base-exec-prefix = /usr|"           "$_PYVENV_CFG"
              info "pyvenv.cfg → /usr/bin/python${_PY_SHORTVER}"
            fi

            # Desabilita torch.compile/_inductor (requer ferramentas GPU/CUDA ausentes na maioria dos PCs)
            _SITE_PKG="$_VENV_IN_DEB/lib/${_PY_VER_DIR}/site-packages"
            if [[ -d "$_SITE_PKG" ]]; then
              cat > "$_SITE_PKG/sitecustomize.py" << 'SITECUSTOMIZE'
import os
# Disable PyTorch JIT/compile backend — requires GPU compiler tools not available on all systems
os.environ.setdefault("TORCH_COMPILE_DISABLE", "1")
os.environ.setdefault("TORCHDYNAMO_DISABLE", "1")
SITECUSTOMIZE
              info "sitecustomize.py → desabilita torch.compile"
            fi

            fakeroot dpkg-deb -b "$_REPACK_DIR" "$_DEB_FILE"
            rm -rf "$_REPACK_DIR"
            success "Venv reinjetado no .deb"
          fi
          mv "$_VENV_TMP" "${_LINUX_APP_DIR}/python-env"
        fi
      fi
      success "Instalador Linux (.deb) gerado em: ${OUTPUT_PATH}"
    else
      # Build cruzado via Docker (funciona no macOS também)
      warn "Você está no macOS — usando Docker para gerar instaladores Linux."
      warn "Certifique-se de que Docker Desktop está rodando."
      build_linux_docker "linux/amd64"
    fi
    ;;

  linux-arm64)
    warn "Gerando instalador Linux ARM64 via Docker..."
    build_linux_docker "linux/arm64"
    ;;

  linux-all)
    warn "Gerando instaladores Linux (amd64 + arm64) via Docker..."
    build_linux_docker "linux/amd64"
    build_linux_docker "linux/arm64"
    ;;

  windows)
    die "Para gerar instaladores Windows (.msi), você precisa:
  1. Copiar o conteúdo de weasis-native para um Windows com JDK >= 25 e WiX Toolset 4.
  2. Executar no PowerShell (ou Git Bash):
       bash build/script/package-weasis.sh --jdk C:/path/to/jdk --input bin-dist --output dist-output
  
  Alternativamente, use um pipeline CI/CD (GitHub Actions) — veja DISTRIBUICAO.md."
    ;;

  *)
    die "Plataforma desconhecida: ${TARGET_PLATFORM}. Use: macosx | linux | linux-arm64 | linux-all | windows"
    ;;
esac

echo ""
info "Conteúdo gerado em: ${OUTPUT_PATH}"
ls -lh "${OUTPUT_PATH}" 2>/dev/null || true
echo ""
success "Concluído! ✓"
