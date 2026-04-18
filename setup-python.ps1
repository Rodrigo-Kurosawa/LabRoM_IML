#Requires -Version 5.1
# setup-python.ps1 — Configura o ambiente Python para o LabRoM Sex Classifier.
#
# Execute UMA VEZ em PCs novos (duplo-clique ou PowerShell):
#   .\setup-python.ps1
#
# O venv e criado em %USERPROFILE%\.weasis\labrom-env\ e detectado
# automaticamente pelo plugin Java sem necessidade de configuracao adicional.

$ErrorActionPreference = "Stop"

$VENV_DIR = Join-Path $env:USERPROFILE ".weasis\labrom-env"

if (Test-Path "$VENV_DIR\Scripts\python.exe") {
    Write-Host "Ambiente Python ja existe em: $VENV_DIR"
    Write-Host "Para recriar, delete a pasta e execute este script novamente."
    exit 0
}

# Localizar Python 3.9+
$pythonCmd = $null
foreach ($c in @("python", "python3", "py")) {
    try {
        $ver = & $c -c "import sys; print(sys.version_info.major*10+sys.version_info.minor)" 2>$null
        if ($ver -match '^\d+$' -and [int]$ver -ge 39) { $pythonCmd = $c; break }
    } catch {}
}

if (-not $pythonCmd) {
    Write-Error @"
ERRO: Python 3.9+ nao encontrado.

Instale Python de: https://www.python.org/downloads/
  - Marque a opcao "Add python.exe to PATH" durante a instalacao.
  - Reinicie o PowerShell apos instalar e execute este script novamente.
"@
    exit 1
}

$pyVersion = & $pythonCmd --version 2>&1
Write-Host "Usando Python: $pyVersion"
Write-Host "Criando ambiente virtual em: $VENV_DIR"
Write-Host "(Isto pode levar alguns minutos — instalando PyTorch, ultralytics, etc.)"

New-Item -ItemType Directory -Force -Path (Split-Path $VENV_DIR -Parent) | Out-Null
& $pythonCmd -m venv $VENV_DIR --clear
if ($LASTEXITCODE -ne 0) { Write-Error "Falha ao criar venv."; exit 1 }

& "$VENV_DIR\Scripts\pip.exe" install --upgrade pip --quiet
& "$VENV_DIR\Scripts\pip.exe" install `
    ultralytics torch torchvision opencv-python pydicom pillow grad-cam

if ($LASTEXITCODE -ne 0) {
    Write-Error "Falha ao instalar dependencias Python."
    exit 1
}

Write-Host ""
Write-Host "Ambiente Python configurado com sucesso em: $VENV_DIR"
Write-Host "O plugin LabRoM ira detecta-lo automaticamente na proxima execucao do Weasis."
