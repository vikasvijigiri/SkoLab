# build-app.ps1
# Automates building the SkoLab Android app APK.

Write-Host "[*] Compiling Android app..." -ForegroundColor Cyan
cd apps/android-app
./gradlew assembleDevDebug
cd ../..

$apkPath = "apps/android-app/app/build/outputs/apk/dev/debug/app-dev-debug.apk"
if (Test-Path $apkPath) {
    Write-Host "[SUCCESS] Build completed successfully!" -ForegroundColor Green
    Write-Host "[*] APK location: $apkPath" -ForegroundColor Yellow
} else {
    Write-Host "[ERROR] Build failed, APK not found at $apkPath" -ForegroundColor Red
    exit 1
}
