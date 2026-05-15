#Requires -Version 5.1
# distribute.ps1 - Gera o instalador MSI do Weasis/LabRoM_IML no Windows.
#
# Uso:
#   .\distribute.ps1 [opcoes]
#
# Opcoes:
#   -Jdk <path>     Caminho para um JDK 25+ com jpackage  (padrao: auto-detect)
#   -Output <path>  Pasta de saida dos instaladores        (padrao: .\dist-output)
#   -NoPackage      Gera apenas a imagem de app, sem instalador MSI
#
# Pre-requisitos:
#   - JDK >= 25 com jpackage  (detectado automaticamente via JAVA_HOME ou PATH)
#   - WiX Toolset >= 4:  winget install WiXToolset.WiXToolset
#     ou:                choco install wixtoolset

param(
    [string]$Jdk      = "",
    [string]$Output   = "",
    [switch]$NoPackage
)

$SCRIPT_DIR   = $PSScriptRoot
$APP_NAME     = "LabRoM_IML"

# distribute.ps1 pode ser executado de LabRoM_IML/ ou de weasis-native/.
# Detecta automaticamente qual dos dois tem bin-dist.
$BIN_DIST = Join-Path $SCRIPT_DIR "bin-dist"
if (-not (Test-Path $BIN_DIST)) {
    $alt = Join-Path (Split-Path $SCRIPT_DIR -Parent) "weasis-native"
    if (Test-Path (Join-Path $alt "bin-dist")) {
        $SCRIPT_DIR = $alt
        $BIN_DIST   = Join-Path $SCRIPT_DIR "bin-dist"
        Write-Host ("[INFO]  Usando weasis-native em: " + $SCRIPT_DIR)
    }
}

$BUILD_PROPS  = Join-Path $SCRIPT_DIR "build\script\build.properties"

# Validar pre-requisitos
if (-not (Test-Path $BIN_DIST)) {
    Write-Host ("ERRO: bin-dist nao encontrado em: " + $BIN_DIST)
    Write-Host "Execute run_weasis.ps1 primeiro para gerar o bin-dist."
    exit 1
}
if (-not (Test-Path $BUILD_PROPS)) {
    Write-Host ("ERRO: build.properties nao encontrado em: " + $BUILD_PROPS)
    exit 1
}

# ---------------------------------------------------------------------------
# Auto-detectar JDK com jpackage
# ---------------------------------------------------------------------------
if (-not $Jdk) {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) {
        $Jdk = $env:JAVA_HOME
    }
    if (-not $Jdk) {
        $userJava = Join-Path $env:USERPROFILE "Java"
        if (Test-Path $userJava) {
            $found = Get-ChildItem $userJava -Directory -ErrorAction SilentlyContinue |
                     Where-Object { Test-Path "$($_.FullName)\bin\jpackage.exe" } |
                     Sort-Object Name -Descending | Select-Object -First 1
            if ($found) { $Jdk = $found.FullName }
        }
    }
    if (-not $Jdk) {
        $javaCmd = Get-Command java -ErrorAction SilentlyContinue
        if ($javaCmd -and $javaCmd.Source -notlike "*WindowsApps*") {
            $candidate = Split-Path (Split-Path $javaCmd.Source -Parent) -Parent
            if (Test-Path "$candidate\bin\jpackage.exe") { $Jdk = $candidate }
        }
    }
}

if (-not $Jdk -or -not (Test-Path "$Jdk\bin\jpackage.exe")) {
    Write-Host "ERRO: jpackage nao encontrado. Forneca o caminho com -Jdk <path_do_jdk25>"
    exit 1
}
Write-Host ("[OK]    JDK: " + $Jdk)

$JPACKAGE = Join-Path $Jdk "bin\jpackage.exe"
$JAVA     = Join-Path $Jdk "bin\java.exe"

if (-not $Output) { $Output = Join-Path $SCRIPT_DIR "dist-output" }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
Write-Host ("[INFO]  Saida: " + $Output)

# ---------------------------------------------------------------------------
# Ler versao
# ---------------------------------------------------------------------------
$WEASIS_VERSION = (Get-Content $BUILD_PROPS |
    Where-Object { $_ -match "^weasis\.version=" }) -replace "^weasis\.version=", ""
Write-Host ("[INFO]  Versao Weasis: " + $WEASIS_VERSION)

# ---------------------------------------------------------------------------
# LabRoM setup: compilar JAR + copiar modelos
# ---------------------------------------------------------------------------
$SEX_CLASSIFIER_DIR = Join-Path $SCRIPT_DIR "weasis-sex-classifier"
if (-not (Test-Path $SEX_CLASSIFIER_DIR)) {
    $SEX_CLASSIFIER_DIR = Join-Path (Split-Path $SCRIPT_DIR -Parent) "LabRoM_IML\weasis-sex-classifier"
}

if (Test-Path $SEX_CLASSIFIER_DIR) {
    Write-Host ""
    Write-Host "-- LabRoM/IML: preparando plugin Sex Classifier --"

    $bundleDir = Join-Path $BIN_DIST "weasis\bundle"

    $jar = Get-ChildItem (Join-Path $SEX_CLASSIFIER_DIR "target") `
           -Filter "weasis-sex-classifier-*.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch "sources" } | Select-Object -First 1
    if (-not $jar) {
        Write-Host "[INFO]  JAR nao encontrado - compilando com mvn package..."
        $mvnCmd = $null
        foreach ($c in @("mvn", "mvn.cmd")) {
            $m = Get-Command $c -ErrorAction SilentlyContinue
            if ($m) { $mvnCmd = $m.Source; break }
        }
        if ($mvnCmd) {
            Push-Location $SEX_CLASSIFIER_DIR
            & $mvnCmd package -DskipTests -q
            Pop-Location
            $jar = Get-ChildItem (Join-Path $SEX_CLASSIFIER_DIR "target") `
                   -Filter "weasis-sex-classifier-*.jar" -ErrorAction SilentlyContinue |
                   Where-Object { $_.Name -notmatch "sources" } | Select-Object -First 1
        }
    }
    if ($jar) {
        Copy-Item $jar.FullName $bundleDir -Force
        Write-Host ("[OK]    Plugin JAR -> bundle\  (" + $jar.Name + ")")
    } else {
        Write-Warning "JAR nao encontrado. Certifique-se de ter executado run_weasis.ps1 primeiro."
    }

    $modelsSrc = Join-Path $SEX_CLASSIFIER_DIR "models"
    if (Test-Path $modelsSrc) {
        $modelsDst = Join-Path $BIN_DIST "weasis\models"
        New-Item -ItemType Directory -Force -Path $modelsDst | Out-Null
        Get-ChildItem $modelsSrc -Filter "*.pt" | ForEach-Object {
            Copy-Item $_.FullName $modelsDst -Force
        }
        $n = (Get-ChildItem $modelsDst -Filter "*.pt" -ErrorAction SilentlyContinue).Count
        Write-Host ("[OK]    Modelos (.pt) -> weasis\models\  (" + $n + " arquivo(s))")
    } else {
        Write-Warning ("Pasta models/ nao encontrada em: " + $modelsSrc)
    }

    $residualVenv = Join-Path $BIN_DIST "weasis\python-env"
    if (Test-Path $residualVenv) {
        Remove-Item -Recurse -Force $residualVenv
        Write-Host "[INFO]  python-env residual removido (sera instalado pos-build)"
    }
} else {
    Write-Warning ("Diretorio weasis-sex-classifier nao encontrado - pulando fase LabRoM.")
}

# ---------------------------------------------------------------------------
# Detectar arquitetura via weasis-core-img.jar
# ---------------------------------------------------------------------------
$coreImgGlob = Get-ChildItem (Join-Path $BIN_DIST "weasis\bundle") `
    -Filter "weasis-core-img-*" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $coreImgGlob) {
    Write-Host "ERRO: weasis-core-img JAR nao encontrado em bin-dist\weasis\bundle\"
    exit 1
}

$tmpJar = Join-Path $SCRIPT_DIR "_weasis-core-img-tmp.jar"
if ($coreImgGlob.Extension -eq ".xz") {
    $bash = Get-Command bash -ErrorAction SilentlyContinue
    $sz   = Get-Command 7z  -ErrorAction SilentlyContinue
    if ($bash) {
        $unixSrc = $coreImgGlob.FullName.Replace('\', '/')
        $unixDst = $tmpJar.Replace('\', '/')
        & bash -c ("cp `"" + $unixSrc + "`" `"" + $unixDst + ".xz`" && xz -d `"" + $unixDst + ".xz`"")
    } elseif ($sz) {
        & 7z e $coreImgGlob.FullName ("-o" + (Split-Path $tmpJar -Parent)) -y | Out-Null
        $extracted = Get-ChildItem (Split-Path $tmpJar -Parent) -Filter "weasis-core-img-*.jar" |
                     Select-Object -First 1
        if ($extracted) { Rename-Item $extracted.FullName $tmpJar -Force }
    } else {
        Write-Host "ERRO: 7-Zip ou Git Bash necessario para descompactar .xz"
        Write-Host "Instale Git for Windows ou 7-Zip e tente novamente."
        exit 1
    }
} else {
    Copy-Item $coreImgGlob.FullName $tmpJar -Force
}

if (-not (Test-Path $tmpJar)) {
    Write-Host "ERRO: Falha ao preparar weasis-core-img.jar para deteccao de arquitetura."
    exit 1
}

$ARC_OS = (& $JAVA -cp $tmpJar org.weasis.opencv.natives.NativeLibrary 2>$null)
if (-not $ARC_OS) {
    $ARC_OS = (& $JAVA -cp $tmpJar org.weasis.core.util.NativeLibrary 2>$null)
}
Remove-Item $tmpJar -ErrorAction SilentlyContinue

if (-not $ARC_OS) {
    Write-Host "ERRO: Nao foi possivel detectar a arquitetura do sistema."
    exit 1
}
$machine = $ARC_OS.Split("-")[0]
$arc     = ($ARC_OS.Split("-") | Select-Object -Skip 1) -join "-"
Write-Host ("[INFO]  Plataforma: " + $ARC_OS + "  (machine=" + $machine + ", arc=" + $arc + ")")

if ($machine -ne "windows") {
    Write-Host ("ERRO: Este script so gera instaladores Windows. Plataforma detectada: " + $machine)
    exit 1
}

# ---------------------------------------------------------------------------
# Aplicar main.wxs customizado (sem wixhelper.dll) sobre o weasis-native
# O weasis-native nao e um repositorio git e pode ser sobrescrito pelo
# run_weasis.ps1 — guardamos os arquivos corrigidos dentro do LabRoM_IML.
# ---------------------------------------------------------------------------
# Se o script foi copiado para weasis-native pelo run_weasis.ps1, PSScriptRoot
# aponta para weasis-native. Procura LabRoM_IML no diretorio irma­o.
$labromRoot = if (Test-Path (Join-Path $PSScriptRoot "assets")) {
    $PSScriptRoot
} else {
    $candidate = Join-Path (Split-Path $PSScriptRoot -Parent) "LabRoM_IML"
    if (Test-Path $candidate) { $candidate } else { $PSScriptRoot }
}
foreach ($arc_ in @("x86-64", "aarch64")) {
    $src_ = Join-Path $labromRoot "build\msi\$arc_\main.wxs"
    $dst_ = Join-Path $SCRIPT_DIR "build\script\resources\windows\msi\$arc_\main.wxs"
    if ((Test-Path $src_) -and (Test-Path (Split-Path $dst_ -Parent))) {
        Copy-Item $src_ $dst_ -Force
        Write-Host ("[OK]    main.wxs ($arc_) aplicado.")
    }
}

# ---------------------------------------------------------------------------
# Configurar jpackage
# ---------------------------------------------------------------------------
$RES        = Join-Path $SCRIPT_DIR "build\script\resources\windows"
$INPUT_DIR  = Join-Path $BIN_DIST "weasis"
$IMAGE_PATH = Join-Path $Output $APP_NAME

# ---------------------------------------------------------------------------
# Injetar icone customizado (assets\icon.png -> $APP_NAME.ico)
# jpackage usa automaticamente o .ico cujo nome coincide com --name no resource-dir.
# ---------------------------------------------------------------------------
$icoPath = Join-Path $RES "$APP_NAME.ico"
$iconSrc = Join-Path $labromRoot "assets\icon.png"
if (Test-Path $iconSrc) {
    $magick = Get-Command magick -ErrorAction SilentlyContinue
    if (-not $magick) {
        $magickFallback = "C:\Program Files\ImageMagick-7.1.2-Q16-HDRI\magick.exe"
        if (Test-Path $magickFallback) { $magick = $magickFallback }
    }
    if ($magick) {
        Write-Host "-- Gerando icone customizado ($APP_NAME.ico) --"
        & $magick $iconSrc -background black -gravity center `
            "(" -clone 0 -resize 16x16   -extent 16x16   ")" `
            "(" -clone 0 -resize 32x32   -extent 32x32   ")" `
            "(" -clone 0 -resize 48x48   -extent 48x48   ")" `
            "(" -clone 0 -resize 64x64   -extent 64x64   ")" `
            "(" -clone 0 -resize 128x128 -extent 128x128 ")" `
            "(" -clone 0 -resize 256x256 -extent 256x256 ")" `
            -delete 0 $icoPath 2>$null
        Write-Host ("[OK]    Icone -> " + (Split-Path $icoPath -Leaf))
    } else {
        Write-Warning "[WARN]  ImageMagick nao encontrado -- icone customizado nao gerado."
        Write-Warning "        Instale: winget install ImageMagick.ImageMagick"
        if (Test-Path (Join-Path $RES "Weasis.ico")) {
            Copy-Item (Join-Path $RES "Weasis.ico") $icoPath -Force
        }
    }
} elseif (Test-Path (Join-Path $RES "Weasis.ico")) {
    Copy-Item (Join-Path $RES "Weasis.ico") $icoPath -Force
    Write-Host "[INFO]  assets\icon.png nao encontrado - usando Weasis.ico como fallback."
}

$CLEAN_VERSION = $WEASIS_VERSION -replace '-[^.]*$', '' -replace '(\d+\.\d+\.\d+)\.\d+', '$1'

$JDK_MODULES = "java.base,java.compiler,java.datatransfer,java.net.http,java.desktop," +
               "java.logging,java.management,java.prefs,java.xml,jdk.localedata," +
               "jdk.charsets,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jdwp.agent," +
               "java.sql,jdk.crypto.mscapi"

if (Test-Path $Output) { Remove-Item -Recurse -Force $Output }

Write-Host ""
Write-Host "-- Gerando imagem do app com jpackage --"

& $JPACKAGE `
    --type app-image `
    --input  $INPUT_DIR `
    --dest   $Output `
    --name   $APP_NAME `
    --main-jar weasis-launcher.jar `
    --main-class org.weasis.launcher.AppLauncher `
    --add-modules $JDK_MODULES `
    --resource-dir $RES `
    --app-version $CLEAN_VERSION `
    --java-options "-Dgosh.port=17179" `
    --java-options "--enable-native-access=ALL-UNNAMED" `
    --java-options "-Djavax.accessibility.assistive_technologies=org.weasis.launcher.EmptyAccessibilityProvider" `
    --java-options "-Djavax.accessibility.screen_magnifier_present=false" `
    --java-options "-splash:`$APPDIR\resources\images\about-round.png" `
    --verbose

if ($LASTEXITCODE -ne 0) {
    Write-Host ("ERRO: jpackage app-image falhou (exit " + $LASTEXITCODE + ").")
    exit 1
}
Write-Host ("[OK]    Imagem do app gerada em: " + $IMAGE_PATH)

# ---------------------------------------------------------------------------
# Instalar Python embeddable dentro do app (pos-jpackage)
# Python embeddable e um runtime standalone - nao precisa de Python instalado
# no PC do usuario final.
# ---------------------------------------------------------------------------
$appDir = Join-Path $IMAGE_PATH "app"
if (Test-Path $appDir) {
    Write-Host ""
    Write-Host "-- Instalando Python embeddable (runtime standalone) --"

    # Cache persistente fora do dist-output (que e deletado a cada build).
    # O install de torch/ultralytics leva 15-30 min — so roda na primeira vez.
    $runtimeCache = Join-Path $env:LOCALAPPDATA "LabRoM\python-runtime"
    $runtimeDir   = Join-Path $appDir "python-runtime"
    $getPipPy     = Join-Path $env:TEMP "get-pip.py"

    # Verificar cache persistente
    $pyExeCache = Join-Path $runtimeCache "python.exe"
    $pipCache   = Join-Path $runtimeCache "Scripts\pip.exe"
    $torchOk = $false
    if ((Test-Path $pyExeCache) -and (Test-Path $pipCache)) {
        & $pyExeCache -c "import torch" 2>$null
        $torchOk = ($LASTEXITCODE -eq 0)
    }

    if ($torchOk) {
        Write-Host ("[OK]    Python cache encontrado - copiando para app (rapido)...")
        if (Test-Path $runtimeDir) { Remove-Item -Recurse -Force $runtimeDir }
        Copy-Item $runtimeCache $runtimeDir -Recurse -Force
    } else {
        # Descobrir a ultima versao 3.12.x disponivel no python.org
        $embedZip = $null
        for ($patch = 20; $patch -ge 0; $patch--) {
            $ver = "3.12.$patch"
            $zip = Join-Path $env:TEMP "python-$ver-embed-amd64.zip"
            if (Test-Path $zip) { $embedZip = $zip; break }
            $url = "https://www.python.org/ftp/python/$ver/python-$ver-embed-amd64.zip"
            try {
                Invoke-WebRequest $url -OutFile $zip -UseBasicParsing -ErrorAction Stop
                $embedZip = $zip
                Write-Host "[INFO]  Python $ver embeddable baixado."
                break
            } catch { Remove-Item $zip -ErrorAction SilentlyContinue }
        }
        if (-not $embedZip) {
            Write-Warning "[WARN]  Nao foi possivel baixar o Python embeddable. Verifique a conexao."
        } else {
            if (Test-Path $runtimeCache) { Remove-Item -Recurse -Force $runtimeCache }
            New-Item -ItemType Directory -Force -Path $runtimeCache | Out-Null
            Expand-Archive $embedZip $runtimeCache -Force

            # Habilitar site-packages descomentando 'import site' no ._pth
            # IMPORTANTE: usar WriteAllLines sem BOM — Set-Content UTF8 adiciona BOM que corrompe o path
            $pthFile = Get-ChildItem $runtimeCache -Filter "python*._pth" | Select-Object -First 1
            if ($pthFile) {
                $pthLines = (Get-Content $pthFile.FullName) -replace '^#import site', 'import site'
                [System.IO.File]::WriteAllLines($pthFile.FullName, $pthLines,
                    [System.Text.UTF8Encoding]::new($false))
            }

            # Bootstrap pip
            if (-not (Test-Path $getPipPy)) {
                Write-Host "[INFO]  Baixando get-pip.py..."
                Invoke-WebRequest "https://bootstrap.pypa.io/get-pip.py" `
                    -OutFile $getPipPy -UseBasicParsing
            }
            & $pyExeCache $getPipPy --no-warn-script-location -q
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "[WARN]  Falha ao instalar pip no Python embeddable."
            }

            # Python embeddable nao inclui setuptools/wheel — necessarios para
            # pacotes que compilam extensoes (ex: pylibjpeg).
            & $pipCache install --no-warn-script-location setuptools wheel -q

            # Instalar dependencias Python no cache
            Write-Host "[INFO]  Instalando dependencias Python (pode demorar alguns minutos)..."
            & $pipCache install --no-warn-script-location `
                pillow pydicom "pylibjpeg[all]" `
                ultralytics torch torchvision opencv-python grad-cam
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "[WARN]  Algumas dependencias nao foram instaladas. Verifique a conexao."
            } else {
                Write-Host ("[OK]    Python runtime instalado em cache: " + $runtimeCache)
                if (Test-Path $runtimeDir) { Remove-Item -Recurse -Force $runtimeDir }
                Copy-Item $runtimeCache $runtimeDir -Recurse -Force
                Write-Host ("[OK]    Python runtime copiado para app: " + $runtimeDir)
            }
        }
    }
}

# ---------------------------------------------------------------------------
# Gerar instalador MSI
# ---------------------------------------------------------------------------
if (-not $NoPackage) {
    Write-Host ""
    Write-Host "-- Gerando instalador MSI --"

    # Verificar WiX 4+ (necessario para gerar MSI com JDK 26+)
    # WiX 3 (candle.exe/light.exe) e INCOMPATIVEL com JDK 26 — nao adicionar ao PATH.
    # Apenas wix.exe (WiX 4+, instalado via dotnet tool) e suportado.
    $env:PATH = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" +
                [System.Environment]::GetEnvironmentVariable("Path","User") + ";" +
                "$env:USERPROFILE\.dotnet\tools;" +
                $env:PATH
    $wixFound = [bool](Get-Command "wix.exe" -ErrorAction SilentlyContinue)
    if (-not $wixFound) {
        Write-Host "[INFO]  wix.exe nao encontrado. Instalando WiX 4.0.5 via dotnet tool (nao requer admin)..."
        dotnet tool install --global wix --version 4.0.5
        $env:PATH = "$env:USERPROFILE\.dotnet\tools;$env:PATH"
        $wixFound = [bool](Get-Command "wix.exe" -ErrorAction SilentlyContinue)
    }
    if (-not $wixFound) {
        Write-Host "ERRO: wix.exe nao encontrado. Instale o .NET SDK 8+ e execute:"
        Write-Host "        dotnet tool install --global wix --version 4.0.5"
        Write-Host "      Depois reinicie o PowerShell e execute .\distribute.ps1 novamente."
        exit 1
    }
    $wixVer = (& wix.exe --version 2>$null)
    Write-Host ("[OK]    WiX: " + $wixVer)

    # Garantir que as extensoes WiX necessarias estao instaladas
    $extList = (& wix.exe extension list --global 2>$null) | Out-String
    foreach ($ext in @("WixToolset.Util.wixext", "WixToolset.UI.wixext")) {
        $ver405 = $ext + "/4.0.5"
        if ($extList -notmatch [regex]::Escape($ext + " 4.0.5")) {
            Write-Host ("[INFO]  Instalando extensao WiX: " + $ext + " ...")
            & wix.exe extension remove --global $ext 2>$null
            & wix.exe extension add --global ($ext + "/4.0.5")
            if ($LASTEXITCODE -ne 0) {
                Write-Host ("ERRO: Falha ao instalar extensao WiX '" + $ext + "'.")
                exit 1
            }
        }
    }

    $UPGRADE_UID = if ($arc -eq "aarch64") {
        "3aedc24e-48a8-4623-ab39-0c3c01c7383c"
    } else {
        "3aedc24e-48a8-4623-ab39-0c3c01c7383a"
    }

    $licenseFile = Join-Path $BIN_DIST "Licence.txt"
    $msiResDir   = Join-Path $RES "msi\$arc"
    $fileAssoc   = Join-Path $SCRIPT_DIR "build\script\file-associations.properties"

    & $JPACKAGE `
        --type msi `
        --app-image $IMAGE_PATH `
        --dest $Output `
        --name $APP_NAME `
        --resource-dir $msiResDir `
        --license-file $licenseFile `
        --description "$APP_NAME DICOM viewer" `
        --win-upgrade-uuid $UPGRADE_UID `
        --win-shortcut `
        --win-menu `
        --win-menu-group $APP_NAME `
        --copyright "2009-2026 Weasis Team" `
        --app-version $CLEAN_VERSION `
        --vendor "Weasis Team" `
        --file-associations $fileAssoc `
        --verbose

    if ($LASTEXITCODE -ne 0) {
        Write-Host ("ERRO: jpackage MSI falhou (exit " + $LASTEXITCODE + ").")
        exit 1
    }

    $msi = Get-ChildItem $Output -Filter "$APP_NAME-*.msi" | Select-Object -First 1
    if ($msi) {
        $newName = $msi.Name -replace "\.msi$", ("-" + $arc + ".msi")
        Rename-Item $msi.FullName (Join-Path $Output $newName)
        Write-Host ("[OK]    Instalador MSI: " + $newName)
    }
}

Write-Host ""
Write-Host ("[OK]    Concluido! Artefatos em: " + $Output)
Get-ChildItem $Output -ErrorAction SilentlyContinue | Format-Table Name, Length, LastWriteTime
