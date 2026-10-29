[CmdletBinding()]
param(
    [ValidateSet('windows', 'android', 'core')][string]$Component = 'windows',
    [switch]$Publish
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoRoot
. (Join-Path $PSScriptRoot 'release-bundle.ps1')
$bundle = Get-ReleaseBundle $Component
$repository = 'khivus/IgnorantShuffle'
$tag = $bundle.Tag
$assetNames = $bundle.Names
$notes = $bundle.Notes
$dist = Join-Path $repoRoot 'dist'
Test-ReleaseArtifacts $bundle
[IO.File]::WriteAllText((Join-Path $dist ("release-notes-" + $Component + ".md")), $notes, [Text.UTF8Encoding]::new($false))
if (-not $Publish) { Write-Output "Preflight only. Use -Publish to push main/tag and publish $tag with both applications."; return }

function Invoke-Git {
    param([string[]]$Arguments)
    & git -c "safe.directory=$repoRoot" -c credential.helper= @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Git operation failed; publication stopped.' }
}
if (@(Invoke-Git -Arguments @('status', '--porcelain')).Count) { throw 'Commit intended changes before publishing.' }
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
    $headers = @{ Authorization = 'Bearer ' + $credentialFields['password']; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2026-03-10'; 'User-Agent' = 'IgnorantShuffle-release' }
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
    $latestPublished = $null
    try { $latestPublished = Invoke-GitHub "/repos/$repository/releases/latest" }
    catch { if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw } }
    if ($latestPublished -and $latestPublished.tag_name -cmatch '^v(\d+\.\d+\.\d+)$' -and
        $latestPublished.tag_name -cne $tag -and [version]$Matches[1] -ge [version]$bundle.Release.Version) {
        throw 'Advance versions/release.properties beyond the latest stable bundle before publishing.'
    }
    $gitEnvironment = @{ GIT_CONFIG_COUNT='1'; GIT_CONFIG_KEY_0='http.https://github.com/.extraheader'; GIT_CONFIG_VALUE_0=$gitHeader; GIT_TERMINAL_PROMPT='0' }
    foreach ($key in $gitEnvironment.Keys) { Set-PublishEnvironment $key $gitEnvironment[$key] }
    Invoke-Git -Arguments @('fetch', 'origin', 'main')
    & git -c "safe.directory=$repoRoot" merge-base --is-ancestor FETCH_HEAD HEAD
    if ($LASTEXITCODE -ne 0) { throw 'Remote main is ahead/diverged; reconcile before publication.' }
    $tags = @(Invoke-Git -Arguments @('tag', '--list', $tag))
    if ($tags -contains $tag) {
        if ((Invoke-Git -Arguments @('rev-parse', "$tag^{}")).Trim() -ne $commit) { throw 'Existing component tag points to another commit.' }
    } else { Invoke-Git -Arguments @('tag', '-a', $tag, '-m', $bundle.Title, $commit) }
    Invoke-Git -Arguments @('push', 'origin', 'main')
    Invoke-Git -Arguments @('push', 'origin', "refs/tags/$tag")
    $release = $null
    try { $release = Invoke-GitHub ("/repos/$repository/releases/tags/" + [Uri]::EscapeDataString($tag)) }
    catch { if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw } }
    if (-not $release) {
        $release = Invoke-GitHub "/repos/$repository/releases" POST @{ tag_name=$tag; target_commitish=$commit;
            name=$bundle.Title; body=$notes; draft=$true; prerelease=$bundle.Preview; make_latest='false' }
    }
    if ([bool]$release.prerelease -ne $bundle.Preview) { throw 'Existing component release channel mismatch; stopped without altering it.' }
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
        $release = Invoke-GitHub "/repos/$repository/releases/$($release.id)" PATCH @{ name=$bundle.Title; body=$notes; draft=$false; prerelease=$bundle.Preview; make_latest=$(if ($bundle.Latest) { 'true' } else { 'false' }) }
    }
    $published = Invoke-GitHub ("/repos/$repository/releases/tags/" + [Uri]::EscapeDataString($tag))
    if ($published.draft -or [bool]$published.prerelease -ne $bundle.Preview -or $published.tag_name -cne $tag -or
        $published.body.Replace("`r`n", "`n") -cne $notes.Replace("`r`n", "`n")) { throw 'Published release metadata mismatch.' }
    foreach ($name in $assetNames) {
        $asset = $published.assets | Where-Object { $_.name -ceq $name } | Select-Object -First 1
        if (-not $asset) { throw "Missing public release asset: $name" }
        $download = Join-Path $dist ("verified-" + $Component + "-" + $name)
        Invoke-WebRequest -Uri $asset.browser_download_url -UseBasicParsing -OutFile $download -TimeoutSec 60
        if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath (Join-Path $dist $name) -Algorithm SHA256).Hash) { throw "Public download hash mismatch: $name" }
        Write-Output "Verified public download: $name"
    }
    [IO.File]::WriteAllText((Join-Path $dist ("release-" + $Component + ".json")), ($published | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    $latest = Invoke-GitHub "/repos/$repository/releases/latest"
    Write-Output "Repository Latest: $($latest.tag_name)"
    Write-Output "Published and verified: $($published.html_url)"
    Write-Output "Commit: $commit"
} finally {
    foreach ($key in $previousEnvironment.Keys) {
        if ($null -eq $previousEnvironment[$key]) { Remove-Item -LiteralPath "Env:$key" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($key, $previousEnvironment[$key], 'Process') }
    }
    $headers = $null; $gitHeader = $null; $credentialReply = $null; $credentialFields = $null; $gitEnvironment = $null
}
