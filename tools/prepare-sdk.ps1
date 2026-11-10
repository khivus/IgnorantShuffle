[CmdletBinding()]
param([string]$ArchivePath)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$deps = Join-Path $projectRoot '.deps'
New-Item -ItemType Directory -Force $deps | Out-Null
# Official AIMP 5.40 archives. The 2026-11-09 refresh changes only the two
# Help/*.chm files; all 121 other files (including every API header) match.
# Keep the original published pin for existing offline archives. Reject anything else.
$expectedHashes = @(
    'aa449fed93b93af7880a5580d71265231d048115e837b8d79f0c32aec59537a7',
    '5ecf7225f522a4c2de45b7b958d0edb4363b218caccfd141289bd5e675bb50d1'
)
if (-not $ArchivePath) {
    $ArchivePath = Join-Path $deps 'aimp_sdk.zip'
    Invoke-WebRequest -Uri 'https://aimp.ru/?do=download.file&id=15' -OutFile $ArchivePath -UseBasicParsing
}
$ArchivePath = (Resolve-Path -LiteralPath $ArchivePath).Path
$actualHash = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -notin $expectedHashes) {
    throw "SDK hash differs from the pinned AIMP 5.40 archives. Check the official SDK page before updating the pins. Actual SHA256: $actualHash"
}
$destination = Join-Path $deps 'aimp_sdk_540'
Expand-Archive -LiteralPath $ArchivePath -DestinationPath $destination -Force
if (-not (Test-Path -LiteralPath (Join-Path $destination 'Sources\Cpp\apiPlaylists.h'))) {
    throw "Unexpected SDK layout in $destination"
}
Write-Output "Verified AIMP SDK: $destination"
