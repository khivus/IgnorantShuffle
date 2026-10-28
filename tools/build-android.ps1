[CmdletBinding()]
param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'component-version.ps1')
$expected = Get-ComponentVersion android
$androidRoot = Join-Path $repoRoot 'android'
if (-not $env:ANDROID_HOME) {
    $sdkCandidate = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Android\Sdk'
    if (Test-Path -LiteralPath $sdkCandidate) { $env:ANDROID_HOME = $sdkCandidate }
    else { throw 'Set ANDROID_HOME to your installed Android SDK.' }
}
if (-not $env:JAVA_HOME) {
    $jdkCandidate = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path -LiteralPath $jdkCandidate) { $env:JAVA_HOME = $jdkCandidate }
}
$gradleArgs = @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug')
if ($Offline) { $gradleArgs = @('--offline') + $gradleArgs }
Push-Location $androidRoot
try {
    & .\gradlew.bat @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Android build/tests/lint failed.' }
} finally { Pop-Location }
$apk = Join-Path $androidRoot 'app\build\outputs\apk\debug\app-debug.apk'
$metadata = Get-Content -LiteralPath (Join-Path (Split-Path $apk) 'output-metadata.json') -Raw | ConvertFrom-Json
$version = $metadata.elements[0].versionName
if ($version -ne $expected.Version -or $metadata.elements[0].versionCode -ne $expected.VersionCode) {
    throw 'Built APK does not match versions/android.properties.'
}
$signer = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\apksigner.bat'
if (-not (Test-Path -LiteralPath $signer)) { throw 'Install Android Build Tools 36.0.0 to verify the APK.' }
& $signer verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$dist = Join-Path $repoRoot 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$name = "IgnorantShuffle-Android-v$version-debug.apk"
$destination = Join-Path $dist $name
Copy-Item -LiteralPath $apk -Destination $destination -Force
$hash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -LiteralPath (Join-Path $dist 'SHA256SUMS-Android.txt') -Value "$hash  $name" -Encoding ascii
Write-Output "Android APK: $destination"
Write-Output "SHA256: $hash"
