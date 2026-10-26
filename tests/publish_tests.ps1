# Offline regression for the publisher's Windows child-process configuration.
# Never authenticates, writes Git metadata, or contacts GitHub.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$tokens = $null; $parseErrors = $null
$tree = [Management.Automation.Language.Parser]::ParseFile((Join-Path $projectRoot 'tools\publish-release.ps1'), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'Publisher syntax check failed.' }
$gitFunction = $tree.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-Git' }, $true)
if (-not $gitFunction) { throw 'Missing publisher Git invocation.' }
. ([scriptblock]::Create($gitFunction.Extent.Text))
$environmentAssignment = $tree.Find({ param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -eq '$gitEnvironment' -and $node.Right.Extent.Text.StartsWith('@{') }, $true)
if (-not $environmentAssignment) { throw 'Missing publisher environment configuration.' }
$gitAuthorization = 'AUTHORIZATION: basic offline-test-value'
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
    if ($header -cne $gitAuthorization) { throw 'Child Git did not receive the authentication header.' }
    $helper = Invoke-Git -Arguments @('config', '--get', 'credential.helper')
    if (($helper -join '') -ne '') { throw 'Child Git did not disable credential helpers.' }
} finally {
    foreach ($key in $previousEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process') }
}
foreach ($key in $previousEnvironment.Keys) {
    if ([Environment]::GetEnvironmentVariable($key, 'Process') -cne $previousEnvironment[$key]) { throw "Git test failed to restore $key." }
}
Write-Output 'Publisher Windows environment and real child-Git configuration passed (offline).'
