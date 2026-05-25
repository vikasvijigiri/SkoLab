# Builds ResQit Android app from an ASCII-only copy (fixes Gradle when project path contains π).
# Usage: powershell -ExecutionPolicy Bypass -File scripts/build-and-install.ps1
# Optional: -InstallOnly if build folder already synced

param(
    [switch]$InstallOnly,
    [switch]$SkipLaunch
)

$ErrorActionPreference = "Stop"

function Find-ProjectRoot {
    if (Test-Path (Join-Path (Get-Location) "android-app")) {
        return (Get-Location).Path
    }
    $docs = Join-Path $env:USERPROFILE "Documents"
    $match = Get-ChildItem $docs -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq "ResQit" -or $_.Name -like "Entro*" -or $_.Name -eq "QyRus" } |
        Select-Object -First 1
    if (-not $match) {
        throw "Could not find ResQit, QyRus, or Entro* project folder under $docs"
    }
    return $match.FullName
}

function Stop-GradleDaemons {
    param(
        [string]$GradleProjectDir
    )

    $wrapper = Join-Path $GradleProjectDir "gradlew.bat"
    if (Test-Path $wrapper) {
        Write-Host "Stopping Gradle daemons..."
        Push-Location $GradleProjectDir
        try {
            & .\gradlew.bat --stop | Out-Null
        } catch {
            Write-Warning "Gradle daemon stop failed: $($_.Exception.Message)"
        } finally {
            Pop-Location
        }
    }
}

function Remove-BuildArtifacts {
    param(
        [string]$GradleProjectDir
    )

    foreach ($path in @(
        (Join-Path $GradleProjectDir ".gradle"),
        (Join-Path $GradleProjectDir "build"),
        (Join-Path $GradleProjectDir "app\build")
    )) {
        if (Test-Path $path) {
            Remove-Item $path -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Invoke-DebugBuild {
    param(
        [string]$GradleProjectDir
    )

    $env:KOTLIN_COMPILER_EXECUTION_STRATEGY = "in-process"
    $gradleArgs = @(
        "clean",
        ":app:assembleDebug",
        "--no-daemon",
        "--rerun-tasks",
        "--no-build-cache"
    )

    Push-Location $GradleProjectDir
    try {
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -eq 0) {
            return
        }

        Write-Warning "Initial Gradle build failed; clearing copied caches and retrying once."
        Stop-GradleDaemons -GradleProjectDir $GradleProjectDir
        Remove-BuildArtifacts -GradleProjectDir $GradleProjectDir

        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle build failed (exit $LASTEXITCODE)"
        }
    } finally {
        $env:KOTLIN_COMPILER_EXECUTION_STRATEGY = $null
        Pop-Location
    }
}

$projectRoot = Find-ProjectRoot
$androidSrc = Join-Path $projectRoot "android-app"
$buildRoot = Join-Path $env:LOCALAPPDATA "ResQit-build"
$androidDst = Join-Path $buildRoot "android-app"

if (-not $InstallOnly) {
    Stop-GradleDaemons -GradleProjectDir $androidDst

    if (Test-Path $buildRoot) {
        Write-Host "Removing old build cache at $buildRoot ..."
        Remove-Item $buildRoot -Recurse -Force -ErrorAction SilentlyContinue
    }

    Write-Host "Syncing android-app to $androidDst ..."
    New-Item -ItemType Directory -Force -Path $androidDst | Out-Null

    $excludeDirs = @("build", ".gradle", ".idea", "captures")
    robocopy $androidSrc $androidDst /E /XD $excludeDirs /NFL /NDL /NJH /NJS /nc /ns /np | Out-Null
    if ($LASTEXITCODE -ge 8) {
        throw "robocopy failed with exit code $LASTEXITCODE"
    }

    # Copy local.properties if present (SDK path, GOOGLE_WEB_CLIENT_ID)
    $localProps = Join-Path $androidSrc "local.properties"
    if (Test-Path $localProps) {
        Copy-Item $localProps (Join-Path $androidDst "local.properties") -Force
    }

    Write-Host "Building debug APK..."
    Invoke-DebugBuild -GradleProjectDir $androidDst
}

$apk = Join-Path $androidDst "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
    throw "APK not found at $apk. Run without -InstallOnly first."
}

Write-Host "Checking connected ADB devices..."
$devices = adb devices 2>$null
$hasDevice = $false
if ($LASTEXITCODE -eq 0 -and $devices) {
    foreach ($line in $devices) {
        if ($line -match "\bdevice\b") {
            $hasDevice = $true
            break
        }
    }
}

if (-not $hasDevice) {
    Write-Host ""
    Write-Host "=========================================================================" -ForegroundColor Yellow
    Write-Host "  ⚠️  [WARNING] No active Android device or emulator was found connected via ADB." -ForegroundColor Yellow
    Write-Host "=========================================================================" -ForegroundColor Yellow
    Write-Host "The debug APK was successfully compiled and is located at:"
    Write-Host "  $apk" -ForegroundColor Green
    Write-Host ""
    Write-Host "Once your device connection is established, you can install it instantly without"
    Write-Host "recompiling the app by running this fast install-only command:"
    Write-Host "  powershell -ExecutionPolicy Bypass -File scripts/build-and-install.ps1 -InstallOnly" -ForegroundColor Cyan
    Write-Host "Or manually via ADB:"
    Write-Host "  adb install -r `"$apk`"" -ForegroundColor Cyan
    Write-Host "=========================================================================" -ForegroundColor Yellow
    Write-Host ""
} else {
    Write-Host "Installing on device..."
    adb install -r $apk
    if ($LASTEXITCODE -ne 0) { 
        Write-Warning "adb install failed. Please ensure your device is unlocked and screen is on."
    } else {
        if (-not $SkipLaunch) {
            Write-Host "Setting up USB port forwarding for backend access..."
            adb reverse tcp:8000 tcp:8000
            Write-Host "Launching ResQit..."
            adb shell am start -n com.company.ResQit/com.open.entropy.MainActivity
        }
        Write-Host "Done. Launcher name should show as ResQit after install." -ForegroundColor Green
    }
}
