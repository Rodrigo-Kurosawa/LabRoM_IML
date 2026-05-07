#Requires -Version 5.1
# run_weasis.ps1 - Build e execucao do Weasis com o plugin LabRoM/IML no Windows.
#
# Uso:
#   .\run_weasis.ps1          # build completo + execucao
#   .\run_weasis.ps1 -Fast    # recompila so o plugin e reinicia
#
# Requisitos: Java 25+ (JAVA_HOME ou ~/Java/jdk-*), Maven 3.8+ no PATH ou ~/tools, Python 3.9+ (opcional)

param([switch]$Fast)

$SCRIPT_DIR  = $PSScriptRoot
$DEST_DIR    = Join-Path (Split-Path $SCRIPT_DIR -Parent) "weasis-native"
$BIN_DIR     = Join-Path $DEST_DIR "bin-dist\weasis"
$BUNDLE_DIR  = Join-Path $BIN_DIR "bundle"
$MODELS_SRC  = Join-Path $SCRIPT_DIR "weasis-sex-classifier\models"
$MODELS_DEST = Join-Path $BIN_DIR "models"

# ---------------------------------------------------------------------------
# Detectar JAVA_HOME
# ---------------------------------------------------------------------------
function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
        return $env:JAVA_HOME
    }
    $userJava = Join-Path $env:USERPROFILE "Java"
    if (Test-Path $userJava) {
        $jdk = Get-ChildItem $userJava -Directory -ErrorAction SilentlyContinue |
               Where-Object { Test-Path "$($_.FullName)\bin\java.exe" } |
               Sort-Object Name -Descending | Select-Object -First 1
        if ($jdk) { return $jdk.FullName }
    }
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd -and $javaCmd.Source -notlike "*WindowsApps*") {
        $candidate = Split-Path (Split-Path $javaCmd.Source -Parent) -Parent
        if (Test-Path "$candidate\bin\java.exe") { return $candidate }
    }
    return $null
}

$JAVA_HOME_FOUND = Find-JavaHome
if (-not $JAVA_HOME_FOUND) {
    Write-Host "ERRO: Java nao encontrado. Defina JAVA_HOME ou instale o JDK em ~/Java/jdk-*."
    exit 1
}
$javaVerLine = (& "$JAVA_HOME_FOUND\bin\java.exe" -version 2>&1) | Select-Object -First 1
if ($javaVerLine -match '"(\d+)[\._]') {
    $javaMajor = [int]$Matches[1]
    if ($javaMajor -lt 25) {
        Write-Host ("ERRO: Java " + $javaMajor + " detectado. Java 25+ e necessario.")
        exit 1
    }
}
$env:JAVA_HOME   = $JAVA_HOME_FOUND
$env:PATH        = "$JAVA_HOME_FOUND\bin;$env:PATH"
$env:MAVEN_OPTS  = "--enable-native-access=ALL-UNNAMED"
$javaVer = (& "$JAVA_HOME_FOUND\bin\java.exe" -version 2>&1) | Select-Object -First 1
Write-Host ("Usando Java: " + $javaVer + "  [JAVA_HOME=$JAVA_HOME_FOUND]")

# ---------------------------------------------------------------------------
# Detectar Maven
# ---------------------------------------------------------------------------
function Find-Maven {
    foreach ($cmd in @("mvn", "mvn.cmd")) {
        $m = Get-Command $cmd -ErrorAction SilentlyContinue
        if ($m) { return $m.Source }
    }
    foreach ($base in @("$env:USERPROFILE\tools", "$env:LOCALAPPDATA\Programs")) {
        if (Test-Path $base) {
            $found = Get-ChildItem $base -Recurse -Filter "mvn.cmd" -ErrorAction SilentlyContinue |
                     Sort-Object FullName | Select-Object -Last 1
            if ($found) { return $found.FullName }
        }
    }
    return $null
}

$MVN = Find-Maven
if (-not $MVN) {
    Write-Host "ERRO: Maven nao encontrado. Instale Maven 3.8+ e adicione ao PATH ou coloque em ~/tools/."
    exit 1
}
$mvnVer = (& $MVN --version 2>&1) | Where-Object { $_ -notmatch "^WARNING" } | Select-Object -First 1
Write-Host ("Usando Maven: " + $mvnVer)

# ---------------------------------------------------------------------------
# Detectar Python (evita stub do Microsoft Store)
# ---------------------------------------------------------------------------
function Find-Python {
    $knownPaths = @(
        "$env:LOCALAPPDATA\Programs\Python\Python313\python.exe",
        "$env:LOCALAPPDATA\Programs\Python\Python312\python.exe",
        "$env:LOCALAPPDATA\Programs\Python\Python311\python.exe",
        "$env:LOCALAPPDATA\Programs\Python\Python310\python.exe",
        "$env:LOCALAPPDATA\Programs\Python\Python39\python.exe"
    )
    foreach ($p in $knownPaths) {
        if (Test-Path $p) { return $p }
    }
    foreach ($c in @("python", "python3", "py")) {
        $found = Get-Command $c -ErrorAction SilentlyContinue
        if ($found -and $found.Source -notlike "*WindowsApps*") {
            $ver = (& $found.Source -c "import sys; print(sys.version_info.major*10+sys.version_info.minor)" 2>$null)
            if ($ver -match '^\d+$' -and [int]$ver -ge 39) { return $found.Source }
        }
    }
    return $null
}

# ---------------------------------------------------------------------------
# Redimensionar imagem com fundo preto (usa ImageMagick ou System.Drawing)
# ---------------------------------------------------------------------------
function Resize-ImageBlack {
    param([string]$Src, [string]$Dst, [int]$W, [int]$H)
    New-Item -ItemType Directory -Force -Path (Split-Path $Dst -Parent) | Out-Null
    $magick = Get-Command magick -ErrorAction SilentlyContinue
    if ($magick) {
        & magick $Src -resize "${W}x${H}" -background black -gravity center -extent "${W}x${H}" $Dst 2>$null
        Write-Host ("  [PNG " + $W + "x" + $H + "] -> " + $Dst)
        return
    }
    try {
        Add-Type -AssemblyName System.Drawing
        $img   = [System.Drawing.Image]::FromFile($Src)
        $bmp   = New-Object System.Drawing.Bitmap($W, $H)
        $g     = [System.Drawing.Graphics]::FromImage($bmp)
        $g.Clear([System.Drawing.Color]::Black)
        $scale = [Math]::Min($W / $img.Width, $H / $img.Height)
        $fw    = [int]($img.Width  * $scale)
        $fh    = [int]($img.Height * $scale)
        $x     = [int](($W - $fw) / 2)
        $y     = [int](($H - $fh) / 2)
        $g.DrawImage($img, $x, $y, $fw, $fh)
        $bmp.Save($Dst, [System.Drawing.Imaging.ImageFormat]::Png)
        $g.Dispose(); $bmp.Dispose(); $img.Dispose()
        Write-Host ("  [PNG " + $W + "x" + $H + "] -> " + $Dst)
    } catch {
        Write-Warning ("  Falha ao redimensionar imagem: " + $_)
    }
}

function Make-Ico {
    param([string]$Src, [string]$Dst)
    New-Item -ItemType Directory -Force -Path (Split-Path $Dst -Parent) | Out-Null
    $magick = Get-Command magick -ErrorAction SilentlyContinue
    if (-not $magick) {
        Write-Warning ("  ImageMagick nao encontrado - " + $Dst + " ignorado")
        return
    }
    & magick $Src -background black -gravity center `
        "(" -clone 0 -resize 16x16   -extent 16x16   ")" `
        "(" -clone 0 -resize 32x32   -extent 32x32   ")" `
        "(" -clone 0 -resize 48x48   -extent 48x48   ")" `
        "(" -clone 0 -resize 64x64   -extent 64x64   ")" `
        "(" -clone 0 -resize 128x128 -extent 128x128 ")" `
        "(" -clone 0 -resize 256x256 -extent 256x256 ")" `
        -delete 0 $Dst 2>$null
    Write-Host ("  [ICO] -> " + $Dst)
}

function Write-Utf8File {
    param([string]$Path, [string]$Content)
    $enc = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($Path, $Content, $enc)
}

function Inject-Images {
    $iconSrc   = Join-Path $SCRIPT_DIR "assets\icon.png"
    $splashSrc = Join-Path $SCRIPT_DIR "assets\splash.png"
    if (-not (Test-Path $iconSrc) -and -not (Test-Path $splashSrc)) { return }

    Write-Host "Injetando imagens customizadas..."

    if (Test-Path $iconSrc) {
        Resize-ImageBlack $iconSrc (Join-Path $DEST_DIR "build\script\resources\linux\Weasis.png")    64  64
        Resize-ImageBlack $iconSrc (Join-Path $DEST_DIR "build\script\resources\linux\Dicomizer.png") 64  64
        Make-Ico          $iconSrc (Join-Path $DEST_DIR "build\script\resources\windows\Weasis.ico")
        Make-Ico          $iconSrc (Join-Path $DEST_DIR "build\script\resources\windows\Dicomizer.ico")

        $iconB64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($iconSrc))
        $svgDir  = Join-Path $BIN_DIR "resources\svg\logo"
        New-Item -ItemType Directory -Force -Path $svgDir | Out-Null
        $svgContent = '<svg width="512" height="512" viewBox="0 0 512 512" xmlns="http://www.w3.org/2000/svg">' + "`n" +
                      '  <rect width="512" height="512" fill="#000000"/>' + "`n" +
                      '  <image href="data:image/png;base64,' + $iconB64 + '" x="0" y="0" width="512" height="512" preserveAspectRatio="xMidYMid meet"/>' + "`n" +
                      '</svg>'
        foreach ($svgName in @("Weasis.svg", "Dicomizer.svg")) {
            Write-Utf8File (Join-Path $svgDir $svgName) $svgContent
            Write-Host ("  [SVG icon] -> " + (Join-Path $svgDir $svgName))
        }
    }

    if (Test-Path $splashSrc) {
        Resize-ImageBlack $splashSrc (Join-Path $BIN_DIR "resources\images\about.png")       374 147
        Resize-ImageBlack $splashSrc (Join-Path $BIN_DIR "resources\images\about-round.png") 374 147
        Resize-ImageBlack $splashSrc (Join-Path $BIN_DIR "resources\images\logo-button.png") 140  44

        $splashB64  = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($splashSrc))
        $svgSplash  = Join-Path $BIN_DIR "resources\svg\logo"
        New-Item -ItemType Directory -Force -Path $svgSplash | Out-Null
        $svgContent2 = '<svg width="448" height="162" viewBox="0 0 448 162" xmlns="http://www.w3.org/2000/svg">' + "`n" +
                       '  <rect width="448" height="162" fill="#000000"/>' + "`n" +
                       '  <image href="data:image/png;base64,' + $splashB64 + '" x="0" y="0" width="448" height="162" preserveAspectRatio="xMidYMid meet"/>' + "`n" +
                       '</svg>'
        Write-Utf8File (Join-Path $svgSplash "WeasisAbout.svg") $svgContent2
        Write-Host ("  [SVG splash] -> " + (Join-Path $svgSplash "WeasisAbout.svg"))
    }
}

# ---------------------------------------------------------------------------
# Copiar modelos (.pt)
# ---------------------------------------------------------------------------
function Copy-Models {
    if (-not (Test-Path $MODELS_SRC)) {
        Write-Warning ("AVISO: pasta models/ nao encontrada em: " + $MODELS_SRC)
        return
    }
    if (-not (Test-Path $BIN_DIR)) { return }
    New-Item -ItemType Directory -Force -Path $MODELS_DEST | Out-Null
    $count = 0
    Get-ChildItem $MODELS_SRC -Filter "*.pt" -ErrorAction SilentlyContinue | ForEach-Object {
        Copy-Item $_.FullName $MODELS_DEST -Force
        $count++
    }
    if ($count -gt 0) {
        Write-Host ("Modelos copiados para " + $MODELS_DEST + "  (" + $count + " arquivo(s))")
    }
}

# ---------------------------------------------------------------------------
# Criar/verificar venv Python
# ---------------------------------------------------------------------------
function Setup-Python {
    $venvDir = Join-Path $env:USERPROFILE ".weasis\labrom-env"
    if (Test-Path "$venvDir\Scripts\python.exe") {
        Write-Host ("Venv Python OK: " + $venvDir + " - verificando dependencias...")
        & "$venvDir\Scripts\pip.exe" install --quiet pillow pydicom numpy ultralytics torch torchvision opencv-python grad-cam "pylibjpeg[all]"
        return
    }
    $pyExe = Find-Python
    if (-not $pyExe) {
        Write-Warning "AVISO: Python 3.9+ nao encontrado - o plugin de classificacao nao funcionara."
        Write-Warning "       Instale Python de https://www.python.org/downloads/ e execute novamente."
        return
    }
    Write-Host ("Criando ambiente Python em " + $venvDir + " (pode levar alguns minutos)...")
    New-Item -ItemType Directory -Force -Path (Split-Path $venvDir -Parent) | Out-Null
    & $pyExe -m venv $venvDir --clear
    if ($LASTEXITCODE -ne 0) { Write-Warning "Falha ao criar venv Python."; return }
    & "$venvDir\Scripts\pip.exe" install --upgrade pip --quiet
    & "$venvDir\Scripts\pip.exe" install ultralytics torch torchvision opencv-python pydicom pillow grad-cam "pylibjpeg[all]" --quiet
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Algumas dependencias Python nao foram instaladas. Verifique a conexao e tente novamente."
        return
    }
    Write-Host ("Ambiente Python configurado em: " + $venvDir)
}

# ---------------------------------------------------------------------------
# Renomear aplicacao nos JSONs de configuracao
# ---------------------------------------------------------------------------
function Rename-AppInConf {
    $confDir = Join-Path $BIN_DIR "conf"
    $pyExe   = Find-Python
    if (-not $pyExe) {
        Write-Warning "AVISO: Python nao encontrado - nome do app nao alterado nos JSONs."
        return
    }
    $pyLines = @(
        "import json, sys",
        "path = sys.argv[1]",
        "fh = open(path, encoding='utf-8')",
        "data = json.load(fh)",
        "fh.close()",
        "def update(obj):",
        "    if isinstance(obj, dict):",
        "        if obj.get('code') == 'weasis.name': obj['value'] = 'LabRoM_IML'",
        "        for v in obj.values(): update(v)",
        "    elif isinstance(obj, list):",
        "        for v in obj: update(v)",
        "update(data)",
        "fh2 = open(path, 'w', encoding='utf-8')",
        "json.dump(data, fh2, indent=2, ensure_ascii=False)",
        "fh2.close()"
    )
    $tmpPy = [System.IO.Path]::GetTempFileName() + ".py"
    Set-Content -Path $tmpPy -Value ($pyLines -join "`n") -Encoding UTF8
    try {
        foreach ($f in @("base.json", "non-dicom-explorer.json", "dicomizer.json")) {
            $jsonPath = Join-Path $confDir $f
            if (Test-Path $jsonPath) {
                & $pyExe $tmpPy $jsonPath
                Write-Host ("  Nome atualizado: " + $f)
            }
        }
    } finally {
        Remove-Item $tmpPy -ErrorAction SilentlyContinue
    }
}

# ---------------------------------------------------------------------------
# Modo -Fast: recompila so o plugin e reinicia
# ---------------------------------------------------------------------------
if ($Fast) {
    Write-Host "[FAST] Compilando plugin weasis-sex-classifier..."
    Push-Location $SCRIPT_DIR
    & $MVN install -pl weasis-sex-classifier -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        & $MVN install -pl weasis-sex-classifier -am -DskipTests -q
        if ($LASTEXITCODE -ne 0) {
            Pop-Location
            Write-Host "ERRO: Compilacao do plugin falhou."
            exit 1
        }
    }
    Pop-Location

    $JAR = Get-ChildItem (Join-Path $SCRIPT_DIR "weasis-sex-classifier\target") `
           -Filter "weasis-sex-classifier-*.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch "sources" } | Select-Object -First 1
    if (-not $JAR) { Write-Host "ERRO: JAR nao encontrado apos compilacao."; exit 1 }

    if (-not (Test-Path $BUNDLE_DIR)) {
        Write-Host ("ERRO: " + $BUNDLE_DIR + " nao encontrado. Execute .\run_weasis.ps1 sem -Fast primeiro.")
        exit 1
    }

    Copy-Item $JAR.FullName $BUNDLE_DIR -Force
    Write-Host ("[FAST] Plugin injetado: " + $JAR.Name)

    Copy-Models
    Setup-Python

    Remove-Item -Recurse -Force (Join-Path $env:USERPROFILE ".weasis\cache") -ErrorAction SilentlyContinue
    Write-Host "[FAST] Cache OSGi limpo. Iniciando Weasis..."
    Push-Location $BIN_DIR
    & java "-Dweasis.name=LabRoM_IML" "-cp" "weasis-launcher.jar;felix.jar" org.weasis.launcher.AppLauncher
    Pop-Location
    exit 0
}

# ---------------------------------------------------------------------------
# Build completo
# ---------------------------------------------------------------------------
Write-Host "Iniciando build do Weasis..."

Get-Process -Name "java" -ErrorAction SilentlyContinue |
    Where-Object { $_.MainWindowTitle -match "Weasis|LabRoM" } |
    Stop-Process -Force -ErrorAction SilentlyContinue

Push-Location $SCRIPT_DIR
& $MVN clean install -DskipTests
if ($LASTEXITCODE -ne 0) {
    Pop-Location
    Write-Host "ERRO: Build Maven falhou (mvn clean install)."
    exit 1
}
Pop-Location

Write-Host "Build concluido. Empacotando distribuicao..."

Push-Location (Join-Path $SCRIPT_DIR "weasis-distributions")
& $MVN package -DskipTests
if ($LASTEXITCODE -ne 0) {
    Pop-Location
    Write-Host "ERRO: Packaging Maven falhou (mvn package)."
    exit 1
}
$ZIP_FILE = Get-ChildItem "." -Filter "weasis-native*.zip" -Recurse -ErrorAction SilentlyContinue |
            Select-Object -First 1
Pop-Location

if (-not $ZIP_FILE) {
    Write-Host "ERRO: ZIP do weasis-native nao encontrado em weasis-distributions/!"
    exit 1
}
Write-Host ("Encontrado: " + $ZIP_FILE.FullName)

if (Test-Path $DEST_DIR) { Remove-Item -Recurse -Force $DEST_DIR }
New-Item -ItemType Directory -Force -Path $DEST_DIR | Out-Null
Expand-Archive $ZIP_FILE.FullName -DestinationPath $DEST_DIR -Force
Write-Host ("Extraido para " + $DEST_DIR)

foreach ($f in @("distribute.sh", "distribute.ps1", "package-weasis.sh")) {
    $src = Join-Path $SCRIPT_DIR $f
    if (Test-Path $src) {
        if ($f -eq "package-weasis.sh") {
            Copy-Item $src (Join-Path $DEST_DIR "build\script\$f") -Force
        } else {
            Copy-Item $src $DEST_DIR -Force
        }
        Write-Host ("  Copiado: " + $f)
    }
}

Rename-AppInConf
Inject-Images

Write-Host "Injetando plugins customizados..."
$pluginTarget = Join-Path $SCRIPT_DIR "weasis-sex-classifier\target"
if (Test-Path $pluginTarget) {
    Get-ChildItem $pluginTarget -Filter "*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch "sources" } |
        ForEach-Object {
            if (Test-Path $BUNDLE_DIR) {
                Copy-Item $_.FullName $BUNDLE_DIR -Force
                Write-Host ("  Injetado: " + $_.Name)
            }
        }
}

Copy-Models
Setup-Python

if (-not (Test-Path $BIN_DIR)) {
    Write-Host ("ERRO: " + $BIN_DIR + " nao encontrado apos extracao.")
    exit 1
}

Write-Host "Iniciando Weasis..."
Push-Location $BIN_DIR
& java "-Dweasis.name=LabRoM_IML" "-cp" "weasis-launcher.jar;felix.jar" org.weasis.launcher.AppLauncher
Pop-Location
