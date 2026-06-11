# SkoLab PostgreSQL Restore Script with AES-256 Decryption
# Usage: powershell -ExecutionPolicy Bypass -File scripts/db-restore.ps1 -BackupPath <path-to-sql-enc-file>

param (
    [Parameter(Mandatory=$true)]
    [string]$BackupPath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $BackupPath)) {
    Write-Error "Backup file not found at: $BackupPath"
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$backupDir = Split-Path -Parent $BackupPath
$tempDecryptedFile = Join-Path $backupDir "temp_decrypted_restore.sql"

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

function Decrypt-File {
    param (
        [string]$Path,
        [string]$OutputPath,
        [string]$KeyBase64
    )
    $key = [System.Convert]::FromBase64String($KeyBase64)
    $fileStream = New-Object System.IO.FileStream($Path, [System.IO.FileMode]::Open)
    
    # Read IV (first 16 bytes)
    $iv = New-Object byte[] 16
    $fileStream.Read($iv, 0, 16) | Out-Null
    
    # Read rest as encrypted bytes
    $encryptedLength = $fileStream.Length - 16
    $encryptedBytes = New-Object byte[] $encryptedLength
    $fileStream.Read($encryptedBytes, 0, $encryptedLength) | Out-Null
    $fileStream.Close()
    
    $aes = [System.Security.Cryptography.Aes]::Create()
    $aes.Key = $key
    $aes.IV = $iv
    
    $decryptor = $aes.CreateDecryptor()
    $decryptedBytes = $decryptor.TransformFinalBlock($encryptedBytes, 0, $encryptedBytes.Length)
    
    [System.IO.File]::WriteAllBytes($OutputPath, $decryptedBytes)
}

Write-Host "Decrypting backup snapshot..."
Decrypt-File -Path $BackupPath -OutputPath $tempDecryptedFile -KeyBase64 $encryptionKey

Write-Host "Restoring database from: $tempDecryptedFile..."
# Drop and recreate schema inside container, then load dump
docker exec skolab_postgres psql -U postgres -d postgres -c "REVOKE connect ON DATABASE skolab FROM public;"
docker exec skolab_postgres psql -U postgres -d postgres -c "SELECT pg_terminate_backend(pg_stat_activity.pid) FROM pg_stat_activity WHERE pg_stat_activity.datname = 'skolab' AND pid <> pg_backend_pid();"
docker exec skolab_postgres psql -U postgres -d postgres -c "DROP DATABASE IF EXISTS skolab;"
docker exec skolab_postgres psql -U postgres -d postgres -c "CREATE DATABASE skolab;"
Get-Content $tempDecryptedFile -Raw | docker exec -i skolab_postgres psql -U postgres -d skolab

# Cleanup unencrypted file
Remove-Item $tempDecryptedFile
Write-Host "Database restore drill completed successfully!"
