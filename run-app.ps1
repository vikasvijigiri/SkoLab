# run-app.ps1
# Automates building, reversing ports, installing, and launching SkoLab on the active device.

# 1. Compile the Android app
Write-Host "[*] Stopping existing Gradle daemons to release file locks..." -ForegroundColor Yellow
cd apps/android-app
./gradlew --stop
Write-Host "[*] Compiling Android app (clean build)..." -ForegroundColor Cyan
./gradlew clean assembleDevDebug
cd ../..

# 2. Find the active ADB device
Write-Host "[*] Detecting connected ADB devices..." -ForegroundColor Cyan
$devices = @()
$adbOutput = adb devices 2>$null
if ($LASTEXITCODE -eq 0 -and $adbOutput) {
    foreach ($line in $adbOutput) {
        if ($line -match "^([^\s]+)\s+device\b") {
            $devices += $Matches[1]
        }
    }
}

if ($devices.Count -eq 0) {
    Write-Host ""
    Write-Host "=========================================================================" -ForegroundColor Yellow
    Write-Host "  [WARNING] No active Android device or emulator was found connected via ADB." -ForegroundColor Yellow
    Write-Host "=========================================================================" -ForegroundColor Yellow
    Write-Host "The debug APK was successfully compiled and is located in:"
    Write-Host "  apps/android-app/app/build/outputs/apk/dev/debug/" -ForegroundColor Green
    Write-Host ""
    Write-Host "Once your device connection is established, you can run this script again or install manually:"
    Write-Host "  adb install -r <path-to-apk>" -ForegroundColor Cyan
    Write-Host "=========================================================================" -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

# Use the first active device
$targetDevice = $devices[0]
Write-Host "[OK] Target Device Detected: $targetDevice" -ForegroundColor Green

# 3. Establish port forwarding
Write-Host "[*] Reversing ports (8000, 8080) for backend connection..." -ForegroundColor Cyan
adb -s $targetDevice reverse tcp:8000 tcp:8000
adb -s $targetDevice reverse tcp:8080 tcp:8080

# 4. Install the APK
Write-Host "[*] Installing APK..." -ForegroundColor Cyan
$apkDir = "apps/android-app/app/build/outputs/apk/dev/debug"
$apkFiles = Get-ChildItem -Path $apkDir -Filter "*.apk"

if ($apkFiles -eq $null -or $apkFiles.Count -eq 0) {
    Write-Host "[ERROR] Compiled APK not found in build outputs." -ForegroundColor Red
    exit 1
}

$targetApk = $null
if ($apkFiles.Count -gt 1) {
    # Query device ABI to match correct split APK
    $deviceAbi = (adb -s $targetDevice shell getprop ro.product.cpu.abi).Trim()
    Write-Host "[*] Device CPU Architecture: $deviceAbi" -ForegroundColor Cyan
    $targetApk = $apkFiles | Where-Object { $_.Name -like "*$deviceAbi*" } | Select-Object -First 1
}

if ($targetApk -eq $null) {
    $targetApk = $apkFiles[0]
}

Write-Host "[OK] Installing: $($targetApk.Name)" -ForegroundColor Green
adb -s $targetDevice install -r $targetApk.FullName

# 5. Launch the app
Write-Host "[*] Launching SkoLab on phone..." -ForegroundColor Cyan
adb -s $targetDevice shell am start -n com.company.skolab/com.company.skolab.MainActivity

Write-Host "[SUCCESS] Done!" -ForegroundColor Green
