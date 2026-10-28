# Compatibility entry point; every component release includes both applications.
[CmdletBinding()]
param([switch]$Publish)
& (Join-Path $PSScriptRoot 'publish-release.ps1') -Component android -Publish:$Publish
