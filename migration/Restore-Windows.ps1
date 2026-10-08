$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'project\migration\restore.py'
if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 $script --bundle-root $PSScriptRoot @args
} elseif (Get-Command python -ErrorAction SilentlyContinue) {
    & python $script --bundle-root $PSScriptRoot @args
} else {
    throw 'Install Python 3.10+ from python.org, then run this script again.'
}
if ($LASTEXITCODE -ne 0) { throw 'Restore did not complete. Read the reported error; do not delete volumes.' }
