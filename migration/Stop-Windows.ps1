$ErrorActionPreference = 'Stop'
$statePath = Join-Path $PSScriptRoot 'restore-state.json'
if (!(Test-Path $statePath)) { throw 'No restore-state.json found.' }
$state = Get-Content -Raw $statePath | ConvertFrom-Json
$composeArgs = @($state.compose_arguments)
& docker compose @composeArgs stop
if ($LASTEXITCODE -ne 0) { throw 'Application shutdown failed.' }
