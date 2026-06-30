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
$devices = adb devices | Select-String -Pattern "\bdevice\b" | ForEach-Object { $_.Line.Split("`t")[0] }

if ($devices -eq $null -or $devices.Count -eq 0) {
    Write-Host "[ERROR] No connected ADB devices found. Please plug in your phone via USB or connect via wireless debugging (adb connect <ip>:<port>)." -ForegroundColor Red
    exit 1
}

# Use the first active device
$targetDevice = $devices[0]
Write-Host "[OK] Target Device Detected: $targetDevice" -ForegroundColor Green

# 3. Establish port forwarding
Write-Host "[*] Reversing port 8080..." -ForegroundColor Cyan
adb -s $targetDevice reverse tcp:8080 tcp:8080

# 4. Install the APK
Write-Host "[*] Installing APK..." -ForegroundColor Cyan
$apkFile = Get-ChildItem -Path "apps/android-app/app/build/outputs/apk/dev/debug" -Filter "*.apk" | Select-Object -First 1
if ($apkFile -ne $null) {
    Write-Host "[OK] Installing: $($apkFile.Name)" -ForegroundColor Green
    adb -s $targetDevice install -r $apkFile.FullName
} else {
    Write-Host "[ERROR] Compiled APK not found in build outputs." -ForegroundColor Red
    exit 1
}

# 5. Launch the app
Write-Host "[*] Launching SkoLab on phone..." -ForegroundColor Cyan
adb -s $targetDevice shell am start -n com.company.skolab/com.company.skolab.MainActivity

Write-Host "[SUCCESS] Done!" -ForegroundColor Green
