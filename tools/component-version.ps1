# Shared local metadata reader; importing this file has no side effects.
function Get-ComponentVersion {
    param(
        [Parameter(Mandatory)][ValidateSet('windows', 'android', 'core', 'release')][string]$Component,
        [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
    )
    $path = Join-Path $RepositoryRoot "versions\$Component.properties"
    $properties = Get-Content -LiteralPath $path -Raw | ConvertFrom-StringData
    $version = $properties.version
    if ($version -cnotmatch '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$') {
        throw "Invalid stable MAJOR.MINOR.PATCH version in $path"
    }
    if ($Component -eq 'windows' -and @($version.Split('.') | Where-Object { [decimal]$_ -gt 65535 }).Count) {
        throw 'Windows version components must fit in VERSIONINFO (0..65535).'
    }
    $code = $null
    if ($Component -eq 'android') {
        [int]$parsedCode = 0
        if (-not [int]::TryParse($properties.versionCode, [ref]$parsedCode) -or $parsedCode -lt 1 -or $parsedCode -gt 2100000000) {
            throw 'Android versionCode must be in 1..2100000000.'
        }
        $code = $parsedCode
    }
    [pscustomobject]@{ Component = $Component; Version = $version; VersionCode = $code; Tag = "v$version" }
}
