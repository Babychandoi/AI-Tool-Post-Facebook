$ErrorActionPreference = 'Stop'
$statePath = Join-Path $PSScriptRoot 'restore-state.json'
if (!(Test-Path $statePath)) { throw 'Run Restore-Windows.ps1 first.' }
$state = Get-Content -Raw $statePath | ConvertFrom-Json
$composeArgs = @($state.compose_arguments)
& docker compose @composeArgs up -d --no-build --pull never
if ($LASTEXITCODE -ne 0) { throw 'Application startup failed.' }
Write-Host 'App: http://127.0.0.1:5173 - keep the Windows browser signed in for automatic images.'
