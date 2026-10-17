param(
    [string]$AvdName = "Pixel_Tablet",
    [string]$Serial = "emulator-5554",
    [ValidateSet("all", "phone", "tablet")]
    [string]$Profile = "all",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "D:\Program Files\Android\Android-SDK" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emulator = Join-Path $sdk "emulator\emulator.exe"
$avdHome = Join-Path $sdk ".android\avd"
$package = "com.newoether.agora.screenshots"
$activity = "$package/com.newoether.agora.MainActivity"
$conversationId = "7a294d3f-b710-4c8c-8e52-574825f3012e"
$imagesRoot = Join-Path $repo "fastlane\metadata\android\en-US\images"
$phoneOutput = Join-Path $imagesRoot "phoneScreenshots"
$tabletOutput = Join-Path $imagesRoot "tenInchScreenshots"
$assets = Join-Path $repo "assets"
$apk = Join-Path $repo "app\build\outputs\apk\play\debug\app-play-debug.apk"

function Invoke-Adb([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments) {
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $adb -s $Serial @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($exitCode -ne 0) { throw "adb failed: $($Arguments -join ' ')`n$($output -join "`n")" }
    return $output
}

function Wait-ForBoot {
    $deadline = (Get-Date).AddMinutes(4)
    $state = ""
    $boot = ""
    do {
        Start-Sleep -Seconds 2
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        $state = (& $adb -s $Serial get-state 2>$null | Out-String).Trim()
        $boot = (& $adb -s $Serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        $ErrorActionPreference = $previousPreference
    } while ((($state -ne "device") -or ($boot -ne "1")) -and (Get-Date) -lt $deadline)
    if ($state -ne "device" -or $boot -ne "1") { throw "Emulator did not boot" }
}

function Get-GlobalSetting([string]$Name) {
    return ((& $adb -s $Serial shell settings get global $Name) | Out-String).Trim()
}
function Restore-GlobalSetting([string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") {
        Invoke-Adb shell settings delete global $Name | Out-Null
    } else {
        Invoke-Adb shell settings put global $Name $Value | Out-Null
    }
}
function Set-CaptureProfile([string]$Profile) {
    if ($Profile -eq "phone") {
        Invoke-Adb shell wm size 1216x2400 | Out-Null
        Invoke-Adb shell wm density 420 | Out-Null
        Invoke-Adb shell wm user-rotation lock 0 | Out-Null
    } else {
        Invoke-Adb shell wm size 2560x1600 | Out-Null
        Invoke-Adb shell wm density 320 | Out-Null
        Invoke-Adb shell wm user-rotation lock 0 | Out-Null
    }
    Start-Sleep -Seconds 2
}

function Capture-Screenshot(
    [int]$Index,
    [string]$Destination,
    [string]$Profile,
    [string]$OutputDirectory
) {
    Invoke-Adb shell am force-stop $package
    $startArguments = @(
        "shell", "am", "start", "-W", "-S", "--activity-clear-task", "-n", $activity,
        "--es", "com.newoether.agora.extra.SCREENSHOT_DESTINATION", $Destination,
        "--es", "com.newoether.agora.extra.CONVERSATION_ID", $conversationId
    )
    Invoke-Adb @startArguments | Out-Null
    Start-Sleep -Seconds 5

    if ($Destination -eq "chat") {
        1..6 | ForEach-Object {
            Invoke-Adb shell input swipe 608 520 608 1900 250 | Out-Null
            Start-Sleep -Milliseconds 150
        }
    }

    if ($Profile -eq "tablet") {
        $navigationSwipes = switch ($Destination) {
            "settings:shell" { 1 }
            "settings:memory" { 2 }
            "settings:datacontrol" { 2 }
            default { 0 }
        }
        if ($navigationSwipes -gt 0) {
            1..$navigationSwipes | ForEach-Object {
                Invoke-Adb shell input swipe 400 1400 400 420 500 | Out-Null
                Start-Sleep -Milliseconds 800
            }
        }
    }
    # ESC hides an IME without dismissing the drawer or a settings destination.
    Invoke-Adb shell input keyevent 111 | Out-Null
    Start-Sleep -Milliseconds 600

    $stem = "agora-$Profile-screenshot-$Index"
    $png = Join-Path $env:TEMP "$stem.png"
    $jpg = Join-Path $OutputDirectory "screenshot_$Index.jpg"
    $remotePng = "/data/local/tmp/$stem.png"
    $captureArguments = @("shell", "screencap", "-p", $remotePng)
    Invoke-Adb @captureArguments | Out-Null
    Invoke-Adb pull $remotePng $png | Out-Null
    Invoke-Adb shell rm -f $remotePng | Out-Null

    if ($Profile -eq "tablet") {
        & ffmpeg -y -loglevel error -i $png -q:v 2 $jpg
    } else {
        $filter = "delogo=x=498:y=30:w=220:h=36:show=0,delogo=x=398:y=2360:w=420:h=38:show=0"
        & ffmpeg -y -loglevel error -i $png -vf $filter -q:v 2 $jpg
    }
    if ($LASTEXITCODE -ne 0) { throw "Unable to encode $Profile screenshot $Index" }
    Remove-Item $png -Force
}

foreach ($directory in @($phoneOutput, $tabletOutput, $assets)) {
    if (-not (Test-Path $directory)) { New-Item -ItemType Directory -Path $directory | Out-Null }
}

if (-not $SkipBuild) {
    $env:GRADLE_USER_HOME = "C:\Users\newoether\.gradle"
    & (Join-Path $repo "gradlew.bat") :app:assemblePlayDebug --gradle-user-home $env:GRADLE_USER_HOME
    if ($LASTEXITCODE -ne 0) { throw "Debug APK build failed" }
}
if (-not (Test-Path $apk)) { throw "Debug APK not found: $apk" }

$connected = (& $adb devices | Out-String) -match "(?m)^$([regex]::Escape($Serial))\s+device$"
if (-not $connected) {
    $env:ANDROID_AVD_HOME = $avdHome
    Start-Process -FilePath $emulator -ArgumentList @(
        "-avd", $AvdName,
        "-no-snapshot-save",
        "-no-boot-anim",
        "-no-audio",
        "-no-metrics",
        "-gpu", "host"
    ) | Out-Null
}
Wait-ForBoot
$animationSettings = @(
    "window_animation_scale",
    "transition_animation_scale",
    "animator_duration_scale"
)
$savedAnimationSettings = @{}
foreach ($setting in $animationSettings) {
    $savedAnimationSettings[$setting] = Get-GlobalSetting $setting
}
try {
    Invoke-Adb shell settings put system accelerometer_rotation 0 | Out-Null
    Invoke-Adb shell settings put secure stylus_handwriting_enabled 0 | Out-Null
    Invoke-Adb shell settings put secure stylus_pointer_icon_enabled 0 | Out-Null
    foreach ($setting in $animationSettings) {
        Invoke-Adb shell settings put global $setting 0 | Out-Null
    }
    Invoke-Adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural | Out-Null
    Invoke-Adb shell cmd overlay enable com.android.internal.systemui.navbar.transparent | Out-Null
    Invoke-Adb install -r -t $apk | Out-Null
    Invoke-Adb shell pm clear $package | Out-Null
    Invoke-Adb shell pm grant $package android.permission.POST_NOTIFICATIONS | Out-Null
    $destinations = @(
        "chat",
        "drawer",
        "settings",
        "settings:shell",
        "settings:memory",
        "settings:datacontrol"
    )
    $profiles = @(
        @{ Name = "phone"; Output = $phoneOutput },
        @{ Name = "tablet"; Output = $tabletOutput }
    ) | Where-Object { $Profile -eq "all" -or $_.Name -eq $Profile }
    foreach ($captureProfile in $profiles) {
        Set-CaptureProfile -Profile $captureProfile.Name
        for ($index = 0; $index -lt $destinations.Count; $index++) {
            Capture-Screenshot `
                -Index ($index + 1) `
                -Destination $destinations[$index] `
                -Profile $captureProfile.Name `
                -OutputDirectory $captureProfile.Output
        }
    }
    1..3 | ForEach-Object {
        Copy-Item (Join-Path $phoneOutput "screenshot_$_.jpg") (Join-Path $assets "screenshot_$_.jpg") -Force
    }
    $generatedProfiles = ($profiles | ForEach-Object Name) -join ", "
    Write-Host "Generated six screenshots for: $generatedProfiles. Synchronized README screenshots 1-3 from the phone set."
} finally {
    foreach ($setting in $animationSettings) {
        Restore-GlobalSetting $setting $savedAnimationSettings[$setting]
    }
}
