Set-Location "C:\Users\Adam\Desktop\buildcraft-template-26.2"

$logFile = "$env:TEMP\buildcraft-auto-backup.log"
$timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

git add -A
$status = git status --porcelain
if ($status) {
    git commit -m "Auto-backup: $timestamp" | Out-Null
    git push origin main
    if ($LASTEXITCODE -eq 0) {
        Add-Content -Path $logFile -Value "$timestamp - committed and pushed changes"
    } else {
        Add-Content -Path $logFile -Value "$timestamp - ERROR: git push exited with code $LASTEXITCODE"
    }
} else {
    Add-Content -Path $logFile -Value "$timestamp - no changes, skipped"
}
