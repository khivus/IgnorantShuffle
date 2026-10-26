[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$repositoryName = 'khivus/IgnorantShuffle'
$description = 'Weighted smart shuffle for AIMP on Windows, with track weights, cooldowns, portable weight storage, and a compact control panel.'
$cmakeText = [IO.File]::ReadAllText((Join-Path $projectRoot 'CMakeLists.txt'))
if ($cmakeText -notmatch 'project\(IgnorantShuffle VERSION ([0-9]+\.[0-9]+\.[0-9]+)') { throw 'Cannot read project version.' }
$version = $Matches[1]
$tag = "v$version"
$releaseNames = @("IgnorantShuffle-$tag.aimppack", 'aimp_ignorant_shuffle-x64.dll', 'aimp_ignorant_shuffle-x86.dll', 'SHA256SUMS.txt')
$releaseDirectory = Join-Path $projectRoot 'dist'
$releaseFiles = @($releaseNames | ForEach-Object { Join-Path $releaseDirectory $_ })
foreach ($file in $releaseFiles) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing release file: $file. Build both architectures and run tools/package-release.ps1 first." }
}
$notes = [IO.File]::ReadAllText((Join-Path $projectRoot 'RELEASE_NOTES.md'))
$manifest = Get-Content -LiteralPath (Join-Path $releaseDirectory 'SHA256SUMS.txt')
foreach ($file in $releaseFiles | Where-Object { [IO.Path]::GetFileName($_) -ne 'SHA256SUMS.txt' }) {
    $expectedLine = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() + '  ' + [IO.Path]::GetFileName($file)
    if ($manifest -notcontains $expectedLine) { throw "Release checksum mismatch: $file" }
}
# Credentials remain in process memory; never write them to files, Git config,
# command arguments, or console output. Use the existing signed-in GCM account.
$env:GCM_INTERACTIVE = 'Never'
$credentialReply = "protocol=https`nhost=github.com`nusername=khivus`n`n" | & git credential-manager get 2>$null
if ($LASTEXITCODE -ne 0) { throw 'No existing GitHub credential is available through Git Credential Manager.' }
$credentialFields = @{}
foreach ($line in $credentialReply) {
    $separator = $line.IndexOf('=')
    if ($separator -gt 0) { $credentialFields[$line.Substring(0, $separator)] = $line.Substring($separator + 1) }
}
if (-not $credentialFields['password']) { throw 'Git Credential Manager returned no GitHub credential.' }
$headers = @{ Authorization = 'Bearer ' + $credentialFields['password']; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28'; 'User-Agent' = 'IgnorantShuffle-release' }
$gitAuthorization = 'AUTHORIZATION: basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('khivus:' + $credentialFields['password']))
$credentialReply = $null; $credentialFields = $null
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
function Invoke-GitHub {
    param([string]$Path, [string]$Method = 'GET', [object]$Body)
    $arguments = @{ Uri = 'https://api.github.com' + $Path; Method = $Method; Headers = $headers; TimeoutSec = 60 }
    if ($null -ne $Body) { $arguments.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20)); $arguments.ContentType = 'application/json; charset=utf-8' }
    Invoke-RestMethod @arguments
}
function Invoke-Git {
    param([string[]]$Arguments)
    # An empty -c value is preserved in argv on Windows. An empty environment
    # value is removed by .NET/Windows PowerShell and breaks GIT_CONFIG_COUNT.
    & git -c "safe.directory=$projectRoot" -c credential.helper= @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Git operation failed; publishing stopped.' }
}
$account = Invoke-GitHub -Path '/user'
if ($account.login -ine 'khivus') { throw 'The authenticated GitHub account is not khivus; publishing stopped.' }
Write-Output "Authenticated GitHub account: $($account.login)"
try { $repository = Invoke-GitHub -Path "/repos/$repositoryName" }
catch {
    if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw }
    $repository = Invoke-GitHub -Path '/user/repos' -Method POST -Body @{ name = 'IgnorantShuffle'; description = $description; private = $false; auto_init = $false; has_issues = $true }
}
if ($repository.full_name -ine $repositoryName -or $repository.private) { throw 'The target repository is unexpected or private; publishing stopped.' }
Write-Output "Repository: $($repository.html_url)"
if (-not (Test-Path -LiteralPath (Join-Path $projectRoot '.git'))) { Invoke-Git -Arguments @('init', '-b', 'main') }
Invoke-Git -Arguments @('add', '--', '.gitignore', 'CMakeLists.txt', 'README.md', 'CHANGELOG.md', 'LICENSE', 'RELEASE_NOTES.md', 'build.ps1', 'docs', 'examples', 'src', 'tests', 'tools')
Invoke-Git -Arguments @('diff', '--cached', '--check')
& git -c "safe.directory=$projectRoot" diff --cached --quiet
if ($LASTEXITCODE -eq 1) { Invoke-Git -Arguments @('commit', '-m', "feat: release IgnorantShuffle v$version") }
elseif ($LASTEXITCODE -ne 0) { throw 'Cannot inspect staged changes.' }
$commit = (Invoke-Git -Arguments @('rev-parse', 'HEAD')).Trim()
$branch = (Invoke-Git -Arguments @('branch', '--show-current')).Trim()
if ($branch -ne 'main') { throw 'Expected local main branch; publishing stopped.' }
$remotes = @(Invoke-Git -Arguments @('remote'))
if ($remotes -contains 'origin') {
    $origin = (Invoke-Git -Arguments @('remote', 'get-url', 'origin')).Trim()
    if ($origin -ne "https://github.com/$repositoryName.git") { throw 'Existing origin does not match the requested repository.' }
} else { Invoke-Git -Arguments @('remote', 'add', 'origin', "https://github.com/$repositoryName.git") }
# Supply authentication to Git through the child process environment, avoiding
# helper shell invocation and avoiding credential storage in repository config.
$previousGitEnvironment = @{}
$gitEnvironment = @{ GIT_CONFIG_COUNT = '1'; GIT_CONFIG_KEY_0 = 'http.https://github.com/.extraheader'; GIT_CONFIG_VALUE_0 = $gitAuthorization; GIT_TERMINAL_PROMPT = '0' }
foreach ($key in $gitEnvironment.Keys) { $previousGitEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process'); [Environment]::SetEnvironmentVariable($key, $gitEnvironment[$key], 'Process') }
try {
    Invoke-Git -Arguments @('push', '-u', 'origin', 'main')
    $existingTags = @(Invoke-Git -Arguments @('tag', '--list', $tag))
    if ($existingTags -notcontains $tag) { Invoke-Git -Arguments @('tag', '-a', $tag, '-m', "IgnorantShuffle $tag", $commit) }
    $tagCommit = (Invoke-Git -Arguments @('rev-parse', "$tag^{}")).Trim()
    if ($tagCommit -ne $commit) { throw 'Existing release tag refers to a different commit.' }
    Invoke-Git -Arguments @('push', 'origin', "refs/tags/$tag")
} finally {
    foreach ($key in $previousGitEnvironment.Keys) { [Environment]::SetEnvironmentVariable($key, $previousGitEnvironment[$key], 'Process') }
    $gitAuthorization = $null; $gitEnvironment = $null
}
$repository = Invoke-GitHub -Path "/repos/$repositoryName" -Method PATCH -Body @{ description = $description; default_branch = 'main' }
$releases = @(Invoke-GitHub -Path "/repos/$repositoryName/releases?per_page=100")
$release = $releases | Where-Object { $_.tag_name -eq $tag } | Select-Object -First 1
if (-not $release) { $release = Invoke-GitHub -Path "/repos/$repositoryName/releases" -Method POST -Body @{ tag_name = $tag; target_commitish = $commit; name = "IgnorantShuffle $tag"; body = $notes; draft = $true; prerelease = $false } }
foreach ($file in $releaseFiles) {
    $name = [IO.Path]::GetFileName($file)
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    $asset = $release.assets | Where-Object { $_.name -ceq $name } | Select-Object -First 1
    if (-not $asset) {
        if (-not $release.draft) { throw "Published release is missing $name; publishing stopped without altering it." }
        $upload = $release.upload_url.Split('{')[0] + '?name=' + [Uri]::EscapeDataString($name)
        $asset = Invoke-RestMethod -Uri $upload -Method POST -Headers $headers -ContentType 'application/octet-stream' -InFile $file -TimeoutSec 60
    }
    for ($attempt = 0; -not $asset.digest -and $attempt -lt 10; $attempt++) {
        Start-Sleep -Seconds 1
        $asset = Invoke-GitHub -Path "/repos/$repositoryName/releases/assets/$($asset.id)"
    }
    if ($asset.state -ne 'uploaded' -or $asset.size -ne (Get-Item -LiteralPath $file).Length -or $asset.digest -ne "sha256:$hash") { throw "GitHub asset verification failed: $name. Draft was not published." }
    Write-Output "Verified release asset: $name"
}
if ($release.draft) { $release = Invoke-GitHub -Path "/repos/$repositoryName/releases/$($release.id)" -Method PATCH -Body @{ body = $notes; draft = $false; make_latest = 'true' } }
$latest = Invoke-GitHub -Path "/repos/$repositoryName/releases/latest"
if ($latest.tag_name -ne $tag -or $latest.draft -or $latest.prerelease) { throw 'Latest stable release verification failed.' }
foreach ($file in $releaseFiles) {
    $name = [IO.Path]::GetFileName($file)
    $asset = $latest.assets | Where-Object { $_.name -ceq $name } | Select-Object -First 1
    if (-not $asset) { throw "Published release is missing $name." }
    $download = Join-Path $releaseDirectory ("verified-" + $name)
    Invoke-WebRequest -Uri $asset.browser_download_url -UseBasicParsing -OutFile $download -TimeoutSec 60
    if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash) { throw "Published download verification failed: $name" }
}
[IO.File]::WriteAllText((Join-Path $releaseDirectory 'release-latest.json'), ($latest | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
Write-Output "Published and verified: $($latest.html_url)"
Write-Output "Commit: $commit"
