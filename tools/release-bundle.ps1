# Shared release contract. Importing this file never builds, authenticates or publishes.
. (Join-Path $PSScriptRoot 'component-version.ps1')

function Get-ReleaseBundle {
    param(
        [ValidateSet('windows', 'android', 'core')][string]$Component,
        [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
    )
    $windows = Get-ComponentVersion windows $RepositoryRoot
    $android = Get-ComponentVersion android $RepositoryRoot
    $core = Get-ComponentVersion core $RepositoryRoot
    $release = Get-ComponentVersion release $RepositoryRoot
    if ([version]$release.Version -lt [version]$windows.Version) { throw 'Combined release version must not trail Windows (legacy updater compatibility).' }
    $tag = $release.Tag
    $encodedTag = [Uri]::EscapeDataString($tag)
    $url = "https://github.com/khivus/IgnorantShuffle/releases/download/$encodedTag"
    $packageName = "IgnorantShuffle-Windows-v$($windows.Version).aimppack"
    $apkName = "IgnorantShuffle-Android-v$($android.Version)-debug.apk"
    $names = @($packageName, 'aimp_ignorant_shuffle-x64.dll', 'aimp_ignorant_shuffle-x86.dll',
        'SHA256SUMS.txt', $apkName, 'SHA256SUMS-Android.txt', 'IgnorantShuffle.aimppack')
    foreach ($entry in @(
        @{ Path='RELEASE_NOTES.md'; Heading="# IgnorantShuffle Windows v$($windows.Version)" },
        @{ Path='android\RELEASE_NOTES.md'; Heading="# IgnorantShuffle Android v$($android.Version)" },
        @{ Path='docs\core-release-notes.md'; Heading="# IgnorantShuffle Core v$($core.Version)" }
    )) {
        $text = [IO.File]::ReadAllText((Join-Path $RepositoryRoot $entry.Path)).Replace("`r`n", "`n")
        if ($text.Split("`n")[0] -cne $entry.Heading) { throw "Release notes/version mismatch: $($entry.Path)" }
    }
    $body = [IO.File]::ReadAllText((Join-Path $RepositoryRoot 'BUNDLE_NOTES.md')).Trim()
    $title = $tag
    $publicNames = @($names | Where-Object { $_ -notlike 'SHA256SUMS*' })
    $notes = @"
**Ignorant Shuffle $tag**
Windows v$($windows.Version) | Android v$($android.Version) | Core v$($core.Version)

$body

## Downloads

| System | Packages |
| --- | --- |
| Windows | [AIMP installer (x64 + x86)]($url/$packageName) / [DLL x64]($url/aimp_ignorant_shuffle-x64.dll) / [DLL x86 (32-bit)]($url/aimp_ignorant_shuffle-x86.dll) |
| Android | [APK Universal]($url/$apkName) |
"@
    [pscustomobject]@{ Windows=$windows; Android=$android; Core=$core; Release=$release; Tag=$tag; Title=$title;
        Names=$names; PublicNames=$publicNames; Notes=$notes.TrimEnd() + "`n"; Preview=$false; Latest=$true }
}

function Test-ReleaseChecksums {
    param($Bundle, [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot))
    $dist = Join-Path $RepositoryRoot 'dist'
    foreach ($name in $Bundle.Names) {
        if (-not (Test-Path -LiteralPath (Join-Path $dist $name) -PathType Leaf)) { throw "Missing release asset: $name. Build both platforms before publication." }
    }
    foreach ($group in @(
        @{ Manifest='SHA256SUMS.txt'; Files=@($Bundle.Names[0], $Bundle.Names[1], $Bundle.Names[2], $Bundle.Names[6]) },
        @{ Manifest='SHA256SUMS-Android.txt'; Files=@($Bundle.Names[4]) }
    )) {
        $manifest = @(Get-Content -LiteralPath (Join-Path $dist $group.Manifest))
        if ($manifest.Count -ne $group.Files.Count) { throw "Unexpected entries in $($group.Manifest)" }
        foreach ($name in $group.Files) {
            $hash = (Get-FileHash -LiteralPath (Join-Path $dist $name) -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($manifest -cnotcontains "$hash  $name") { throw "Release checksum mismatch: $name" }
        }
    }
    if ((Get-FileHash -LiteralPath (Join-Path $dist $Bundle.Names[0])).Hash -cne
        (Get-FileHash -LiteralPath (Join-Path $dist $Bundle.Names[6])).Hash) { throw 'Legacy installer alias differs from the versioned package.' }
}

function Test-ReleaseArtifacts {
    param($Bundle, [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot))
    Test-ReleaseChecksums $Bundle $RepositoryRoot
    $dist = Join-Path $RepositoryRoot 'dist'
    foreach ($name in $Bundle.Names[1..2]) {
        $path = Join-Path $dist $name
        if ([Diagnostics.FileVersionInfo]::GetVersionInfo($path).FileVersion -cne $Bundle.Windows.Version) {
            throw "Windows DLL version mismatch: $name"
        }
        $reader = [IO.BinaryReader]::new([IO.File]::OpenRead($path))
        try {
            if ($reader.ReadUInt16() -ne 0x5a4d) { throw "Not a Windows PE DLL: $name" }
            $reader.BaseStream.Position = 0x3c
            $reader.BaseStream.Position = $reader.ReadInt32()
            if ($reader.ReadUInt32() -ne 0x4550) { throw "Invalid Windows PE signature: $name" }
            $machine = if ($name -like '*x64*') { 0x8664 } else { 0x14c }
            if ($reader.ReadUInt16() -ne $machine) { throw "Windows DLL architecture mismatch: $name" }
        } finally { $reader.Dispose() }
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $dist $Bundle.Names[0]))
    try {
        if ($archive.Entries.Count -ne 3) { throw 'Unexpected AIMP package entries.' }
        foreach ($entry in @(
            @{ Name='aimp_ignorant_shuffle/aimp_ignorant_shuffle.dll'; File=(Join-Path $dist $Bundle.Names[2]) },
            @{ Name='aimp_ignorant_shuffle/x64/aimp_ignorant_shuffle.dll'; File=(Join-Path $dist $Bundle.Names[1]) },
            @{ Name='aimp_ignorant_shuffle/LICENSE'; File=(Join-Path $RepositoryRoot 'LICENSE') }
        )) {
            $item = $archive.GetEntry($entry.Name)
            if (-not $item) { throw "Missing AIMP package entry: $($entry.Name)" }
            $stream = $item.Open(); $sha = [Security.Cryptography.SHA256]::Create()
            try { $hash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
            finally { $sha.Dispose(); $stream.Dispose() }
            if ($hash -cne (Get-FileHash -LiteralPath $entry.File -Algorithm SHA256).Hash) { throw "AIMP package differs from release files: $($entry.Name)" }
        }
    } finally { $archive.Dispose() }
    Write-Output "Verified Windows $($Bundle.Windows.Version): x64, x86, installer contents and checksums"
    $info = $Bundle.Android
    $repoRoot = $RepositoryRoot
    $apk = Join-Path $dist $Bundle.Names[4]
    $apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android\Sdk' }
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr' }
    $buildTools = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0'
    $previousErrorAction = $ErrorActionPreference
    try {
        # Windows PowerShell treats redirected Java warnings as NativeCommandError.
        # Judge the native process by its exit code, retaining stdout certificates.
        $ErrorActionPreference = 'Continue'
        $signed = @(& (Join-Path $buildTools 'apksigner.bat') verify --print-certs $apk 2>$null)
        $signatureExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorAction }
    if ($signatureExit -ne 0) { throw 'Android APK signature verification failed.' }
    $signer = @($signed | Select-String '^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]{64})$')
    $pin = (Get-Content -LiteralPath (Join-Path $repoRoot 'android\signing-certificate.sha256') -Raw).Trim()
    if ($pin -cnotmatch '^[a-f0-9]{64}$' -or $signer.Count -ne 1 -or
        @($signed | Select-String '^Signer #[0-9]+ certificate SHA-256 digest:').Count -ne 1 -or
        $signer[0].Matches[0].Groups[1].Value.ToLowerInvariant() -cne $pin) {
        throw 'APK does not match the pinned Android release signing certificate.'
    }
    $badging = @(& (Join-Path $buildTools 'aapt.exe') dump badging $apk)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Android APK metadata.' }
    $package = $badging | Where-Object { $_.StartsWith('package:') } | Select-Object -First 1
    if ($package -notmatch "name='dev\.ignorantshuffle\.android' versionCode='([0-9]+)' versionName='([^']+)'") { throw 'Unexpected Android application ID.' }
    if ([int]$Matches[1] -ne $info.VersionCode -or $Matches[2] -cne $info.Version) { throw 'Built APK does not match Android version metadata.' }
    Write-Output "Verified Android $($info.Version), code $($info.VersionCode), pinned signer, SHA256 $apkHash"
}
