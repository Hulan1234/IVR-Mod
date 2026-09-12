$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$logDir = Join-Path $root 'build-logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

$fabricOut = Join-Path $logDir 'fabric.out.log'
$fabricErr = Join-Path $logDir 'fabric.err.log'
$forgeOut = Join-Path $logDir 'forge.out.log'
$forgeErr = Join-Path $logDir 'forge.err.log'

$fabric = Start-Process `
    -FilePath (Join-Path $root 'fabric\gradlew.bat') `
    -ArgumentList 'build', '--no-daemon' `
    -WorkingDirectory (Join-Path $root 'fabric') `
    -RedirectStandardOutput $fabricOut `
    -RedirectStandardError $fabricErr `
    -PassThru

$forge = Start-Process `
    -FilePath (Join-Path $root 'forge\gradlew.bat') `
    -ArgumentList 'build', '--no-daemon' `
    -WorkingDirectory (Join-Path $root 'forge') `
    -RedirectStandardOutput $forgeOut `
    -RedirectStandardError $forgeErr `
    -PassThru

$fabric.WaitForExit()
$forge.WaitForExit()

Write-Host "=== Fabric output ==="
Get-Content $fabricOut -ErrorAction SilentlyContinue
Get-Content $fabricErr -ErrorAction SilentlyContinue
Write-Host "=== Forge output ==="
Get-Content $forgeOut -ErrorAction SilentlyContinue
Get-Content $forgeErr -ErrorAction SilentlyContinue

if ($fabric.ExitCode -ne 0 -or $forge.ExitCode -ne 0) {
    exit 1
}
