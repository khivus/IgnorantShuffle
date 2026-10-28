# Offline regression for the publisher's Windows child-process configuration.
# Never authenticates, writes Git metadata, or contacts GitHub.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$tokens = $null; $parseErrors = $null
$tree = [Management.Automation.Language.Parser]::ParseFile((Join-Path $projectRoot 'tools\publish-release.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Publisher syntax check failed.' }
. (Join-Path $projectRoot 'tools\component-version.ps1')
$versionInfo = Get-ComponentVersion windows
$version = $versionInfo.Version
$tag = $versionInfo.Tag
$repoRoot = $projectRoot
. (Join-Path $projectRoot 'tools\release-bundle.ps1')
foreach ($component in 'windows', 'android', 'core') {
    $bundle = Get-ReleaseBundle $component
    if ($bundle.Names.Count -ne 6 -or @($bundle.Names | Where-Object { $_ -match '[/\\]' }).Count) {
        throw 'Every component release must include both platforms with flat asset names.'
    }
    if ($bundle.Tag -cne (Get-ComponentVersion $component).Tag) { throw 'Wrong component tag.' }
}
if ($tree.Extent.Text -match "Invoke-Git -Arguments @\('(add|commit)'") { throw 'Publisher must not stage or commit unrelated components.' }
$notes = [IO.File]::ReadAllText((Join-Path $projectRoot 'RELEASE_NOTES.md'))
if ($notes.Split("`n")[0].TrimEnd("`r") -cne "# IgnorantShuffle Windows v$version") { throw 'Windows release notes/version mismatch.' }
$gitFunction = $tree.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-Git' }, $true)
if (-not $gitFunction) { throw 'Missing publisher Git invocation.' }
. ([scriptblock]::Create($gitFunction.Extent.Text))
$environmentAssignment = $tree.Find({ param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$gitEnvironment' -and $node.Right.Extent.Text.StartsWith('@{') }, $true)
if (-not $environmentAssignment) { throw 'Missing publisher environment configuration.' }
$gitHeader = 'AUTHORIZATION: basic offline-test-value'
. ([scriptblock]::Create($environmentAssignment.Extent.Text))
$count = [int]$gitEnvironment.GIT_CONFIG_COUNT
for ($index = 0; $index -lt $count; $index++) {
    if ([string]::IsNullOrEmpty($gitEnvironment["GIT_CONFIG_KEY_$index"]) -or [string]::IsNullOrEmpty($gitEnvironment["GIT_CONFIG_VALUE_$index"])) {
        throw "Git environment entry $index would disappear in Windows PowerShell."
    }
}
$previousEnvironment = @{}
foreach ($key in $gitEnvironment.Keys) { $previousEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    foreach ($key in $gitEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $gitEnvironment[$key], 'Process') }
    $header = Invoke-Git -Arguments @('config', '--get', 'http.https://github.com/.extraheader')
    if ($header -cne $gitHeader) { throw 'Child Git did not receive the authentication header.' }
    $helper = Invoke-Git -Arguments @('config', '--get', 'credential.helper')
    if (($helper -join '') -ne '') { throw 'Child Git did not disable credential helpers.' }
} finally {
    foreach ($key in $previousEnvironment.Keys) {
        if ($null -eq $previousEnvironment[$key]) { Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process') }
    }
}
foreach ($key in $previousEnvironment.Keys) {
    if ([Environment]::GetEnvironmentVariable($key, 'Process') -cne $previousEnvironment[$key]) { throw "Git test failed to restore $key." }
}
Write-Output 'Publisher Windows environment and real child-Git configuration passed (offline).'
