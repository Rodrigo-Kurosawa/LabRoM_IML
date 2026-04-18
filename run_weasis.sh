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
DEST_DIR="$(dirname "$SCRIPT_DIR")/weasis-native"
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
# Injetar imagens customizadas com fundo preto (ícone e splash)
# Espera: assets/icon.png  e  assets/splash.png ao lado deste script.
# Ferramentas: sips + iconutil (macOS built-in); magick/convert para .ico
# ---------------------------------------------------------------------------

# Redimensiona src para WxH com fundo preto centralizado.
# Usa ImageMagick se disponível; senão usa sips (built-in macOS).
_img_resize_black() {
  local src="$1" dst="$2" w="$3" h="$4"
  mkdir -p "$(dirname "$dst")"
  if command -v magick &>/dev/null; then
    magick "$src" -resize "${w}x${h}" -background black -gravity center -extent "${w}x${h}" "$dst"
  elif command -v convert &>/dev/null; then
    convert "$src" -resize "${w}x${h}" -background black -gravity center -extent "${w}x${h}" "$dst"
  else
    # sips: determina qual dimensão é o gargalo, redimensiona e centraliza com pad preto
    local tmp ow oh scale_h scale_w
    tmp=$(mktemp /tmp/weasis_img_XXXXXX.png)
    ow=$(sips -g pixelWidth  "$src" 2>/dev/null | awk '/pixelWidth/{print $2}')
    oh=$(sips -g pixelHeight "$src" 2>/dev/null | awk '/pixelHeight/{print $2}')
    scale_h=$(( oh * w / (ow > 0 ? ow : 1) ))   # altura resultante se ajustar pela largura
    if [ "$scale_h" -le "$h" ]; then
      sips --resampleWidth "$w" "$src" --out "$tmp" &>/dev/null
    else
      sips --resampleHeight "$h" "$src" --out "$tmp" &>/dev/null
    fi
    sips -c "$h" "$w" --padColor 000000 "$tmp" --out "$dst" &>/dev/null
    rm -f "$tmp"
  fi
  echo "  [PNG ${w}x${h}] → $dst"
}

# Gera .icns usando iconutil (macOS built-in).
_make_icns() {
  local src="$1" dst="$2"
  if ! command -v iconutil &>/dev/null; then
    echo "  AVISO: iconutil não encontrado — $dst não gerado."
    return
  fi
  local tmp
  tmp=$(mktemp -d /tmp/weasis_iconset_XXXXXX)
  local iset="$tmp/App.iconset"
  mkdir "$iset"
  for entry in "icon_16x16.png:16"     "icon_16x16@2x.png:32" \
               "icon_32x32.png:32"     "icon_32x32@2x.png:64" \
               "icon_128x128.png:128"  "icon_128x128@2x.png:256" \
               "icon_256x256.png:256"  "icon_256x256@2x.png:512" \
               "icon_512x512.png:512"  "icon_512x512@2x.png:1024"; do
    local fname="${entry%%:*}" sz="${entry##*:}"
    _img_resize_black "$src" "$iset/$fname" "$sz" "$sz"
  done
  mkdir -p "$(dirname "$dst")"
  iconutil -c icns "$iset" -o "$dst" \
    && echo "  [ICNS] → $dst" \
    || echo "  AVISO: iconutil falhou para $dst"
  rm -rf "$tmp"
}

# Gera .ico multi-size (requer ImageMagick).
_make_ico() {
  local src="$1" dst="$2"
  mkdir -p "$(dirname "$dst")"
  local IMG=""
  command -v magick   &>/dev/null && IMG="magick"
  command -v convert  &>/dev/null && [ -z "$IMG" ] && IMG="convert"
  if [ -n "$IMG" ]; then
    $IMG "$src" -background black -gravity center \
      \( -clone 0 -resize 16x16   -extent 16x16   \) \
      \( -clone 0 -resize 32x32   -extent 32x32   \) \
      \( -clone 0 -resize 48x48   -extent 48x48   \) \
      \( -clone 0 -resize 64x64   -extent 64x64   \) \
      \( -clone 0 -resize 128x128 -extent 128x128 \) \
      \( -clone 0 -resize 256x256 -extent 256x256 \) \
      -delete 0 "$dst"
    echo "  [ICO] → $dst"
  else
    echo "  AVISO: ImageMagick não encontrado — $dst ignorado (instale: brew install imagemagick)"
  fi
}

_inject_images() {
  local icon_src="$SCRIPT_DIR/assets/icon.png"
  local splash_src="$SCRIPT_DIR/assets/splash.png"

  [ ! -f "$icon_src" ] && [ ! -f "$splash_src" ] && return

  echo "Injetando imagens customizadas..."

  if [ -f "$icon_src" ]; then
    _img_resize_black "$icon_src" "$DEST_DIR/build/script/resources/linux/Weasis.png"    64  64
    _img_resize_black "$icon_src" "$DEST_DIR/build/script/resources/linux/Dicomizer.png" 64  64
    _make_ico         "$icon_src" "$DEST_DIR/build/script/resources/windows/Weasis.ico"
    _make_ico         "$icon_src" "$DEST_DIR/build/script/resources/windows/Dicomizer.ico"
    _make_icns        "$icon_src" "$DEST_DIR/build/script/resources/macosx/LabRoM_IML.icns"
    _make_icns        "$icon_src" "$DEST_DIR/build/script/resources/macosx/Weasis.icns"
    _make_icns        "$icon_src" "$DEST_DIR/build/script/resources/macosx/Dicomizer.icns"
    # Atualiza também o .icns dentro do dist-output (app já gerado)
    local dist_app
    for dist_app in "$DEST_DIR/dist-output/"*.app; do
      local res="$dist_app/Contents/Resources"
      if [ -d "$res" ]; then
        local icns
        for icns in "$res/"*.icns; do
          [ -f "$icns" ] && _make_icns "$icon_src" "$icns" && echo "  [ICNS] → $icns (dist-output)"
        done
      fi
    done
  fi

  if [ -f "$splash_src" ]; then
    # PNGs de banner/splash — usam splash.png (landscape, 1200x590) como fonte
    _img_resize_black "$splash_src" "$DEST_DIR/bin-dist/weasis/resources/images/about.png"       374 147
    _img_resize_black "$splash_src" "$DEST_DIR/bin-dist/weasis/resources/images/about-round.png" 374 147
    _img_resize_black "$splash_src" "$DEST_DIR/bin-dist/weasis/resources/images/logo-button.png" 140  44
    # SVG real usado pelo app (WeasisAbout.svg = splash/about dialog)
    local splash_b64
    splash_b64=$(base64 -i "$splash_src")
    local svg_dir="$DEST_DIR/bin-dist/weasis/resources/svg/logo"
    mkdir -p "$svg_dir"
    cat > "$svg_dir/WeasisAbout.svg" << SVGEOF
<svg width="448" height="162" viewBox="0 0 448 162" xmlns="http://www.w3.org/2000/svg">
  <rect width="448" height="162" fill="#000000"/>
  <image href="data:image/png;base64,${splash_b64}" x="0" y="0" width="448" height="162" preserveAspectRatio="xMidYMid meet"/>
</svg>
SVGEOF
    echo "  [SVG splash] → $svg_dir/WeasisAbout.svg"
  fi

  if [ -f "$icon_src" ]; then
    # SVG real usado pelo app para ícones na UI (Weasis.svg, Dicomizer.svg)
    local icon_b64
    icon_b64=$(base64 -i "$icon_src")
    local svg_dir="$DEST_DIR/bin-dist/weasis/resources/svg/logo"
    mkdir -p "$svg_dir"
    for svg_name in Weasis.svg Dicomizer.svg; do
      cat > "$svg_dir/$svg_name" << SVGEOF
<svg width="512" height="512" viewBox="0 0 512 512" xmlns="http://www.w3.org/2000/svg">
  <rect width="512" height="512" fill="#000000"/>
  <image href="data:image/png;base64,${icon_b64}" x="0" y="0" width="512" height="512" preserveAspectRatio="xMidYMid meet"/>
</svg>
SVGEOF
      echo "  [SVG icon] → $svg_dir/$svg_name"
    done
  fi
}

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
  java -Xdock:name="LabRoM_IML" \
     -Xdock:icon="$SCRIPT_DIR/assets/icon.png" \
     -Dweasis.name="LabRoM_IML" \
     -cp "weasis-launcher.jar:felix.jar" org.weasis.launcher.AppLauncher
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

# Copiar scripts de distribuição para weasis-native
[ -f "$SCRIPT_DIR/distribute.sh" ] && cp "$SCRIPT_DIR/distribute.sh" "$DEST_DIR/" \
  && echo "  Copiado: distribute.sh"
[ -f "$SCRIPT_DIR/package-weasis.sh" ] && cp "$SCRIPT_DIR/package-weasis.sh" "$DEST_DIR/build/script/package-weasis.sh" \
  && echo "  Copiado: package-weasis.sh -> build/script/"

# Renomear aplicação nos JSONs de configuração
_rename_app_in_conf() {
  local conf_dir="$DEST_DIR/bin-dist/weasis/conf"
  local py=""
  for c in /Library/Developer/CommandLineTools/Library/Frameworks/Python3.framework/Versions/3.9/bin/python3 python3 python; do
    command -v "$c" &>/dev/null 2>&1 && py="$c" && break
  done
  [ -z "$py" ] && echo "AVISO: Python não encontrado — nome do app não alterado nos JSONs." && return
  for f in base.json non-dicom-explorer.json dicomizer.json; do
    [ -f "$conf_dir/$f" ] || continue
    "$py" - "$conf_dir/$f" <<'PYEOF'
import json, sys
path = sys.argv[1]
with open(path) as fh: data = json.load(fh)
def update(obj):
    if isinstance(obj, dict):
        if obj.get('code') == 'weasis.name': obj['value'] = 'LabRoM_IML'
        for v in obj.values(): update(v)
    elif isinstance(obj, list):
        for v in obj: update(v)
update(data)
with open(path, 'w') as fh: json.dump(data, fh, indent=2, ensure_ascii=False)
PYEOF
    echo "  Nome atualizado: $f"
  done
}
_rename_app_in_conf

# Injetar imagens customizadas (ícone e splash)
_inject_images

# Renomear app nos arquivos de bundle gerados pelo jpackage
_patch_app_name() {
  local NEW_EXE="LabRoM-IML"
  for app in "$DEST_DIR/dist-output/"*.app; do
    [ -d "$app" ] || continue
    local plist="$app/Contents/Info.plist"

    # --- Renomear binário principal (MacOS/<old> → MacOS/LabRoM-IML) ---
    local old_exe
    old_exe=$(/usr/libexec/PlistBuddy -c "Print :CFBundleExecutable" "$plist" 2>/dev/null || \
              basename "$app" .app)
    if [ "$old_exe" != "$NEW_EXE" ] && [ -f "$app/Contents/MacOS/$old_exe" ]; then
      mv "$app/Contents/MacOS/$old_exe" "$app/Contents/MacOS/$NEW_EXE"
      /usr/libexec/PlistBuddy -c "Set :CFBundleExecutable $NEW_EXE" "$plist" 2>/dev/null || true
      echo "  Binário renomeado: MacOS/$NEW_EXE"
    fi

    # --- Renomear .cfg (app/<old>.cfg → app/LabRoM-IML.cfg) ---
    local cfg_old="$app/Contents/app/${old_exe}.cfg"
    local cfg_new="$app/Contents/app/${NEW_EXE}.cfg"
    [ -f "$cfg_old" ] && mv "$cfg_old" "$cfg_new"

    # --- Injeta -Dweasis.name no .cfg se ainda não estiver ---
    if [ -f "$cfg_new" ] && ! grep -q "weasis.name" "$cfg_new"; then
      sed -i '' 's/^java-options=--enable-native-access=ALL-UNNAMED/java-options=--enable-native-access=ALL-UNNAMED\njava-options=-Dweasis.name=LabRoM_IML/' "$cfg_new"
    fi
    echo "  Config: $cfg_new"

    # --- Atualiza CFBundleName e CFBundleDisplayName no Info.plist ---
    if [ -f "$plist" ]; then
      /usr/libexec/PlistBuddy -c "Set :CFBundleName LabRoM_IML" "$plist" 2>/dev/null || true
      /usr/libexec/PlistBuddy -c "Set :CFBundleDisplayName LabRoM_IML" "$plist" 2>/dev/null || \
        /usr/libexec/PlistBuddy -c "Add :CFBundleDisplayName string LabRoM_IML" "$plist" 2>/dev/null || true
    fi

    # --- Sincroniza PNGs de resources do bin-dist para dentro do .app ---
    local img_src="$DEST_DIR/bin-dist/weasis/resources/images"
    local img_dst="$app/Contents/app/resources/images"
    if [ -d "$img_src" ] && [ -d "$img_dst" ]; then
      cp -f "$img_src/logo-button.png"  "$img_dst/" 2>/dev/null || true
      cp -f "$img_src/about.png"        "$img_dst/" 2>/dev/null || true
      cp -f "$img_src/about-round.png"  "$img_dst/" 2>/dev/null || true
    fi

    # --- Re-assina o app após modificações ---
    chmod -R u+rw "$app" 2>/dev/null || true
    codesign --force --deep --sign - "$app" 2>/dev/null \
      && echo "  Assinado: $app" \
      || echo "  AVISO: codesign falhou para $app"
  done
}
_patch_app_name

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
java -Xdock:name="LabRoM_IML" \
     -Xdock:icon="$SCRIPT_DIR/assets/icon.png" \
     -Dweasis.name="LabRoM_IML" \
     -cp "weasis-launcher.jar:felix.jar" org.weasis.launcher.AppLauncher
