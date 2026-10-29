# Offline metadata regression. Fixture edits never touch real component versions.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $projectRoot 'tools\component-version.ps1')
foreach ($component in 'windows', 'android', 'core', 'release') {
    $info = Get-ComponentVersion $component
    if ($info.Tag -cne "v$($info.Version)") { throw 'Incorrect stable version tag.' }
}
$fixture = Join-Path $projectRoot ('build-version-test-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory((Join-Path $fixture 'versions')) | Out-Null
function Write-Version([string]$component, [string]$content) {
    [IO.File]::WriteAllText((Join-Path $fixture "versions\$component.properties"), $content)
}
function Rejects([scriptblock]$action) {
    $rejected = $false
    try { & $action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Invalid metadata was accepted.' }
}
try {
    Write-Version windows 'version=4.5.6'
    Write-Version android "version=1.2.3`nversionCode=9"
    Write-Version core 'version=0.7.0'
    Write-Version release 'version=9.0.0'
    if ((Get-ComponentVersion release $fixture).Tag -cne 'v9.0.0') { throw 'Combined release tag must be plain stable semver.' }
    Write-Version android "version=1.2.4`nversionCode=10"
    if ((Get-ComponentVersion windows $fixture).Version -ne '4.5.6' -or
        (Get-ComponentVersion core $fixture).Version -ne '0.7.0') { throw 'Android bump changed another version.' }
    Write-Version core 'version=0.8.0'
    if ((Get-ComponentVersion windows $fixture).Version -ne '4.5.6' -or
        (Get-ComponentVersion android $fixture).VersionCode -ne 10) { throw 'Core bump changed an app version.' }
    Write-Version windows 'version=4.6.0'
    if ((Get-ComponentVersion core $fixture).Version -ne '0.8.0' -or
        (Get-ComponentVersion android $fixture).Version -ne '1.2.4') { throw 'Windows bump changed another version.' }
    foreach ($invalid in '1.2', 'v1.2.3', '01.2.3', '1.2.3-beta', '65536.0.0') {
        Write-Version windows "version=$invalid"
        Rejects { Get-ComponentVersion windows $fixture }
    }
    foreach ($invalid in '0', '-1', '2100000001', 'not-a-code') {
        Write-Version android "version=1.2.3`nversionCode=$invalid"
        Rejects { Get-ComponentVersion android $fixture }
    }
    Write-Version core 'version=1.2.3.4'
    Rejects { Get-ComponentVersion core $fixture }
} finally {
    # Validate the exact absolute directory before recursive cleanup on Windows.
    $resolved = [IO.Path]::GetFullPath($fixture)
    $prefix = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\build-version-test-'
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
Write-Output 'Independent component metadata, release tags, and invalid-version checks passed (offline).'
