param(
    [string]$Ide = 'E:\APP\CLION\CLion-2025.3.3.win',
    [string]$Python = 'C:\Users\CRB\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe',
    [switch]$Test
)
$ErrorActionPreference = 'Stop'
$arguments = @((Join-Path $PSScriptRoot 'build_plugin.py'), '--ide', $Ide)
if ($Test) { $arguments += '--test' }
& $Python @arguments
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
