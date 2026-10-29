# Offline fixtures: no SDK, Git writes, credentials or network needed.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $projectRoot 'tools\release-bundle.ps1')
$fixture = Join-Path $projectRoot ('build-release-test-' + [guid]::NewGuid().ToString('N'))
function Rejects([scriptblock]$Action) {
    $rejected = $false
    try { & $Action | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Invalid/incomplete release bundle was accepted.' }
}
try {
    foreach ($folder in 'versions', 'android', 'docs', 'dist') { [IO.Directory]::CreateDirectory((Join-Path $fixture $folder)) | Out-Null }
    foreach ($component in 'windows', 'android', 'core', 'release') {
        Copy-Item -LiteralPath (Join-Path $projectRoot "versions\$component.properties") -Destination (Join-Path $fixture "versions\$component.properties")
    }
    foreach ($path in 'RELEASE_NOTES.md', 'android\RELEASE_NOTES.md', 'docs\core-release-notes.md') {
        Copy-Item -LiteralPath (Join-Path $projectRoot $path) -Destination (Join-Path $fixture $path)
    }
    foreach ($component in 'windows', 'android', 'core') {
        $bundle = Get-ReleaseBundle $component $fixture
        if ($bundle.Names.Count -ne 7 -or -not $bundle.Latest -or $bundle.Preview -or $bundle.Tag -cne "v$($bundle.Release.Version)") { throw 'Release channel, stable tag or asset set mismatch.' }
        # Old Windows versions accept only the bare alias or a package matching
        # the release tag. Keep exactly one match even if bundle == app version.
        foreach ($oldTag in @($bundle.Tag, "v$($bundle.Windows.Version)")) {
            $oldMatches = @($bundle.Names | Where-Object { $_ -ceq 'IgnorantShuffle.aimppack' -or $_ -ceq "IgnorantShuffle-$oldTag.aimppack" })
            if ($oldMatches.Count -ne 1) { throw 'Older Windows updaters would see ambiguous installers.' }
        }
        foreach ($version in $bundle.Windows.Version, $bundle.Android.Version, $bundle.Core.Version) {
            if (-not $bundle.Title.Contains("v$version") -or -not $bundle.Notes.Contains("v$version")) { throw 'A component version is missing from the title/notes.' }
        }
        if (-not $bundle.Notes.Contains('/blob/' + [Uri]::EscapeDataString($bundle.Tag) + '/docs/changelogs/core.md')) { throw 'Source links must point at the release snapshot.' }
        foreach ($name in $bundle.Names) {
            $expected = '/releases/download/' + [Uri]::EscapeDataString($bundle.Tag) + '/' + $name
            if (-not $bundle.Notes.Contains($expected)) { throw "Download table omits $name for $component" }
            [IO.File]::WriteAllText((Join-Path $fixture "dist\$name"), "fixture $name")
        }
        Copy-Item -LiteralPath (Join-Path $fixture "dist\$($bundle.Names[0])") -Destination (Join-Path $fixture "dist\$($bundle.Names[6])") -Force
        foreach ($group in @(
            @{ Manifest='SHA256SUMS.txt'; Files=@($bundle.Names[0], $bundle.Names[1], $bundle.Names[2], $bundle.Names[6]) },
            @{ Manifest='SHA256SUMS-Android.txt'; Files=@($bundle.Names[4]) }
        )) {
            $lines = foreach ($name in $group.Files) {
                (Get-FileHash -LiteralPath (Join-Path $fixture "dist\$name") -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + $name
            }
            [IO.File]::WriteAllText((Join-Path $fixture "dist\$($group.Manifest)"), ($lines -join "`n") + "`n")
        }
        Test-ReleaseChecksums $bundle $fixture
        foreach ($index in 0, 1, 2, 4, 6) {
            $path = Join-Path $fixture ('dist\' + $bundle.Names[$index])
            $original = [IO.File]::ReadAllText($path)
            [IO.File]::WriteAllText($path, 'tampered')
            Rejects { Test-ReleaseChecksums $bundle $fixture }
            [IO.File]::WriteAllText($path, $original)
            Remove-Item -LiteralPath $path
            Rejects { Test-ReleaseChecksums $bundle $fixture }
            [IO.File]::WriteAllText($path, $original)
        }
        Test-ReleaseChecksums $bundle $fixture
    }
    [IO.File]::WriteAllText((Join-Path $fixture 'android\RELEASE_NOTES.md'), '# IgnorantShuffle Android v0.0.0')
    Rejects { Get-ReleaseBundle windows $fixture }
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $prefix = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\build-release-test-'
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected fixture cleanup path.' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
Write-Output 'Complete Windows/Android bundle, independent versions/channels, download links, tamper and missing-asset rejection passed (offline).'
