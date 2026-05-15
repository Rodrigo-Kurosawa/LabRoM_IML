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
APP_NAME="LabRoM_IML"

# Se o script foi copiado para weasis-native pelo run_weasis.sh, SCRIPT_DIR
# aponta para weasis-native. Detecta automaticamente o diretório com bin-dist.
NATIVE_DIR="$SCRIPT_DIR"
if [[ ! -d "$NATIVE_DIR/bin-dist" ]]; then
  _alt="$(dirname "$SCRIPT_DIR")/weasis-native"
  [[ -d "$_alt/bin-dist" ]] && NATIVE_DIR="$_alt"
fi

BIN_DIST="${NATIVE_DIR}/bin-dist"
BUILD_SCRIPT="${NATIVE_DIR}/build/script/package-weasis.sh"
BUILD_PROPS="${NATIVE_DIR}/build/script/build.properties"

# ─── LabRoM/IML: diretórios do plugin ────────────────────────────────────────
# Funciona quando rodado de LabRoM_IML/ ou de weasis-native/ (após cópia pelo run_weasis.sh).
if [[ -d "$SCRIPT_DIR/assets" ]]; then
  LABROM_DIR="$SCRIPT_DIR"
else
  LABROM_DIR="$(dirname "$SCRIPT_DIR")/$APP_NAME"
fi
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

# ─── python-build-standalone (astral-sh) ─────────────────────────────────────
# CPython pré-compilado com libpython embutida via RPATH $ORIGIN/../lib.
# Funciona em qualquer distro com glibc 2.17+ (Ubuntu 20.04+) sem Python instalado.
# Releases: https://github.com/astral-sh/python-build-standalone/releases
_PBS_RELEASE="20260504"
_PBS_PY_VER="3.12.13"

_pbs_filename() {
  local platform="$1" arch="$2"
  case "${platform}-${arch}" in
    linux-x86_64)   echo "cpython-${_PBS_PY_VER}+${_PBS_RELEASE}-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz" ;;
    linux-aarch64)  echo "cpython-${_PBS_PY_VER}+${_PBS_RELEASE}-aarch64-unknown-linux-gnu-install_only_stripped.tar.gz" ;;
    macosx-x86_64)  echo "cpython-${_PBS_PY_VER}+${_PBS_RELEASE}-x86_64-apple-darwin-install_only_stripped.tar.gz" ;;
    macosx-aarch64) echo "cpython-${_PBS_PY_VER}+${_PBS_RELEASE}-aarch64-apple-darwin-install_only_stripped.tar.gz" ;;
    *) echo "" ;;
  esac
}

# Baixa (ou usa cache em ~/.cache/labrom-pbs/) o tarball do python-build-standalone.
# Imprime o caminho do tarball em stdout. Retorna 1 sem abortar em caso de falha.
_download_standalone_python() {
  local platform="$1" arch="$2"
  local cache_dir="$HOME/.cache/labrom-pbs"
  mkdir -p "$cache_dir"

  local filename
  filename=$(_pbs_filename "$platform" "$arch")
  if [[ -z "$filename" ]]; then
    warn "Arquitetura sem suporte para python-build-standalone: ${platform}/${arch}"
    return 1
  fi

  local tarball="$cache_dir/$filename"
  if [[ ! -f "$tarball" ]]; then
    local url="https://github.com/astral-sh/python-build-standalone/releases/download/${_PBS_RELEASE}/${filename}"
    info "Baixando Python ${_PBS_PY_VER} standalone (${platform}/${arch})..."
    if command -v curl &>/dev/null; then
      curl -L --progress-bar -o "$tarball" "$url" \
        || { rm -f "$tarball"; warn "Falha ao baixar Python standalone de: $url"; return 1; }
    elif command -v wget &>/dev/null; then
      wget -q --show-progress -O "$tarball" "$url" \
        || { rm -f "$tarball"; warn "Falha ao baixar Python standalone de: $url"; return 1; }
    else
      warn "curl ou wget necessário para baixar Python standalone."
      return 1
    fi
    success "Python standalone baixado: ${filename}"
  else
    info "Python standalone em cache: ${filename}"
  fi
  echo "$tarball"
}

# Extrai o Python standalone em target_dir e instala as dependências do plugin.
_setup_standalone_python() {
  local target_dir="$1"
  local platform="${2:-$(detect_platform)}"

  local arch
  case "$(uname -m)" in
    x86_64)        arch="x86_64"  ;;
    arm64|aarch64) arch="aarch64" ;;
    *) warn "Arquitetura não reconhecida: $(uname -m)"; return 1 ;;
  esac

  local tarball
  tarball=$(_download_standalone_python "$platform" "$arch") || return 1

  info "Extraindo Python ${_PBS_PY_VER} standalone..."
  rm -rf "$target_dir"
  local tmp_extract="${target_dir}.extract_$$"
  mkdir -p "$tmp_extract"
  tar -xzf "$tarball" -C "$tmp_extract"
  # O tarball extrai para uma subpasta chamada "python/"
  mv "$tmp_extract/python" "$target_dir"
  rm -rf "$tmp_extract"

  local pip_bin="$target_dir/bin/pip3"
  [[ -x "$pip_bin" ]] || "$target_dir/bin/python3" -m ensurepip --upgrade &>/dev/null

  info "Instalando dependências Python (torch, ultralytics, pydicom, pillow, grad-cam)..."
  "$pip_bin" install --upgrade pip --quiet
  "$pip_bin" install \
    pillow \
    pydicom \
    "pylibjpeg[all]" \
    ultralytics \
    torch \
    torchvision \
    opencv-python \
    grad-cam \
    --quiet

  # Desabilita torch.compile: requer ferramentas de compilação GPU ausentes na maioria dos PCs
  local py_ver_dir
  py_ver_dir=$(ls "$target_dir/lib/" | grep "^python3" | head -1)
  if [[ -d "$target_dir/lib/${py_ver_dir}/site-packages" ]]; then
    cat > "$target_dir/lib/${py_ver_dir}/site-packages/sitecustomize.py" << 'SITECUSTOMIZE'
import os
os.environ.setdefault("TORCH_COMPILE_DISABLE", "1")
os.environ.setdefault("TORCHDYNAMO_DISABLE", "1")
SITECUSTOMIZE
  fi

  local ul_ver
  ul_ver=$("$target_dir/bin/python3" -c \
    "import ultralytics; print(ultralytics.__version__)" 2>/dev/null || echo "instalado")
  success "Python ${_PBS_PY_VER} standalone pronto  (ultralytics ${ul_ver})"
}

# Retorna o diretório "app/" dentro da imagem jpackage gerada.
# É onde ficam os JARs e onde o venv deve ser instalado.
_app_dir_in_image() {
  local platform="$1"   # macosx | linux | windows
  local out="$2"        # caminho de saída do jpackage
  # No Linux, jpackage usa NAME_LINUX="labrom-iml" (minúsculo, sem underscore)
  # porque dpkg-deb exige esse formato. Nos demais, usa APP_NAME diretamente.
  case "$platform" in
    macosx)  echo "${out}/${APP_NAME}.app/Contents/app" ;;
    linux)   echo "${out}/labrom-iml/lib/app" ;;
    windows) echo "${out}/${APP_NAME}/app" ;;
    *)       echo "" ;;
  esac
}

# Instala Python standalone dentro da imagem do app gerada pelo jpackage.
_install_venv_in_app() {
  local app_dir="$1"
  local platform="${2:-$(detect_platform)}"
  if [[ -z "$app_dir" || ! -d "$app_dir" ]]; then
    warn "App dir não encontrado: ${app_dir}. Pulando instalação do Python."
    return 0
  fi
  step "Instalando Python ${_PBS_PY_VER} standalone dentro do app"
  _setup_standalone_python "${app_dir}/python-env" "$platform" || {
    warn "Python standalone não instalado. O .deb será gerado sem Python embutido."
    warn "O classificador de sexo não funcionará sem python-env."
    return 0
  }
}

# Fase principal LabRoM: JAR + modelos (sem venv — este vai para dentro do app após jpackage)
setup_labrom() {
  step "LabRoM/IML — preparando plugin Sex Classifier"

  if [[ ! -d "$SEX_CLASSIFIER_DIR" ]]; then
    warn "Diretório não encontrado: $SEX_CLASSIFIER_DIR"
    warn "Pulando fase LabRoM."
    return 0
  fi

  # 1. Compilar módulos customizados (sempre — garante que as últimas mudanças estão no app)
  local mvn_cmd
  mvn_cmd=$(find "$HOME/tools" -maxdepth 3 -name "mvn" -path "*/apache-maven-*/bin/mvn" \
    2>/dev/null | sort -V | tail -n 1)
  [[ -z "$mvn_cmd" ]] && mvn_cmd=$(command -v mvn 2>/dev/null || true)

  if [[ -n "$mvn_cmd" ]]; then
    info "Compilando módulos customizados com Maven..."
    if (cd "$LABROM_DIR" && JAVA_HOME="$JDK_PATH" "$mvn_cmd" install \
        -pl weasis-base/weasis-base-viewer2d,weasis-sex-classifier \
        -DskipTests -q 2>&1); then
      success "Compilação concluída."
    else
      warn "mvn install falhou — usando JARs pré-compilados de bin-dist se disponíveis."
    fi
  else
    warn "Maven não encontrado — usando JARs pré-compilados de bin-dist."
  fi

  local jar_src
  jar_src=$(find "$SEX_CLASSIFIER_DIR/target" \
    -name "weasis-sex-classifier-*.jar" ! -name "*-sources.jar" 2>/dev/null | head -1)

  if [[ -n "$jar_src" ]]; then
    cp -f "$jar_src" "$BIN_DIST/weasis/bundle/"
    success "Plugin JAR → bundle/  ($(basename "$jar_src"))"
  else
    warn "JAR do sex-classifier não encontrado. O bundle existente em bin-dist será usado."
  fi

  # Injetar base viewer customizado (BEST_FIT zoom por padrão)
  local base_viewer_jar
  base_viewer_jar=$(find "$LABROM_DIR/weasis-base/weasis-base-viewer2d/target" \
    -name "weasis-base-viewer2d-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" \
    2>/dev/null | head -1)
  if [[ -n "$base_viewer_jar" ]]; then
    cp -f "$base_viewer_jar" "$BIN_DIST/weasis/bundle/"
    success "Base viewer JAR → bundle/  ($(basename "$base_viewer_jar"))"
  else
    warn "JAR do base viewer não encontrado. O bundle existente em bin-dist será usado."
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
  #    O Python standalone é instalado DENTRO do app após o build.
  if [[ -d "$BIN_DIST/weasis/python-env" ]]; then
    info "Removendo python-env residual de bin-dist/weasis/ (será recriado pós-build)..."
    rm -rf "$BIN_DIST/weasis/python-env"
  fi

  # 4. Limpar caches do Felix OSGi (exceto cache-local do dev) para garantir
  #    que o próximo lançamento do app use os JARs atualizados do bundle.
  local weasis_cache_dir="$HOME/.weasis"
  if [[ -d "$weasis_cache_dir" ]]; then
    local _cleared=0
    for _cache_d in "$weasis_cache_dir"/cache-*; do
      [[ -d "$_cache_d" ]] || continue
      [[ "$(basename "$_cache_d")" == "cache-local" ]] && continue
      rm -rf "$_cache_d"
      ((_cleared++)) || true
    done
    if ((_cleared > 0)); then
      info "Cache Felix OSGi limpo ($_cleared diretório(s)) — app usará JARs atualizados no próximo lançamento."
    fi
  fi
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

    # Se o app já está instalado em /Applications, reinstalar o .pkg automaticamente
    # para que o app instalado receba os JARs atualizados (base-viewer2d + sex-classifier).
    # Sem isso, o usuário abre o app antigo de /Applications e o Felix usa os JARs desatualizados.
    _pkg_file=$(ls "${OUTPUT_PATH}"/*.pkg 2>/dev/null | head -1)
    if [[ -n "$_pkg_file" && -d "/Applications/${APP_NAME}.app" ]]; then
      info "App instalado detectado — reinstalando o .pkg para atualizar /Applications/${APP_NAME}.app ..."
      if sudo installer -pkg "$_pkg_file" -target / 2>&1; then
        rm -rf ~/.weasis/cache-D264E300 2>/dev/null || true
        success "App reinstalado com sucesso. Cache Felix limpo."
      else
        warn "Reinstalação falhou. Execute manualmente:"
        warn "  sudo installer -pkg \"$_pkg_file\" -target /"
      fi
    elif [[ -n "$_pkg_file" ]]; then
      info "Para instalar o app, execute:"
      info "  sudo installer -pkg \"$_pkg_file\" -target /"
    fi
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

        # Passo 4: gera o .deb sem python-env (rápido — sem scan de .so do torch)
        bash "$BUILD_SCRIPT" --jdk "$JDK_PATH" --input "$BIN_DIST" --output "$OUTPUT_PATH" --installer-only

        # Passo 5: repack único — corrige deps E injeta python-env (se disponível)
        # O patch de deps SEMPRE roda, independente de o python-env existir.
        _DEB_FILE=$(ls "$OUTPUT_PATH"/*.deb 2>/dev/null | head -1)
        if [[ -n "$_DEB_FILE" ]]; then
          _REPACK_DIR="/tmp/labrom-repack-$$"
          dpkg-deb -R "$_DEB_FILE" "$_REPACK_DIR"

          # Injeta python-env se foi criado com sucesso
          if [[ -d "$_VENV_TMP" ]]; then
            info "Reempacotando .deb com Python standalone..."
            cp -Rf "$_VENV_TMP" "$_REPACK_DIR/opt/labrom-iml/lib/app/python-env"
          else
            warn "Python standalone não disponível — .deb será gerado sem Python embutido."
          fi

          # Corrige dependências do jpackage incompatíveis com Ubuntu 20.04:
          #   libmd0    — introduzido no Ubuntu 22.04 (funções já no libc do 20.04)
          #   libgcc-s1 — renomeado de libgcc1 no Ubuntu 22.04
          _CTRL="$_REPACK_DIR/DEBIAN/control"
          python3 - "$_CTRL" << 'PYEOF'
import sys, re
path = sys.argv[1]
text = open(path).read()
def fix(line):
    pkgs = [p.strip() for p in re.sub(r'^Depends:\s*', '', line).split(',')]
    pkgs = [p for p in pkgs if p and p != 'libmd0']
    pkgs = [('libgcc1' if p == 'libgcc-s1' else p) for p in pkgs]
    seen, unique = set(), []
    for p in pkgs:
        if p not in seen: seen.add(p); unique.append(p)
    return 'Depends: ' + ', '.join(unique)
open(path, 'w').write('\n'.join(
    fix(l) if l.startswith('Depends:') else l for l in text.splitlines()
) + '\n')
PYEOF
          info "Dependências do .deb ajustadas para Ubuntu 20.04+"

          fakeroot dpkg-deb -b "$_REPACK_DIR" "$_DEB_FILE"
          rm -rf "$_REPACK_DIR"
          [[ -d "$_VENV_TMP" ]] && success "Python standalone reinjetado no .deb" \
                                 || success ".deb reempacotado (sem Python embutido)"
        fi
        [[ -d "$_VENV_TMP" ]] && mv "$_VENV_TMP" "${_LINUX_APP_DIR}/python-env"
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
