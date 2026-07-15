# Starts the SkoLab backend server.
# Usage: powershell -ExecutionPolicy Bypass -File scripts/build/run-backend.ps1

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$backendDir = Join-Path $projectRoot "services\backend"

Push-Location $backendDir
try {
    Write-Host "Starting SkoLab backend server..."
    $env:PYTHONUNBUFFERED = "1"
    $env:PYTHONIOENCODING = "utf-8"
    & .\venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
} finally {
    Pop-Location
}
