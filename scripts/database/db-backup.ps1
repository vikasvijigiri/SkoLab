# SkoLab PostgreSQL Backup Script with AES-256 Encryption
# Usage: powershell -ExecutionPolicy Bypass -File scripts/db-backup.ps1

$ErrorActionPreference = "Stop"

$date = Get-Date -Format "yyyyMMdd-HHmmss"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$backupDir = Join-Path $projectRoot "backups"

if (-not (Test-Path $backupDir)) {
    New-Item -ItemType Directory -Path $backupDir | Out-Null
}

$tempFile = Join-Path $backupDir "temp_skolab_backup_$date.sql"
$backupFile = Join-Path $backupDir "skolab_backup_$date.sql.enc"

# Load DATABASE_ENCRYPTION_KEY from backend/.env or fallback
$envFile = Join-Path $projectRoot "backend\.env"
$encryptionKey = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MTI=" # default fallback matching config.py
if (Test-Path $envFile) {
    Get-Content $envFile | Foreach-Object {
        $line = $_.Trim()
        if ($line -match "^DATABASE_ENCRYPTION_KEY=(.*)$") {
            $encryptionKey = $Matches[1].Trim().Trim('"').Trim("'")
        }
    }
}

function Encrypt-File {
    param (
        [string]$Path,
        [string]$OutputPath,
        [string]$KeyBase64
    )
    $key = [System.Convert]::FromBase64String($KeyBase64)
    $aes = [System.Security.Cryptography.Aes]::Create()
    $aes.Key = $key
    $aes.GenerateIV()
    
    $fileBytes = [System.IO.File]::ReadAllBytes($Path)
    
    $encryptor = $aes.CreateEncryptor()
    $encryptedBytes = $encryptor.TransformFinalBlock($fileBytes, 0, $fileBytes.Length)
    
    $outStream = New-Object System.IO.FileStream($OutputPath, [System.IO.FileMode]::Create)
    $outStream.Write($aes.IV, 0, $aes.IV.Length)
    $outStream.Write($encryptedBytes, 0, $encryptedBytes.Length)
    $outStream.Close()
}

Write-Host "Running PostgreSQL database backup via docker exec pg_dump..."
# Note: container name must match skolab_postgres from docker-compose
docker exec skolab_postgres pg_dump -U postgres -d skolab > $tempFile

if (Test-Path $tempFile) {
    Write-Host "Encrypting backup file with AES-256..."
    Encrypt-File -Path $tempFile -OutputPath $backupFile -KeyBase64 $encryptionKey
    Remove-Item $tempFile
    $size = (Get-Item $backupFile).Length
    Write-Host "Backup and encryption completed successfully! Saved to: $backupFile ($size bytes)"
} else {
    Write-Error "Backup failed!"
}
