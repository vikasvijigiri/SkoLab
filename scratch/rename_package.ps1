if (Test-Path "android-app\app\src\main\java\com\open") {
    Rename-Item -Path "android-app\app\src\main\java\com\open" -NewName "company"
    Write-Host "Directory renamed: com\open -> com\company"
} else {
    Write-Host "Directory 'android-app\app\src\main\java\com\open' not found or already renamed."
}

$files = Get-ChildItem -Path . -Recurse -Include *.kt, *.kts, *.xml, *.json, *.properties, *.md, *.ps1 -Exclude .git, .gradle, .idea, build, venv, node_modules

foreach ($file in $files) {
    $content = Get-Content -Raw -Path $file.FullName
    $changed = $false
    
    if ($content -match "com.company.skolab") {
        $content = $content -replace "com.company.skolab", "com.company.skolab"
        $changed = $true
    }
    if ($content -match "com.company.skolab") {
        $content = $content -replace "com.company.skolab", "com/company/skolab"
        $changed = $true
    }
    if ($content -match "com\\open\\skolab") {
        $content = $content -replace "com\\open\\skolab", "com\\company\\skolab"
        $changed = $true
    }

    if ($changed) {
        Set-Content -Path $file.FullName -Value $content -NoNewline
        Write-Host "Updated: $($file.FullName)"
    }
}
