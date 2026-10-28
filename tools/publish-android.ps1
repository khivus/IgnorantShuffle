[CmdletBinding()]
param([switch]$Publish)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
. (Join-Path $PSScriptRoot 'component-version.ps1')
$info = Get-ComponentVersion android
$repository = 'khivus/IgnorantShuffle'
$tag = $info.Tag
$apkName = "IgnorantShuffle-Android-v$($info.Version)-debug.apk"
$assetNames = @($apkName, 'SHA256SUMS-Android.txt')
$dist = Join-Path $repoRoot 'dist'
foreach ($name in $assetNames) {
    if (-not (Test-Path -LiteralPath (Join-Path $dist $name) -PathType Leaf)) { throw "Missing Android release asset: $name" }
}
$notes = [IO.File]::ReadAllText((Join-Path $repoRoot 'android\RELEASE_NOTES.md'))
if ($notes.Split("`n")[0].TrimEnd("`r") -cne "# IgnorantShuffle Android v$($info.Version)") { throw 'Android release notes/version mismatch.' }
$apk = Join-Path $dist $apkName
$apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
if (@(Get-Content -LiteralPath (Join-Path $dist 'SHA256SUMS-Android.txt')) -cnotcontains "$apkHash  $apkName") { throw 'Android checksum manifest mismatch.' }
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
if (-not $Publish) { Write-Output "Preflight only. Use -Publish to push main/tag and publish the Android preview $tag."; return }

function Invoke-Git {
    param([string[]]$Arguments)
    & git -c "safe.directory=$repoRoot" -c credential.helper= @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Git operation failed; Android publication stopped.' }
}
if (@(Invoke-Git -Arguments @('status', '--porcelain')).Count) { throw 'Commit intended changes before publishing Android.' }
if ((Invoke-Git -Arguments @('branch', '--show-current')).Trim() -ne 'main') { throw 'Expected main branch.' }
if ((Invoke-Git -Arguments @('remote', 'get-url', 'origin')).Trim() -cne "https://github.com/$repository.git") { throw 'Unexpected origin.' }
$commit = (Invoke-Git -Arguments @('rev-parse', 'HEAD')).Trim()
$previousEnvironment = @{}
function Set-PublishEnvironment([string]$Name, [string]$Value) {
    if (-not $previousEnvironment.ContainsKey($Name)) { $previousEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process') }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
try {
    # Credentials stay in memory, never in files, output or command arguments.
    Set-PublishEnvironment GCM_INTERACTIVE Never
    $credentialReply = "protocol=https`nhost=github.com`nusername=khivus`n`n" | & git credential-manager get 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'No existing GitHub credential available through Git Credential Manager.' }
    $credentialFields = @{}
    foreach ($line in $credentialReply) {
        $separator = $line.IndexOf('=')
        if ($separator -gt 0) { $credentialFields[$line.Substring(0, $separator)] = $line.Substring($separator + 1) }
    }
    if (-not $credentialFields['password']) { throw 'No GitHub credential was returned.' }
    $headers = @{ Authorization = 'Bearer ' + $credentialFields['password']; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2026-03-10'; 'User-Agent' = 'IgnorantShuffle-Android-release' }
    $gitHeader = 'AUTHORIZATION: basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('khivus:' + $credentialFields['password']))
    $credentialReply = $null; $credentialFields = $null
    function Invoke-GitHub {
        param([string]$Path, [string]$Method = 'GET', [object]$Body)
        $arguments = @{ Uri = 'https://api.github.com' + $Path; Method = $Method; Headers = $headers; TimeoutSec = 60 }
        if ($null -ne $Body) { $arguments.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20)); $arguments.ContentType = 'application/json; charset=utf-8' }
        Invoke-RestMethod @arguments
    }
    $account = Invoke-GitHub '/user'
    if ($account.login -ine 'khivus') { throw 'Unexpected authenticated GitHub account.' }
    $remoteRepo = Invoke-GitHub "/repos/$repository"
    if ($remoteRepo.full_name -ine $repository -or $remoteRepo.private) { throw 'Unexpected/private target repository.' }
    Write-Output "Authenticated account: $($account.login)"
    Set-PublishEnvironment GIT_CONFIG_COUNT '1'
    Set-PublishEnvironment GIT_CONFIG_KEY_0 'http.https://github.com/.extraheader'
    Set-PublishEnvironment GIT_CONFIG_VALUE_0 $gitHeader
    Set-PublishEnvironment GIT_TERMINAL_PROMPT '0'
    Invoke-Git -Arguments @('fetch', 'origin', 'main')
    & git -c "safe.directory=$repoRoot" merge-base --is-ancestor FETCH_HEAD HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Remote main is ahead/diverged; reconcile before publication.' }
    $tags = @(Invoke-Git -Arguments @('tag', '--list', $tag))
    if ($tags -contains $tag) {
        if ((Invoke-Git -Arguments @('rev-parse', "$tag^{}")).Trim() -ne $commit) { throw 'Existing Android tag points to another commit.' }
    } else { Invoke-Git -Arguments @('tag', '-a', $tag, '-m', "IgnorantShuffle Android v$($info.Version) preview", $commit) }
    Invoke-Git -Arguments @('push', 'origin', 'main')
    Invoke-Git -Arguments @('push', 'origin', "refs/tags/$tag")
    $release = $null
    try { $release = Invoke-GitHub ("/repos/$repository/releases/tags/" + [Uri]::EscapeDataString($tag)) }
    catch { if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw } }
    if (-not $release) {
        $release = Invoke-GitHub "/repos/$repository/releases" POST @{ tag_name=$tag; target_commitish=$commit;
            name="IgnorantShuffle Android v$($info.Version) (preview)"; body=$notes; draft=$true; prerelease=$true; make_latest='false' }
    }
    if (-not $release.prerelease) { throw 'Existing Android release is not a preview; stopped without altering it.' }
    foreach ($name in $assetNames) {
        $path = Join-Path $dist $name
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        $asset = $release.assets | Where-Object { $_.name -ceq $name } | Select-Object -First 1
        if (-not $asset) {
            if (-not $release.draft) { throw "Published release is missing $name; not altering published assets." }
            $upload = $release.upload_url.Split('{')[0] + '?name=' + [Uri]::EscapeDataString($name)
            $asset = Invoke-RestMethod -Uri $upload -Method POST -Headers $headers -ContentType 'application/octet-stream' -InFile $path -TimeoutSec 60
        }
        for ($attempt=0; -not $asset.digest -and $attempt -lt 10; $attempt++) {
            Start-Sleep -Seconds 1
            $asset = Invoke-GitHub "/repos/$repository/releases/assets/$($asset.id)"
        }
        if ($asset.state -ne 'uploaded' -or $asset.size -ne (Get-Item -LiteralPath $path).Length -or $asset.digest -cne "sha256:$hash") {
            throw "Asset verification failed: $name. Draft not published."
        }
        Write-Output "Verified uploaded asset: $name"
    }
    if ($release.draft) {
        $release = Invoke-GitHub "/repos/$repository/releases/$($release.id)" PATCH @{ body=$notes; draft=$false; prerelease=$true; make_latest='false' }
    }
    $published = Invoke-GitHub ("/repos/$repository/releases/tags/" + [Uri]::EscapeDataString($tag))
    if ($published.draft -or -not $published.prerelease -or $published.tag_name -cne $tag -or
        $published.body.Replace("`r`n", "`n") -cne $notes.Replace("`r`n", "`n")) { throw 'Published Android release metadata mismatch.' }
    foreach ($name in $assetNames) {
        $asset = $published.assets | Where-Object { $_.name -ceq $name } | Select-Object -First 1
        if (-not $asset) { throw "Missing public Android asset: $name" }
        $download = Join-Path $dist ("verified-Android-" + $name)
        Invoke-WebRequest -Uri $asset.browser_download_url -UseBasicParsing -OutFile $download -TimeoutSec 60
        if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath (Join-Path $dist $name) -Algorithm SHA256).Hash) { throw "Public download hash mismatch: $name" }
        Write-Output "Verified public download: $name"
    }
    [IO.File]::WriteAllText((Join-Path $dist 'release-android.json'), ($published | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    $latest = Invoke-GitHub "/repos/$repository/releases/latest"
    Write-Output "Repository Latest remains: $($latest.tag_name)"
    Write-Output "Published and verified: $($published.html_url)"
    Write-Output "Commit: $commit"
} finally {
    foreach ($key in $previousEnvironment.Keys) {
        if ($null -eq $previousEnvironment[$key]) { Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process') }
    }
    $headers = $null; $gitHeader = $null; $credentialReply = $null; $credentialFields = $null
}
