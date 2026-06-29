# SkoLab PostgreSQL Backup Restore Validation Drill Script
# Usage: powershell -ExecutionPolicy Bypass -File scripts/db-backup-validation.ps1

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptDir
$backupDir = Join-Path $projectRoot "backups"

if (-not (Test-Path $backupDir)) {
    Write-Error "Backups directory does not exist at: $backupDir"
}

# Find the latest encrypted backup file
$latestBackup = Get-ChildItem -Path $backupDir -Filter "skolab_backup_*.sql.enc" | Sort-Object LastWriteTime -Descending | Select-Object -First 1

if ($null -eq $latestBackup) {
    Write-Error "No encrypted backups found in: $backupDir"
}

$backupPath = $latestBackup.FullName
$tempDecryptedFile = Join-Path $backupDir "temp_validation_restore.sql"

Write-Host "Found latest backup to validate: $backupPath"

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

try {
    Write-Host "Decrypting backup snapshot for validation drill..."
    Decrypt-File -Path $backupPath -OutputPath $tempDecryptedFile -KeyBase64 $encryptionKey

    Write-Host "Restoring database to validation schema 'skolab_validation'..."
    # Drop and recreate validation schema inside container, then load dump
    docker exec skolab_postgres psql -U postgres -d postgres -c "REVOKE connect ON DATABASE skolab_validation FROM public;"
    docker exec skolab_postgres psql -U postgres -d postgres -c "SELECT pg_terminate_backend(pg_stat_activity.pid) FROM pg_stat_activity WHERE pg_stat_activity.datname = 'skolab_validation' AND pid <> pg_backend_pid();"
    docker exec skolab_postgres psql -U postgres -d postgres -c "DROP DATABASE IF EXISTS skolab_validation;"
    docker exec skolab_postgres psql -U postgres -d postgres -c "CREATE DATABASE skolab_validation;"
    Get-Content $tempDecryptedFile -Raw | docker exec -i skolab_postgres psql -U postgres -d skolab_validation

    # Run validation sanity check queries
    Write-Host "Executing validation integrity tests..."
    $res = docker exec skolab_postgres psql -U postgres -d skolab_validation -t -c "SELECT count(*) FROM users;"
    $userCount = $res.Trim()
    
    Write-Host "[SUCCESS] Validation integrity query succeeded. Users table count: $userCount"
    Write-Host "[DRILL PASSED] Database backup load check verified successfully."
}
catch {
    Write-Error "[DRILL FAILED] Database backup load validation failed! Details: $_"
}
finally {
    # Drop validation database and clean up temporary files
    Write-Host "Cleaning up validation environments..."
    docker exec skolab_postgres psql -U postgres -d postgres -c "REVOKE connect ON DATABASE skolab_validation FROM public;"
    docker exec skolab_postgres psql -U postgres -d postgres -c "SELECT pg_terminate_backend(pg_stat_activity.pid) FROM pg_stat_activity WHERE pg_stat_activity.datname = 'skolab_validation' AND pid <> pg_backend_pid();"
    docker exec skolab_postgres psql -U postgres -d postgres -c "DROP DATABASE IF EXISTS skolab_validation;" | Out-Null
    if (Test-Path $tempDecryptedFile) {
        Remove-Item $tempDecryptedFile
    }
}
