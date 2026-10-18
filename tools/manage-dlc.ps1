[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet('list', 'import', 'enable', 'disable')] [string]$Action,
    [Parameter(Mandatory)] [string]$StateRoot,
    [string]$InputPath,
    [string]$PackageId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-common.ps1')
$arguments = @((Join-Path $PSScriptRoot 'manage-fh1-dlc.py'), $Action, '--state-root', $StateRoot)
if ($InputPath) { $arguments += @('--source', $InputPath) }
if ($PackageId) { $arguments += @('--package-id', $PackageId) }
& (Get-PinyonPython) @arguments
# manage-fh1-dlc.py has written the reason to stderr; the launcher shows it
# as it is, without a PowerShell error record around it (#573).
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
