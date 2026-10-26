[CmdletBinding()]
param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'dist' }
$cmakeText = Get-Content -LiteralPath (Join-Path $projectRoot 'CMakeLists.txt') -Raw
if ($cmakeText -notmatch 'project\(IgnorantShuffle VERSION ([0-9]+\.[0-9]+\.[0-9]+)') { throw 'Cannot read project version.' }
$version = $Matches[1]
$files = @(
    @{ Source = Join-Path $projectRoot 'build-Win32\Release\aimp_ignorant_shuffle.dll'; Entry = 'aimp_ignorant_shuffle/aimp_ignorant_shuffle.dll' },
    @{ Source = Join-Path $projectRoot 'build-x64\Release\aimp_ignorant_shuffle.dll'; Entry = 'aimp_ignorant_shuffle/x64/aimp_ignorant_shuffle.dll' }
)
foreach ($file in $files) {
    if (-not (Test-Path -LiteralPath $file.Source -PathType Leaf)) { throw "Build both architectures first: missing $($file.Source)" }
    $actualVersion = [Diagnostics.FileVersionInfo]::GetVersionInfo($file.Source).FileVersion
    if ($actualVersion -ne $version) { throw "Version mismatch in $($file.Source): $actualVersion, expected $version" }
}
$licenseFile = Join-Path $projectRoot 'LICENSE'
if (-not (Test-Path -LiteralPath $licenseFile -PathType Leaf)) { throw 'Missing project LICENSE.' }
$files += @{ Source = $licenseFile; Entry = 'aimp_ignorant_shuffle/LICENSE' }
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$output = [IO.Directory]::CreateDirectory($OutputDirectory).FullName
$package = Join-Path $output "IgnorantShuffle-v$version.aimppack"
$stream = [IO.File]::Open($package, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        foreach ($file in $files) {
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.Source, $file.Entry, [IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $archive.Dispose() }
} finally { $stream.Dispose() }
# Inspect the produced ZIP, including every byte, instead of trusting staging paths.
$archive = [IO.Compression.ZipFile]::OpenRead($package)
try {
    if ($archive.Entries.Count -ne $files.Count) { throw 'Unexpected package entries.' }
    foreach ($file in $files) {
        $entry = $archive.GetEntry($file.Entry)
        if (-not $entry) { throw "Missing package entry $($file.Entry)" }
        $sourceHash = (Get-FileHash -LiteralPath $file.Source -Algorithm SHA256).Hash
        $content = $entry.Open()
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $entryHash = [BitConverter]::ToString($sha.ComputeHash($content)).Replace('-', '') }
        finally { $sha.Dispose(); $content.Dispose() }
        if ($entryHash -ne $sourceHash) { throw "Package verification failed: $($file.Entry)" }
    }
} finally { $archive.Dispose() }
$digest = (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash.ToLowerInvariant()
$checksum = "$package.sha256"
[IO.File]::WriteAllText($checksum, "$digest  $([IO.Path]::GetFileName($package))`n", [Text.UTF8Encoding]::new($false))
Write-Output "Package: $package"
Write-Output "SHA256: $checksum"
$rawDlls = @(
    @{ Source = $files[0].Source; Name = 'aimp_ignorant_shuffle-x86.dll' },
    @{ Source = $files[1].Source; Name = 'aimp_ignorant_shuffle-x64.dll' }
)
foreach ($dll in $rawDlls) { Copy-Item -LiteralPath $dll.Source -Destination (Join-Path $output $dll.Name) -Force }
$releaseFiles = @($package) + @($rawDlls | ForEach-Object { Join-Path $output $_.Name })
$lines = foreach ($releaseFile in $releaseFiles) {
    (Get-FileHash -LiteralPath $releaseFile -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($releaseFile)
}
$manifest = Join-Path $output 'SHA256SUMS.txt'
[IO.File]::WriteAllText($manifest, ($lines -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
Write-Output "Release checksums: $manifest"
