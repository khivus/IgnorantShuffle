[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DeviceSerial,
    [string]$ApkPath,
    [ValidateRange(1, 20)][int]$ColdStarts = 3
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $ApkPath) { $ApkPath = Join-Path $repoRoot 'android\app\build\outputs\apk\debug\app-debug.apk' }
$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
$package = 'dev.ignorantshuffle.android'
$component = "$package/dev.ignorantshuffle.companion.MainActivity"
function Invoke-Adb {
    $response = & $adb -s $DeviceSerial @args 2>&1
    if ($LASTEXITCODE -ne 0) { throw "adb failed ($args): $($response -join "`n")" }
    return ($response -join "`n").Trim()
}
function Assert-Running([string]$Marker, [string]$Phase) {
    Start-Sleep -Seconds 2
    $logs = Invoke-Adb logcat -d -v brief 'IgnorantShuffleSmoke:I' 'AndroidRuntime:E' '*:S'
    $markerIndex = $logs.LastIndexOf($Marker, [StringComparison]::Ordinal)
    if ($markerIndex -lt 0) { throw 'Startup log marker was not captured' }
    $relevant = $logs.Substring($markerIndex)
    if ($relevant -match 'Process: dev\.ignorantshuffle\.android,') { throw "$Phase crashed:`n$relevant" }
    $appPid = Invoke-Adb shell pidof $package
    if (-not $appPid) { throw "$Phase left no running app process`n$relevant" }
    $activities = Invoke-Adb shell dumpsys activity activities
    if ($activities -notmatch 'mResumedActivity:.*dev\.ignorantshuffle\.android|topResumedActivity=.*dev\.ignorantshuffle\.android') {
        throw "$Phase did not leave the app activity resumed"
    }
    Write-Output "$Phase passed (PID $appPid)"
}
if ((Invoke-Adb shell getprop sys.boot_completed) -ne '1') { throw 'Wait for the selected device to finish booting' }
Write-Output (Invoke-Adb install -r (Resolve-Path -LiteralPath $ApkPath).Path)
for ($attempt = 1; $attempt -le $ColdStarts; $attempt++) {
    $marker = 'IgnorantShuffle-startup-' + [guid]::NewGuid().ToString('N')
    $null = Invoke-Adb shell am force-stop $package
    $null = Invoke-Adb shell log -t IgnorantShuffleSmoke $marker
    $null = Invoke-Adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $component
    Assert-Running $marker "Cold start $attempt"
    $null = Invoke-Adb shell input keyevent KEYCODE_HOME
    # Input injection returns before the asynchronous Home transition finishes.
    # Wait until it completes so Home cannot steal focus after the next launch.
    $backgrounded = $false
    for ($poll = 0; $poll -lt 20; $poll++) {
        $activities = Invoke-Adb shell dumpsys activity activities
        if ($activities -notmatch 'mResumedActivity:.*dev\.ignorantshuffle\.android|topResumedActivity=.*dev\.ignorantshuffle\.android') {
            $backgrounded = $true; break
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $backgrounded) { throw 'The Home transition did not finish' }
    $null = Invoke-Adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $component
    Assert-Running $marker "Resume $attempt"
}
Write-Output 'Android startup/resume smoke checks passed. App data was retained.'
