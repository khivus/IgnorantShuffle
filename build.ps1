[CmdletBinding()]
param(
    [ValidateSet('x64', 'Win32', 'Both')][string]$Architecture = 'x64',
    [switch]$CoreOnly,
    [string]$SdkDir,
    [AllowEmptyString()][string]$UpdateRepository = 'khivus/IgnorantShuffle'
)
$ErrorActionPreference = 'Stop'
if (-not $SdkDir) { $SdkDir = Join-Path $PSScriptRoot '.deps\aimp_sdk_540' }
$cmakeCommand = Get-Command cmake -ErrorAction SilentlyContinue
if ($cmakeCommand) {
    $cmake = $cmakeCommand.Source
} else {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path -LiteralPath $vswhere)) { throw 'Install Visual Studio 2022 Build Tools with C++ and CMake support.' }
    $installation = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $installation) { throw 'No Visual Studio C++ toolchain found.' }
    $cmake = Join-Path $installation 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
    if (-not (Test-Path -LiteralPath $cmake)) { throw 'Install the C++ CMake tools component.' }
}
$ctest = Join-Path (Split-Path -Parent $cmake) 'ctest.exe'
$architectures = if ($Architecture -eq 'Both') { @('x64', 'Win32') } else { @($Architecture) }
foreach ($target in $architectures) {
    $suffix = if ($CoreOnly) { "core-$target" } else { $target }
    $buildDir = Join-Path $PSScriptRoot "build-$suffix"
    $plugin = if ($CoreOnly) { 'OFF' } else { 'ON' }
    & $cmake -S $PSScriptRoot -B $buildDir -G 'Visual Studio 17 2022' -A $target "-DBUILD_AIMP_PLUGIN=$plugin" "-DAIMP_SDK_DIR=$SdkDir" "-DIGNORANT_SHUFFLE_UPDATE_REPOSITORY=$UpdateRepository"
    if ($LASTEXITCODE -ne 0) { throw "CMake configuration failed for $target." }
    & $cmake --build $buildDir --config Release
    if ($LASTEXITCODE -ne 0) { throw "Build failed for $target." }
    & $ctest --test-dir $buildDir -C Release --output-on-failure
    if ($LASTEXITCODE -ne 0) { throw "Tests failed for $target." }
    if (-not $CoreOnly) { Write-Output "Plugin: $(Join-Path $buildDir 'Release\aimp_ignorant_shuffle.dll')" }
}
