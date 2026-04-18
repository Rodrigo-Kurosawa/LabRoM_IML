#Requires -Version 5.1
# distribute.ps1 — Gera o instalador MSI do Weasis/LabRoM_IML no Windows.
#
# Uso:
#   .\distribute.ps1 [opcoes]
#
# Opcoes:
#   -Jdk <path>     Caminho para um JDK 25 com jpackage  (padrao: auto-detect)
#   -Output <path>  Pasta de saida dos instaladores      (padrao: .\dist-output)
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

$ErrorActionPreference = "Stop"

$SCRIPT_DIR   = $PSScriptRoot
$BIN_DIST     = Join-Path $SCRIPT_DIR "bin-dist"
$BUILD_PROPS  = Join-Path $SCRIPT_DIR "build\script\build.properties"

# Validar pré-requisitos
if (-not (Test-Path $BIN_DIST))   { Write-Error "bin-dist nao encontrado em: $BIN_DIST`nExecute run_weasis.ps1 primeiro para gerar o bin-dist."; exit 1 }
if (-not (Test-Path $BUILD_PROPS)){ Write-Error "build.properties nao encontrado em: $BUILD_PROPS"; exit 1 }

# ---------------------------------------------------------------------------
# Auto-detectar JDK
# ---------------------------------------------------------------------------
if (-not $Jdk) {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) {
        $Jdk = $env:JAVA_HOME
    } else {
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
        if ($javaCmd) {
            $candidate = Split-Path (Split-Path $javaCmd.Source -Parent) -Parent
            if (Test-Path "$candidate\bin\jpackage.exe") { $Jdk = $candidate }
        }
    }
}
if (-not $Jdk -or -not (Test-Path "$Jdk\bin\jpackage.exe")) {
    Write-Error "jpackage nao encontrado. Forneca o caminho com -Jdk <path_do_jdk25>"
    exit 1
}
Write-Host "[OK]    JDK: $Jdk"

$JPACKAGE = Join-Path $Jdk "bin\jpackage.exe"
$JAVA     = Join-Path $Jdk "bin\java.exe"

if (-not $Output) { $Output = Join-Path $SCRIPT_DIR "dist-output" }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
Write-Host "[INFO]  Saida: $Output"

# ---------------------------------------------------------------------------
# Ler versão
# ---------------------------------------------------------------------------
$WEASIS_VERSION = (Get-Content $BUILD_PROPS |
    Where-Object { $_ -match "^weasis\.version=" }) -replace "^weasis\.version=", ""
Write-Host "[INFO]  Versao Weasis: $WEASIS_VERSION"

# ---------------------------------------------------------------------------
# LabRoM setup: compilar JAR + copiar modelos
# ---------------------------------------------------------------------------
$SEX_CLASSIFIER_DIR = Join-Path $SCRIPT_DIR "weasis-sex-classifier"
# Fallback: quando distribute.ps1 é executado de dentro do weasis-native extraído
if (-not (Test-Path $SEX_CLASSIFIER_DIR)) {
    $SEX_CLASSIFIER_DIR = Join-Path (Split-Path $SCRIPT_DIR -Parent) "LabRoM_IML\weasis-sex-classifier"
}

if (Test-Path $SEX_CLASSIFIER_DIR) {
    Write-Host "`n── LabRoM/IML — preparando plugin Sex Classifier ──"

    $bundleDir = Join-Path $BIN_DIST "weasis\bundle"

    # Compilar JAR se não existir
    $jar = Get-ChildItem (Join-Path $SEX_CLASSIFIER_DIR "target") `
           -Filter "weasis-sex-classifier-*.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch "sources" } | Select-Object -First 1
    if (-not $jar) {
        Write-Host "[INFO]  JAR nao encontrado — compilando com mvn package..."
        $mvn = Get-Command mvn -ErrorAction SilentlyContinue
        if (-not $mvn) { $mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue }
        if ($mvn) {
            Push-Location $SEX_CLASSIFIER_DIR
            & $mvn.Source package -DskipTests -q
            Pop-Location
            $jar = Get-ChildItem (Join-Path $SEX_CLASSIFIER_DIR "target") `
                   -Filter "weasis-sex-classifier-*.jar" -ErrorAction SilentlyContinue |
                   Where-Object { $_.Name -notmatch "sources" } | Select-Object -First 1
        }
    }
    if ($jar) {
        Copy-Item $jar.FullName $bundleDir -Force
        Write-Host "[OK]    Plugin JAR -> bundle\  ($($jar.Name))"
    } else {
        Write-Warning "JAR nao encontrado. Certifique-se de ter executado run_weasis.ps1 primeiro."
    }

    # Copiar modelos (.pt)
    $modelsSrc = Join-Path $SEX_CLASSIFIER_DIR "models"
    if (Test-Path $modelsSrc) {
        $modelsDst = Join-Path $BIN_DIST "weasis\models"
        New-Item -ItemType Directory -Force -Path $modelsDst | Out-Null
        Get-ChildItem $modelsSrc -Filter "*.pt" | ForEach-Object {
            Copy-Item $_.FullName $modelsDst -Force
        }
        $n = (Get-ChildItem $modelsDst -Filter "*.pt" -ErrorAction SilentlyContinue).Count
        Write-Host "[OK]    Modelos (.pt) -> weasis\models\  ($n arquivo(s))"
    } else {
        Write-Warning "Pasta models/ nao encontrada em: $modelsSrc"
    }

    # Remover python-env residual do input do jpackage (será instalado pós-build)
    $residualVenv = Join-Path $BIN_DIST "weasis\python-env"
    if (Test-Path $residualVenv) {
        Remove-Item -Recurse -Force $residualVenv
        Write-Host "[INFO]  python-env residual removido (sera instalado pos-build)"
    }
} else {
    Write-Warning "Diretorio weasis-sex-classifier nao encontrado — pulando fase LabRoM."
}

# ---------------------------------------------------------------------------
# Detectar arquitetura via weasis-core-img.jar
# ---------------------------------------------------------------------------
$coreImgGlob = Get-ChildItem (Join-Path $BIN_DIST "weasis\bundle") `
    -Filter "weasis-core-img-*" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $coreImgGlob) {
    Write-Error "weasis-core-img JAR nao encontrado em bin-dist\weasis\bundle\"
    exit 1
}

$tmpJar = Join-Path $SCRIPT_DIR "_weasis-core-img-tmp.jar"
if ($coreImgGlob.Extension -eq ".xz") {
    # Descompactar XZ — tenta bash (Git for Windows) ou 7-zip
    $bash = Get-Command bash -ErrorAction SilentlyContinue
    $sz   = Get-Command 7z  -ErrorAction SilentlyContinue
    if ($bash) {
        $unixSrc = $coreImgGlob.FullName.Replace('\', '/')
        $unixDst = $tmpJar.Replace('\', '/')
        & bash -c "cp `"$unixSrc`" `"$unixDst.xz`" && xz -d `"$unixDst.xz`""
    } elseif ($sz) {
        & 7z e $coreImgGlob.FullName "-o$(Split-Path $tmpJar -Parent)" -y | Out-Null
        $extracted = Get-ChildItem (Split-Path $tmpJar -Parent) -Filter "weasis-core-img-*.jar" |
                     Select-Object -First 1
        if ($extracted) { Rename-Item $extracted.FullName $tmpJar -Force }
    } else {
        Write-Error "7-Zip ou Git Bash necessario para descompactar $($coreImgGlob.Name).`nInstale Git for Windows (https://gitforwindows.org) ou 7-Zip (https://7-zip.org)."
        exit 1
    }
} else {
    Copy-Item $coreImgGlob.FullName $tmpJar -Force
}

if (-not (Test-Path $tmpJar)) {
    Write-Error "Falha ao preparar weasis-core-img.jar para deteccao de arquitetura."
    exit 1
}

$ARC_OS = & $JAVA -cp $tmpJar org.weasis.opencv.natives.NativeLibrary 2>$null
if (-not $ARC_OS) {
    $ARC_OS = & $JAVA -cp $tmpJar org.weasis.core.util.NativeLibrary 2>$null
}
Remove-Item $tmpJar -ErrorAction SilentlyContinue

if (-not $ARC_OS) { Write-Error "Nao foi possivel detectar a arquitetura do sistema."; exit 1 }
$machine = $ARC_OS.Split("-")[0]
$arc     = ($ARC_OS.Split("-") | Select-Object -Skip 1) -join "-"
Write-Host "[INFO]  Plataforma: $ARC_OS  (machine=$machine, arc=$arc)"

if ($machine -ne "windows") {
    Write-Error "Este script so gera instaladores Windows. Plataforma detectada: $machine"
    exit 1
}

# ---------------------------------------------------------------------------
# Configurar jpackage
# ---------------------------------------------------------------------------
$RES       = Join-Path $SCRIPT_DIR "build\script\resources\windows"
$INPUT_DIR = Join-Path $BIN_DIST "weasis"
$IMAGE_PATH = Join-Path $Output "Weasis"

$CLEAN_VERSION = $WEASIS_VERSION -replace '-[^.]*$', '' -replace '(\d+\.\d+\.\d+)\.\d+', '$1'

$JDK_MODULES = "java.base,java.compiler,java.datatransfer,java.net.http,java.desktop," +
               "java.logging,java.management,java.prefs,java.xml,jdk.localedata," +
               "jdk.charsets,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.jdwp.agent," +
               "java.sql,jdk.crypto.mscapi"

if (Test-Path $Output) { Remove-Item -Recurse -Force $Output }

Write-Host "`n── Gerando imagem do app com jpackage ──"

& $JPACKAGE `
    --type app-image `
    --input  $INPUT_DIR `
    --dest   $Output `
    --name   "Weasis" `
    --main-jar weasis-launcher.jar `
    --main-class org.weasis.launcher.AppLauncher `
    --add-modules $JDK_MODULES `
    "--add-launcher" "Dicomizer=$RES\dicomizer-launcher.properties" `
    --resource-dir $RES `
    --app-version $CLEAN_VERSION `
    --java-options "-Dgosh.port=17179" `
    --java-options "--enable-native-access=ALL-UNNAMED" `
    --java-options "-Djavax.accessibility.assistive_technologies=org.weasis.launcher.EmptyAccessibilityProvider" `
    --java-options "-Djavax.accessibility.screen_magnifier_present=false" `
    "--java-options" "-splash:`$APPDIR\resources\images\about-round.png" `
    --verbose

if ($LASTEXITCODE -ne 0) { Write-Error "jpackage app-image falhou (exit $LASTEXITCODE)."; exit 1 }
Write-Host "[OK]    Imagem do app gerada em: $IMAGE_PATH"

# ---------------------------------------------------------------------------
# Instalar venv Python dentro do app (pós-jpackage)
# ---------------------------------------------------------------------------
$appDir = Join-Path $Image_Path "app"
if (Test-Path $appDir) {
    Write-Host "`n── Instalando ambiente Python dentro do app ──"
    $venvDst = Join-Path $appDir "python-env"
    $pyCmd = $null
    foreach ($c in @("python", "python3")) {
        try {
            $ver = & $c -c "import sys; print(sys.version_info.major*10+sys.version_info.minor)" 2>$null
            if ($ver -match '^\d+$' -and [int]$ver -ge 39) { $pyCmd = $c; break }
        } catch {}
    }
    if ($pyCmd) {
        & $pyCmd -m venv $venvDst --clear
        & "$venvDst\Scripts\pip.exe" install --upgrade pip --quiet
        & "$venvDst\Scripts\pip.exe" install ultralytics torch opencv-python grad-cam --quiet
        Write-Host "[OK]    Venv Python instalado em: $venvDst"
    } else {
        Write-Warning "Python 3.9+ nao encontrado. Execute setup-python.ps1 no PC de destino."
    }
}

# ---------------------------------------------------------------------------
# Gerar instalador MSI
# ---------------------------------------------------------------------------
if (-not $NoPackage) {
    Write-Host "`n── Gerando instalador MSI ──"

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
        --name "Weasis" `
        --resource-dir $msiResDir `
        --license-file $licenseFile `
        --description "Weasis DICOM viewer" `
        --win-upgrade-uuid $UPGRADE_UID `
        --win-menu `
        --win-menu-group "Weasis" `
        --copyright "© 2009-2026 Weasis Team" `
        --app-version $CLEAN_VERSION `
        --vendor "Weasis Team" `
        --file-associations $fileAssoc `
        --verbose

    if ($LASTEXITCODE -ne 0) { Write-Error "jpackage MSI falhou (exit $LASTEXITCODE)."; exit 1 }

    # Renomear para incluir arquitetura
    $msi = Get-ChildItem $Output -Filter "Weasis-*.msi" | Select-Object -First 1
    if ($msi) {
        $newName = $msi.Name -replace "\.msi$", "-$arc.msi"
        Rename-Item $msi.FullName (Join-Path $Output $newName)
        Write-Host "[OK]    Instalador MSI: $newName"
    }
}

Write-Host ""
Write-Host "[OK]    Concluido! Artefatos em: $Output"
Get-ChildItem $Output -ErrorAction SilentlyContinue | Format-Table Name, Length, LastWriteTime
