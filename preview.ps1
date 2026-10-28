<#
.SYNOPSIS
    MiuList preview orchestrator: build -> boot -> install -> launch.

.DESCRIPTION
    Does the whole "I want to see the app" loop in one shot, and does it
    without hardcoding anything about this particular machine:

      * Toolchain (SDK / emulator / Gradle / JDK) is discovered instead of
        being pinned to absolute paths, so moving the SDK or upgrading
        Gradle does not silently break the launcher.
      * The APK is located by globbing the debug output dir, and the
        applicationId is read from build.gradle.kts, so renaming the package
        does not break the install step.
      * The emulator is only started when no device is already attached, and
        boot is awaited with a single in-device shell loop plus a heartbeat
        instead of polling getprop from Windows every five seconds.
      * The app is actually launched after install - a preview you have to
        tap open by hand is only half a preview.
      * On failure the device logcat is dumped next to this script so the
        crash is on disk instead of only in a scrollback buffer.

    start-preview.bat is the double-clickable wrapper around this script and
    forwards its arguments, e.g.  start-preview.bat -SkipBuild

.PARAMETER SkipBuild
    Do not run Gradle; install whatever APK is already in the output dir.

.PARAMETER ForceBuild
    Run Gradle even if -SkipBuild was implied by a previous run. (Gradle's
    own up-to-date checks apply; a clean build is -CleanBuild.)

.PARAMETER NoLaunch
    Install but do not start the app.

.PARAMETER NoEmulator
    Never start an AVD. Fail instead if no device is attached - use this when
    you are previews on a physical phone.

.PARAMETER Headless
    Boot the AVD with -no-window. Useful for scripted checks and on a machine
    where the emulator window is not wanted; everything else is unchanged.

.PARAMETER ColdBoot
    Boot the AVD with -no-snapshot-load -no-snapshot-save. Slower to start, but
    it gets past a snapshot whose emulated storage or GPU state has wedged - the
    usual cause of an `adb install` that never returns, or of a screen that
    stays black, on a freshly started emulator.

    Saving is suppressed on purpose. `quickbootChoice.ini` defaults to
    saveOnExit, so a wedged run used to write its own broken state back into
    default_boot and every later start reproduced it.

.PARAMETER Gpu
    Renderer passed to the emulator as `-gpu <mode>`: auto, host, software,
    lavapipe, swiftshader, or swangle. Overrides the AVD's own setting. Use it
    to fall back to a renderer that does not touch the host GPU when the screen
    comes up black - `swiftshader` is the usual next step after `host`.
    Not every emulator build accepts every mode; check `emulator -help-gpu`.

.PARAMETER Device
    adb serial to target. Required only when several devices are attached.

.PARAMETER Avd
    AVD name to boot. Defaults to MiuList_Preview.

.PARAMETER BootTimeoutSec
    Give up waiting for Android to finish booting after this long.

.PARAMETER ReadyTimeoutSec
    Give up waiting for the device to accept installs after this long. The
    boot flag and the package manager both have to be up before an install
    can succeed.

.PARAMETER InstallTimeoutSec
    How long a single `adb install` may take before it is called hung.

.PARAMETER Reinstall
    Remove the app from the device before installing, so the preview starts
    from an empty state. Without it, an existing app keeps its data.

.PARAMETER NoAutoRecover
    Do not print the recovery advice. The script never removes an app from
    the device by itself; with this switch it also does not suggest doing
    it either.

.PARAMETER Display
    Which display profile the emulator is put on before the app is
    launched:

      phone (default) - 用户真机那一档: 1080x2414 @520dpi = 332x743 dp,
                        system font 1.16. The same profile
                        tools/emulator.sh start applies, and the one the
                        adaptive layout is judged on.
      base            - 设计基准: 1080x2400 @420dpi = 411x914 dp, font
                        1.0. The configuration the design was drawn at.
      keep            - leave the display exactly as it is.

    A screenshot matrix run can leave the device on a third, useless
    profile (1600x1200, 640x1366, or a font at 2.0), and the preview then
    shows a layout nobody asked for. A real phone is never touched - its
    own ratio and font are the normal ones.

.PARAMETER DryRun
    Resolve and print the plan, then exit without building, booting or
    installing anything.

.EXAMPLE
    .\preview.ps1

.EXAMPLE
    .\preview.ps1 -SkipBuild -NoLaunch

.EXAMPLE
    .\preview.ps1 -DryRun
#>
[CmdletBinding()]
param(
    [switch] $SkipBuild,
    [switch] $NoLaunch,
    [switch] $NoEmulator,
    [switch] $Headless,
    [switch] $RestartEmulator,
    [switch] $ColdBoot,
    [switch] $Reinstall,
    [switch] $NoAutoRecover,
    [switch] $DryRun,
    [string] $Device = '',
    [string] $Avd = 'MiuList_Preview',
    [string] $Gpu = '',
    [ValidateSet('phone', 'base', 'keep')]
    [string] $Display = 'phone',
    [int]    $BootTimeoutSec = 300,
    [int]    $ReadyTimeoutSec = 180,
    [int]    $InstallTimeoutSec = 180,
    [string] $ProjectDir = ''
)

$ErrorActionPreference = 'Stop'

# $PSScriptRoot is not dependable inside a param() default value in Windows
# PowerShell 5.1, so resolve the project directory here instead.
$ScriptRoot = $PSScriptRoot
if (-not $ScriptRoot) { $ScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $ProjectDir) {
    # 自举项目目录：脚本现在住在**仓库根目录**，$ScriptRoot 就是项目目录。
    # 但它也可能被挪到更深的目录（tools/…），所以往上找**带 app\build.gradle.kts 的那一层**。
    # 找不到就退回 $ScriptRoot。
    #
    # 旧写法是 `Join-Path $ScriptRoot 'MiuList'`（脚本在仓库**外面**、靠同名子目录定位），
    # 脚本进了仓库之后那条假设就不成立了 —— 而且它把脚本位置和目录名焊死了。
    $probe = $ScriptRoot
    while ($probe) {
        if (Test-Path (Join-Path $probe 'app\build.gradle.kts')) { break }
        $parent = Split-Path -Parent $probe
        if (-not $parent -or $parent -eq $probe) { $probe = ''; break }
        $probe = $parent
    }
    $ProjectDir = if ($probe) { $probe } else { $ScriptRoot }
}

$TotalSteps = 5
$Watch = [System.Diagnostics.Stopwatch]::StartNew()

# --------------------------------------------------------------------------
# Console output helpers
# --------------------------------------------------------------------------
function Write-Step {
    param([string] $Message)
    Write-Host ''
    Write-Host ("=== {0} ===" -f $Message) -ForegroundColor Cyan
}
function Write-Info { param([string] $m) Write-Host ("      " + $m) }
function Write-Good { param([string] $m) Write-Host ("  OK  " + $m) -ForegroundColor Green }
function Write-Warn { param([string] $m) Write-Host ("  !!  " + $m) -ForegroundColor Yellow }
function Write-Bad  { param([string] $m) Write-Host ("  XX  " + $m) -ForegroundColor Red }

function Write-Take {
    param([double] $Since)
    Write-Host ("      took {0:n1}s" -f ($Watch.Elapsed.TotalSeconds - $Since)) -ForegroundColor DarkGray
}

# Runs a native exe and captures stdout+stderr without $ErrorActionPreference
# turning native stderr into a terminating error. The exit code is published
# in $script:LastNativeExit rather than relying on $LASTEXITCODE, whose scope
# inside a function is not dependable.
$script:LastNativeExit = 0
function Invoke-Native {
    param([string] $Exe, [string[]] $NativeArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $result = & $Exe @NativeArgs 2>&1
        $script:LastNativeExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prev
    }
    return ($result | ForEach-Object { $_.ToString() }) -join "`n"
}

# Builds an adb argument list, omitting -s when the serial is not known yet.
function Get-AdbArgs {
    param([string] $Serial, [string[]] $Rest)
    if ($Serial) { return (@('-s', $Serial) + $Rest) }
    return $Rest
}

# Quotes one argument for a Windows command line. ProcessStartInfo on
# .NET Framework (what PowerShell 5.1 runs on) has no ArgumentList, so the
# whole command line has to be assembled by hand.
function ConvertTo-NativeArgString {
    param([string[]] $Values)

    $parts = foreach ($v in $Values) {
        if ($v -eq '') {
            '""'
        } elseif ($v -notmatch '[\s"]') {
            $v
        } else {
            # Backslashes are only special when they run into a quote or the
            # closing quote of the argument, so double them exactly there.
            $escaped = [regex]::Replace($v, '(\\*)"', '$1$1\"')
            $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
            '"' + $escaped + '"'
        }
    }
    return ($parts -join ' ')
}

# adb itself has no timeout. `adb install` in particular will sit there forever
# when the emulator's transport drops mid-transfer, and a call that never
# returns is worse than one that fails - the preview used to hang on the very
# step the user was waiting for, with nothing on screen to explain why.
# Everything that can block indefinitely goes through here and gets killed.
#
# Started through System.Diagnostics.Process rather than Start-Process on
# purpose: with -PassThru, Windows PowerShell hands back a Process whose
# ExitCode is not populated, and a $null exit code makes a *successful* install
# look like a failed one.
function Invoke-NativeTimed {
    param(
        [string]   $Exe,
        [string[]] $NativeArgs,
        [int]      $TimeoutSec = 120
    )

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName               = $Exe
    $psi.Arguments              = ConvertTo-NativeArgString -Values $NativeArgs
    $psi.UseShellExecute        = $false
    $psi.CreateNoWindow         = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true

    try {
        $proc = [System.Diagnostics.Process]::Start($psi)
    } catch {
        $script:LastNativeExit = 1
        return "could not start $Exe : $($_.Exception.Message)"
    }
    if (-not $proc) {
        $script:LastNativeExit = 1
        return "could not start $Exe"
    }

    # Drained asynchronously: waiting for exit first would deadlock as soon as
    # the child fills a pipe buffer, which a chatty gradle or logcat will.
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()

    if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
        $script:LastNativeExit = -1   # never a real adb exit code
        try { $proc.Kill() } catch { }
        return "<no response from adb after $TimeoutSec s>"
    }

    $script:LastNativeExit = $proc.ExitCode
    $text = $outTask.Result + "`n" + $errTask.Result
    return $text.Trim()
}

# adb reports `device` only once the transport is genuinely usable. An emulator
# restored from a snapshot flips sys.boot_completed long before that: it shows up
# as `offline` for a while first, and installing into that window fails with an
# empty reason - which is exactly the useless error this script used to die on.
function Get-AdbState {
    param([string] $Adb, [string] $Serial)
    if (-not $Serial) { return '' }
    # Bounded as well: this runs inside the readiness loop, so a wedged adb
    # server must not turn a bounded wait into an open-ended one.
    return (Invoke-NativeTimed -Exe $Adb -TimeoutSec 20 -NativeArgs @('-s', $Serial, 'get-state')).Trim()
}

function Wait-ForDeviceOnline {
    param([string] $Adb, [string] $Serial, [int] $TimeoutSec = 90)

    $clock     = [System.Diagnostics.Stopwatch]::StartNew()
    $nudged    = $false
    $lastState = '<none>'

    while ($clock.Elapsed.TotalSeconds -lt $TimeoutSec) {
        $state = Get-AdbState -Adb $Adb -Serial $Serial
        if ($state -eq 'device') { return $true }

        if ($state -ne $lastState) {
            $lastState = $state
            $shown = if ($state) { $state } else { 'not attached' }
            Write-Info ("waiting for {0} to be usable (adb state: {1})" -f $Serial, $shown)
        }
        # `reconnect offline` is the supported way to revive a transport parked
        # in that state; killing the emulator instead would cost a full reboot.
        if (-not $nudged -and $clock.Elapsed.TotalSeconds -gt 10) {
            $nudged = $true
            Write-Info 'nudging adb to reconnect the dropped transport ...'
            Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @('reconnect', 'offline') | Out-Null
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function NewestChildDir {
    param([string] $Parent, [string] $Filter)
    if (-not (Test-Path $Parent)) { return $null }
    $hit = Get-ChildItem $Parent -Directory -Filter $Filter -ErrorAction SilentlyContinue |
           Sort-Object LastWriteTime -Descending |
           Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}

# --------------------------------------------------------------------------
# Toolchain discovery
# --------------------------------------------------------------------------
function Resolve-Toolchain {
    param([string] $InProjectDir)

    $binaries = "$env:USERPROFILE\.workbuddy\binaries"

    # --- Android SDK: env wins, then the usual install locations, then any
    #     managed SDK under .workbuddy\binaries (android-sdk, android-sdk-2, ...).
    $sdkCandidates = New-Object System.Collections.ArrayList
    foreach ($v in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
                     "$env:LOCALAPPDATA\Android\Sdk",
                     "$env:USERPROFILE\AppData\Local\Android\Sdk")) {
        if ($v) { [void]$sdkCandidates.Add($v) }
    }
    $managed = NewestChildDir -Parent $binaries -Filter 'android-sdk*'
    if ($managed) { [void]$sdkCandidates.Add($managed) }

    $sdkRoot = $null
    foreach ($c in $sdkCandidates) {
        if (Test-Path (Join-Path $c 'platform-tools\adb.exe')) { $sdkRoot = $c; break }
    }
    if (-not $sdkRoot) {
        throw ("Android SDK not found (no platform-tools\adb.exe). Tried: " +
               (($sdkCandidates | Select-Object -Unique) -join ', '))
    }

    $adb = Join-Path $sdkRoot 'platform-tools\adb.exe'
    $emulator = Join-Path $sdkRoot 'emulator\emulator.exe'

    # --- Gradle: prefer the wrapper if the project ever gets one.
    $gradle = $null
    $wrapper = Join-Path $InProjectDir 'gradlew.bat'
    if (Test-Path $wrapper) {
        $gradle = $wrapper
    } else {
        $gradleHome = NewestChildDir -Parent (Join-Path $binaries 'gradle') -Filter 'gradle-*'
        if ($gradleHome) {
            $candidate = Join-Path $gradleHome 'bin\gradle.bat'
            if (Test-Path $candidate) { $gradle = $candidate }
        }
        if (-not $gradle) {
            $onPath = Get-Command 'gradle' -ErrorAction SilentlyContinue
            if ($onPath) { $gradle = $onPath.Source }
        }
    }
    if (-not $gradle) {
        throw 'Gradle not found. Expected MiuList\gradlew.bat, a .workbuddy\binaries\gradle\gradle-*\bin\gradle.bat, or gradle on PATH.'
    }

    # --- JDK: gradle needs one; prefer the one the SDK tooling expects.
    $jdk = $null
    foreach ($v in @($env:ANDROID_JDK_HOME, $env:JAVA_HOME)) {
        if ($v -and (Test-Path $v)) { $jdk = $v; break }
    }
    if (-not $jdk) { $jdk = NewestChildDir -Parent (Join-Path $binaries 'jdk') -Filter 'jdk*' }
    if (-not $jdk) {
        $javaHomeCmd = Get-Command 'java' -ErrorAction SilentlyContinue
        if ($javaHomeCmd) { $jdk = Split-Path (Split-Path $javaHomeCmd.Source -Parent) -Parent }
    }
    if (-not $jdk) {
        throw 'JDK not found. Set JAVA_HOME or ANDROID_JDK_HOME to a JDK 17+ install.'
    }

    return [pscustomobject]@{
        SdkRoot  = $sdkRoot
        Adb      = $adb
        Emulator = $emulator
        Gradle   = $gradle
        Jdk      = $jdk
    }
}

function Resolve-Apk {
    param([string] $InProjectDir)
    $dir = Join-Path $InProjectDir 'app\build\outputs\apk\debug'
    if (-not (Test-Path $dir)) { return $null }
    $apk = Get-ChildItem $dir -Filter '*.apk' -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notlike '*androidTest*' } |
           Sort-Object LastWriteTime -Descending |
           Select-Object -First 1
    if ($apk) { return $apk.FullName }
    return $null
}

function Resolve-AppId {
    param([string] $InProjectDir)
    $gradleFile = Join-Path $InProjectDir 'app\build.gradle.kts'
    if (Test-Path $gradleFile) {
        $m = [regex]::Match((Get-Content $gradleFile -Raw), 'applicationId\s*=\s*"([^"]+)"')
        if ($m.Success) { return $m.Groups[1].Value }
    }
    return 'com.aistudio.scheduletodo.unmq'
}

# --------------------------------------------------------------------------
# Device helpers
# --------------------------------------------------------------------------
function Get-OnlineDevices {
    param([string] $Adb)
    $text = Invoke-Native -Exe $Adb -NativeArgs @('devices')
    $list = New-Object System.Collections.ArrayList
    foreach ($line in ($text -split "`r?`n")) {
        if ($line -match '^\s*(\S+)\s+device\b') { [void]$list.Add($matches[1]) }
    }
    return $list
}

function Test-EmulatorRunning {
    # emulator 37.x: emulator.exe IS qemu, and the Qt UI may live in qsn.exe.
    $names = @('emulator', 'qemu-system-x86_64', 'qemu-system-x86_64-headless', 'qsn')
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
             Where-Object { $names -contains $_.Name -and $_.CommandLine }
    # While shutting down, the emulator spawns a short-lived
    # `emulator -kill <pid> -sleep 20` helper. It is not a running emulator,
    # and treating it as one would make us wait for a device that is leaving.
    $real = @($procs | Where-Object { $_.CommandLine -notmatch '(?i)\s-kill\b' })
    return [bool]$real.Count
}

# A headless emulator has no window, so the app would start on a screen the
# user cannot see. Reusing one silently is the worst possible outcome for a
# previewer, so it has to be detectable from the command line.
function Test-HeadlessEmulatorRunning {
    $names = @('emulator', 'qemu-system-x86_64', 'qemu-system-x86_64-headless', 'qsn')
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
             Where-Object { $names -contains $_.Name -and $_.CommandLine }
    if (@($procs | Where-Object { $_.CommandLine -match '(?i)-no-window' }).Count) { return $true }
    if (@($procs | Where-Object { $_.Name -like '*-headless*' }).Count) { return $true }
    return $false
}

function Stop-Emulator {
    param([int] $TimeoutSec = 45)
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    while ($clock.Elapsed.TotalSeconds -lt $TimeoutSec) {
        if (-not (Test-EmulatorRunning)) { return }
        Start-Sleep -Seconds 2
    }
    Write-Warn 'emulator process is still around after emu kill; giving it a moment'
}

function Start-ClampWatcher {
    param([string] $ScriptRoot, [string] $WatchAvd)
    $ps = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $clamp = Join-Path $ScriptRoot 'clamp-window.ps1'
    if (-not (Test-Path $clamp)) { return }

    # Started before the AVD so the watcher is already polling when the
    # emulator creates (and re-creates) its window during boot.
    $watchArgs = @(
        '-NoProfile', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass',
        '-File', $clamp,
        '-Match', $WatchAvd,
        '-ProcessName', 'emulator,qemu-system-x86_64,qemu-system-x86_64-headless,qsn',
        '-Seconds', '1800', '-IntervalMs', '500', '-MissingChecks', '60', '-WaitSeconds', '120'
    )
    Start-Process -FilePath $ps -ArgumentList $watchArgs -WindowStyle Hidden | Out-Null
}

# Starts the AVD detached and returns the paths of its log files. Split out of
# the main flow so the cold-restart path can start it a second time with
# exactly the same arguments.
function Start-Avd {
    param(
        [string] $Emulator,
        [string] $Avd,
        [string] $Gpu = '',
        [bool]   $ColdBoot = $false,
        [bool]   $Headless = $false,
        [string] $ScriptRoot
    )

    $emuArgs = @('-avd', $Avd, '-no-boot-anim', '-netspeed', 'full', '-netdelay', 'none')
    if ($Gpu) {
        # Passed last so it wins over whatever the AVD carries. Worth having
        # as a switch: an AVD can read `hw.gpu.mode = auto` and still land on
        # a host renderer that comes up black.
        $emuArgs += @('-gpu', $Gpu)
    }
    if ($ColdBoot) {
        # Restoring a snapshot is fast but carries the previous session's
        # storage AND GPU state with it, and a wedged one makes `adb install`
        # hang forever or leaves the screen black.
        #
        # -no-snapshot-save matters as much as the load side: with the
        # default saveOnExit, a wedged run writes its own broken state into
        # default_boot and the next start reproduces it exactly.
        $emuArgs += @('-no-snapshot-load', '-no-snapshot-save')
    }
    if ($Headless) {
        $emuArgs += '-no-window'
    } else {
        Start-ClampWatcher -ScriptRoot $ScriptRoot -WatchAvd $Avd
    }

    # The emulator's own stdout/stderr is the ONLY place a host-side GPU init
    # failure shows up - guest logcat stays clean because the guest never got
    # a working renderer to complain about. Start-Process without a redirect
    # throws that away, which is why a black screen could previously only be
    # guessed at.
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $emuOut = Join-Path $ScriptRoot ("emulator-$stamp.out.log")
    $emuErr = Join-Path $ScriptRoot ("emulator-$stamp.err.log")
    Start-Process -FilePath $Emulator -ArgumentList $emuArgs `
        -RedirectStandardOutput $emuOut -RedirectStandardError $emuErr | Out-Null

    return @{ OutLog = $emuOut; ErrLog = $emuErr }
}

# A wedged snapshot gives an emulator that comes up, shows a usable
# transport, and then never finishes booting - the install can never succeed
# there. The only reliable fix is to throw the snapshot away and boot from
# scratch, so this does exactly that and waits for the device again.
function Restart-AvdCold {
    param(
        [string] $Adb,
        [string] $Serial,
        $Tool,
        [string] $Avd,
        [bool]   $Headless,
        [string] $ScriptRoot,
        [int]    $BootTimeoutSec,
        [int]    $ReadyTimeoutSec
    )

    Write-Info 'killing the emulator ...'
    if ($Serial) {
        Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @('-s', $Serial, 'emu', 'kill') | Out-Null
    }
    Stop-Emulator

    Write-Info 'booting it cold (-no-snapshot-load -no-snapshot-save) ...'
    $logs = Start-Avd -Emulator $Tool.Emulator -Avd $Avd -ColdBoot $true `
                -Headless $Headless -ScriptRoot $ScriptRoot
    Write-Info "emulator log: $($logs.OutLog)"
    Write-Info "            : $($logs.ErrLog)"

    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $newSerial = ''
    while ($clock.Elapsed.TotalSeconds -lt 90) {
        $newSerial = Get-AttachedSerial -Adb $Adb
        if ($newSerial) { break }
        Start-Sleep -Seconds 2
    }
    if (-not $newSerial) {
        return @{ Ok = $false; Serial = ''; Reason = 'the AVD never showed up in adb devices' }
    }

    $boot = Wait-ForBoot -Adb $Adb -Serial $newSerial -TimeoutSec $BootTimeoutSec
    if (-not $boot.Ok) {
        return @{ Ok = $false; Serial = $newSerial; Reason = $boot.Reason }
    }
    $ready = Wait-ForInstallReady -Adb $Adb -Serial $newSerial -TimeoutSec $ReadyTimeoutSec
    if (-not $ready.Ready) {
        return @{ Ok = $false; Serial = $newSerial; Reason = $ready.Reason }
    }
    return @{ Ok = $true; Serial = $newSerial; Reason = '' }
}

function Get-DeviceProperty {
    param([string] $Adb, [string] $Serial, [string] $Name)

    $args = Get-AdbArgs -Serial $Serial -Rest @('shell', 'getprop', $Name)
    # On a transport that is not up yet this comes back as an adb error
    # string, which is exactly what we want: anything that is not the value
    # we asked for means "not ready".
    return (Invoke-NativeTimed -Exe $Adb -TimeoutSec 20 -NativeArgs $args).Trim()
}

# The device says it finished booting through sys.boot_completed. Images from
# Android 12 on also carry dev.bootcomplete, which flips at the very end of
# the boot; when that property exists it is the stricter signal, so it wins.
#
# This check exists because the old boot gate was a false positive. It waited
# for an in-device `while` loop to return - but that loop never runs while the
# transport is still coming up: `adb shell` fails with "device offline" and
# the shell call ends immediately, so the script happily declared the device
# booted. Measured on this AVD:
#
#     12.6 s   adb state 'device', sys.boot_completed EMPTY, pm path android OK
#     24.7 s   sys.boot_completed = 1
#
# Every install inside that 12 s window comes back as "device is still
# booting", which is exactly the failure this script used to die on.
function Test-BootCompleted {
    param([string] $Adb, [string] $Serial)

    if ((Get-DeviceProperty -Adb $Adb -Serial $Serial -Name 'sys.boot_completed') -ne '1') {
        return $false
    }
    $dev = Get-DeviceProperty -Adb $Adb -Serial $Serial -Name 'dev.bootcomplete'
    if ($dev -and $dev -ne '1') { return $false }
    return $true
}

# Finds a device even while it is still 'offline' - the serial shows up in
# `adb devices` long before the transport is usable, and knowing it early is
# what lets the boot wait report something useful.
function Get-AttachedSerial {
    param([string] $Adb, [string] $Pattern = 'emulator-')

    $text = Invoke-Native -Exe $Adb -NativeArgs @('devices')
    foreach ($line in ($text -split "`r?`n")) {
        if ($line -match '^\s*(' + [regex]::Escape($Pattern) + '\S+)\s') { return $matches[1] }
    }
    return ''
}

# The device can be 'device' long before it accepts an install, so readiness
# is the conjunction of three things: a usable transport, the boot flag, and a
# package manager that answers.
function Get-InstallReadiness {
    param([string] $Adb, [string] $Serial)

    $state = Get-AdbState -Adb $Adb -Serial $Serial
    if ($state -ne 'device') {
        return @{ Ready = $false; Reason = "adb state is '$state'" }
    }
    if (-not (Test-BootCompleted -Adb $Adb -Serial $Serial)) {
        return @{ Ready = $false; Reason = 'the device has not finished booting yet' }
    }
    $pm = Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs (
        Get-AdbArgs -Serial $Serial -Rest @('shell', 'pm', 'path', 'android'))
    if ($pm -notmatch 'package:') {
        return @{ Ready = $false; Reason = 'the package manager does not answer yet' }
    }
    return @{ Ready = $true; Reason = '' }
}

function Wait-ForInstallReady {
    param([string] $Adb, [string] $Serial, [int] $TimeoutSec = 180)

    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $reason = 'not probed yet'
    $shown = ''
    while ($clock.Elapsed.TotalSeconds -lt $TimeoutSec) {
        $probe = Get-InstallReadiness -Adb $Adb -Serial $Serial
        if ($probe.Ready) { return $probe }
        $reason = $probe.Reason
        if ($reason -ne $shown) {
            $shown = $reason
            Write-Info ("waiting for the device to accept installs: {0}" -f $reason)
        }
        Start-Sleep -Seconds 2
    }
    return @{ Ready = $false; Reason = $reason }
}

function Wait-ForBoot {
    param([string] $Adb, [string] $Serial, [int] $TimeoutSec)

    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $lastBeat = 0
    $reason = 'not probed yet'
    while ($clock.Elapsed.TotalSeconds -lt $TimeoutSec) {
        $state = Get-AdbState -Adb $Adb -Serial $Serial
        if ($state -eq 'device') {
            if (Test-BootCompleted -Adb $Adb -Serial $Serial) {
                Write-Host ''
                return @{ Ok = $true; Reason = '' }
            }
            $reason = 'sys.boot_completed is not 1 yet'
        } else {
            $reason = "adb state: $state"
        }
        $elapsed = [int]$clock.Elapsed.TotalSeconds
        if ($elapsed - $lastBeat -ge 10) {
            $lastBeat = $elapsed
            Write-Host ("      booting... {0}s ({1})" -f $elapsed, $reason) -ForegroundColor DarkGray
        } else {
            Write-Host -NoNewline '.'
        }
        Start-Sleep -Seconds 2
    }
    Write-Host ''
    return @{ Ok = $false; Reason = $reason }
}

function Wait-ForPmReady {
    param([string] $Adb, [string] $Serial, [int] $TimeoutSec = 120)

    # `cmd package wait-for-handler` blocks inside the device until the package
    # handler is registered, so it is a cheaper wait than polling from Windows.
    # It is only a helper here: the real gate is Wait-ForInstallReady, which
    # additionally insists on the boot flag - the handler can be registered
    # long before the device accepts an install.
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $tryHandlerWait = $true

    while ($clock.Elapsed.TotalSeconds -lt $TimeoutSec) {
        if ($tryHandlerWait) {
            $args = Get-AdbArgs -Serial $Serial -Rest @('shell', 'cmd', 'package', 'wait-for-handler')
            $out = Invoke-NativeTimed -Exe $Adb -NativeArgs $args -TimeoutSec 45
            if ($script:LastNativeExit -eq 0 -and $out -notmatch 'Unknown|Exception|not found|<no response') { return }
            # Not supported on this API level - fall back to plain polling.
            $tryHandlerWait = $false
            continue
        }
        $args = Get-AdbArgs -Serial $Serial -Rest @('shell', 'pm', 'path', 'android')
        if ((Invoke-NativeTimed -Exe $Adb -NativeArgs $args -TimeoutSec 45) -match 'package:') { return }
        Start-Sleep -Seconds 3
    }
    Write-Warn 'Package manager still not answering; continuing anyway.'
}

# `adb install` fails in four very different shapes, and treating all of them
# the same is what used to end the run at step 5 with the emulator up and
# nothing on screen:
#   * transient - the device was not ready yet. This is the slow-boot case:
#                an emulator restored from a snapshot reports 'device' to adb
#                seconds before it accepts an install, and every install in
#                that window says "device is still booting". Retrying is the
#                whole point.
#   * signature - the app on the device was signed with another key. Nothing
#                to do with the APK being broken; it needs an APK signed with
#                the same key. The old copy is left alone.
#   * downgrade - the app on the device is newer than this APK.
#   * fatal    - a broken APK, no space, a split that is missing. Retrying
#                cannot help, so the run should stop and say why.
# Reads a `wm size` / `wm density` value. Two different things live in that
# output: the panel the AVD was made with ("Physical"), and what it is
# showing right now ("Override", only present when one is set). The override
# wins for what the device does, but the physical value is the one a target
# has to be compared against - otherwise a device that already carries the
# target override looks like "the target IS the native panel" and gets reset.
function Get-DisplayValue {
    param([string] $Text, [string] $Kind, [switch] $Physical)

    if ($Physical) {
        if ($Text -match ('Physical ' + $Kind + ':\s*(\S+)')) { return $matches[1] }
        return ''
    }
    if ($Text -match ('Override ' + $Kind + ':\s*(\S+)')) { return $matches[1] }
    if ($Text -match ('Physical ' + $Kind + ':\s*(\S+)')) { return $matches[1] }
    return ''
}

# The font scale comes back as a float, so it cannot be compared as a string.
function Test-FontScale {
    param([string] $Actual, [string] $Expected)

    $a = 0.0
    $e = 0.0
    if (-not [double]::TryParse($Actual, [ref]$a)) { return $false }
    if (-not [double]::TryParse($Expected, [ref]$e)) { return $false }
    return ([math]::Abs($a - $e) -lt 0.001)
}

# The device's display profile. This project works against two known
# configurations - the design baseline (411dp) and the user's real phone
# (332dp with the system font at 1.16) - and a screenshot matrix run can
# leave the device on a third, useless one: 1600x1200, 640x1366, or a font
# at 2.0. The preview then shows a layout nobody asked for, so the launcher
# puts the device back on a known profile before it launches the app.
#
# A real phone is never touched: its own ratio and font are the normal ones,
# and rewriting them on someone's device would be both wrong and destructive.
function Set-DisplayProfile {
    param(
        [string] $Adb,
        [string] $Serial,
        [string] $Profile = 'phone'
    )

    $profiles = @{
        # 用户真机那一档：1080×2414 @520dpi = 332×743 dp，系统字号 1.16
        phone = @{ Size = '1080x2414'; Density = '520'; Font = '1.16'; Label = '用户真机' }
        # 设计基准：1080×2400 @420dpi = 411×914 dp，字号 1.0
        base  = @{ Size = '1080x2400'; Density = '420'; Font = '1.0';  Label = '设计基准' }
    }

    if ($Profile -eq 'keep') {
        return [pscustomobject]@{ Applied = $false; Label = 'left as it is (-Display keep)' }
    }
    if ($Serial -notlike 'emulator-*') {
        return [pscustomobject]@{ Applied = $false; Label = 'a real device keeps its own display settings' }
    }
    if (-not $profiles.ContainsKey($Profile)) {
        return [pscustomobject]@{ Applied = $false; Label = "unknown profile '$Profile'" }
    }
    $p = $profiles[$Profile]

    # `wm size` / `wm density` set an override; `reset` removes it and puts
    # the panel back to what the AVD was created with. Using reset when the
    # target IS the native one keeps `wm size` reporting "Physical size",
    # which is what the matrix scripts look for. The comparison is against
    # the PHYSICAL panel size - a device that already carries the target
    # override would otherwise look like the native panel and get reset.
    $physSize = Get-DisplayValue -Physical -Text (Invoke-NativeTimed -Exe $Adb -TimeoutSec 20 -NativeArgs `
                    @('-s', $Serial, 'shell', 'wm', 'size')) -Kind 'size'
    $physDensity = Get-DisplayValue -Physical -Text (Invoke-NativeTimed -Exe $Adb -TimeoutSec 20 -NativeArgs `
                    @('-s', $Serial, 'shell', 'wm', 'density')) -Kind 'density'

    # Applied AND verified. A snapshot boot can swallow a `wm size` that was
    # accepted a moment earlier, and a profile that did not take is worse
    # than no profile at all - the app would then be judged in the wrong
    # layout, and nothing in the output would say so.
    $size = ''
    $density = ''
    $font = ''
    for ($try = 1; $try -le 3; $try++) {
        if ($p.Size -eq $physSize) {
            Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @('-s', $Serial, 'shell', 'wm', 'size', 'reset') | Out-Null
        } else {
            $out = Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @('-s', $Serial, 'shell', 'wm', 'size', $p.Size)
            if ($out -match '(?i)error|cannot|could not') {
                Write-Info ("wm size {0} was refused: {1}" -f $p.Size, ($out -replace '\s+', ' '))
            }
        }
        if ($p.Density -eq $physDensity) {
            Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @('-s', $Serial, 'shell', 'wm', 'density', 'reset') | Out-Null
        } else {
            $out = Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @('-s', $Serial, 'shell', 'wm', 'density', $p.Density)
            if ($out -match '(?i)error|cannot|could not') {
                Write-Info ("wm density {0} was refused: {1}" -f $p.Density, ($out -replace '\s+', ' '))
            }
        }
        Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @(
            '-s', $Serial, 'shell', 'settings', 'put', 'system', 'font_scale', $p.Font) | Out-Null

        # The configuration change needs a moment to reach the system. The app
        # itself is restarted before the launch, so it starts on the new metrics.
        Start-Sleep -Seconds 2

        # Read it back.
        $size = Get-DisplayValue -Text (Invoke-NativeTimed -Exe $Adb -TimeoutSec 20 -NativeArgs `
                    @('-s', $Serial, 'shell', 'wm', 'size')) -Kind 'size'
        $density = Get-DisplayValue -Text (Invoke-NativeTimed -Exe $Adb -TimeoutSec 20 -NativeArgs `
                    @('-s', $Serial, 'shell', 'wm', 'density')) -Kind 'density'
        $font = (Invoke-NativeTimed -Exe $Adb -TimeoutSec 20 -NativeArgs @(
            '-s', $Serial, 'shell', 'settings', 'get', 'system', 'font_scale')).Trim()

        # The font scale comes back as a float, so compare it as one.
        $fontOk = Test-FontScale -Actual $font -Expected $p.Font
        if ($size -eq $p.Size -and $density -eq $p.Density -and $fontOk) { break }
        if ($try -lt 3) {
            Write-Info ("the display settings did not stick (size {0}, density {1}, font {2}) - setting them again" -f
                        $size, $density, $font)
        }
    }

    $stuck = ($size -eq $p.Size -and $density -eq $p.Density -and (Test-FontScale -Actual $font -Expected $p.Font))
    if (-not $stuck) {
        Write-Warn ("the display profile did not stick: wanted {0} @ {1}dpi, font {2}; the device says {3} @ {4}dpi, font {5}" -f
                    $p.Size, $p.Density, $p.Font, $size, $density, $font)
    }

    $dp = ''
    if ($size -match '^(\d+)x(\d+)$' -and $density) {
        $dp = [math]::Round(([int]$matches[1]) * 160 / [int]$density)
    }
    return [pscustomobject]@{
        Applied = $stuck; Label = $p.Label; Size = $size; Density = $density
        Font = $font; Dp = $dp
    }
}

function Get-InstallErrorClass {
    param([string] $Output)

    if (-not $Output) { return 'transient' }

    # Our own timeout marker. Checked first because it is the one case where
    # we killed adb ourselves, so the output says nothing at all about the APK -
    # and it does not contain any of the transient words below.
    if ($Output -match '<no response from adb') { return 'transient' }

    # The recoverable verdicts come before the generic INSTALL_FAILED_ match,
    # because they are a subset of it.
    if ($Output -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') { return 'signature' }
    if ($Output -match 'INSTALL_FAILED_VERSION_DOWNGRADE|INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE') {
        return 'downgrade'
    }
    if ($Output -match 'INSTALL_FAILED_|DELETE_FAILED_|^Failure \[') { return 'fatal' }

    if ($Output -match '(?i)device is still booting|offline|device not found|' +
                     'device unauthorized|no devices/emulators found|cannot connect|' +
                     'no connection|protocol error|transport error|closed|' +
                     'failed to stream|timed out|timeout') {
        return 'transient'
    }
    # An empty reason after "failed to install" means adb lost the device
    # mid-transfer, which is retryable - never a verdict about the APK.
    if ($Output -match '(?i)failed to install.*:\s*$') { return 'transient' }
    return 'fatal'
}

function Remove-App {
    param([string] $Adb, [string] $Serial, [string] $AppId)

    $out = Invoke-NativeTimed -Exe $Adb -TimeoutSec 90 -NativeArgs @(
        '-s', $Serial, 'uninstall', $AppId)
    return ($out -match 'Success')
}

# Stop the app before copying SQLite files so the database and WAL form one snapshot.
function Backup-AppData {
    param([string] $Adb, [string] $Serial, [string] $AppId, [string] $DestRoot)

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $dest = Join-Path $DestRoot ("preview-data-{0}-{1}" -f $AppId, $stamp)
    New-Item -ItemType Directory -Path $dest -ErrorAction Stop | Out-Null

    $root = Invoke-NativeTimed -Exe $Adb -TimeoutSec 45 -NativeArgs @('-s', $Serial, 'root')
    if ($script:LastNativeExit -ne 0 -or $root -match 'cannot run as root') {
        return @{ Ok = $false; Path = $dest; Note = 'root access failed; the app data was not backed up' }
    }
    if (-not (Wait-ForDeviceOnline -Adb $Adb -Serial $Serial -TimeoutSec 60)) {
        return @{ Ok = $false; Path = $dest; Note = 'the device did not reconnect after adb root' }
    }
    $uid = Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @('-s', $Serial, 'shell', 'id', '-u')
    if ($script:LastNativeExit -ne 0 -or $uid.Trim() -ne '0') {
        return @{ Ok = $false; Path = $dest; Note = 'root access could not be verified' }
    }
    Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @(
        '-s', $Serial, 'shell', 'am', 'force-stop', $AppId) | Out-Null
    if ($script:LastNativeExit -ne 0) {
        return @{ Ok = $false; Path = $dest; Note = 'the app could not be stopped for a consistent backup' }
    }

    $listing = Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @(
        '-s', $Serial, 'shell', 'ls', '-1', "/data/data/$AppId")
    if ($script:LastNativeExit -ne 0) {
        return @{ Ok = $false; Path = $dest; Note = 'the app data directory could not be inspected' }
    }
    $entries = @($listing -split '\r?\n' | ForEach-Object { $_.Trim() })
    $pulled = @()
    foreach ($dir in @('databases', 'shared_prefs', 'files', 'no_backup')) {
        if ($dir -notin $entries) { continue }
        Invoke-NativeTimed -Exe $Adb -TimeoutSec 120 -NativeArgs @(
            '-s', $Serial, 'pull', "/data/data/$AppId/$dir", $dest) | Out-Null
        if ($script:LastNativeExit -ne 0) {
            return @{ Ok = $false; Path = $dest; Note = "incomplete backup: failed to copy $dir" }
        }
        $pulled += $dir
    }
    $note = if ($pulled.Count) { 'saved: ' + ($pulled -join ', ') } else { 'no app data directories were found' }
    return @{ Ok = ($pulled.Count -gt 0); Path = $dest; Note = $note }
}

# Installs, retrying everything that is not a real verdict about the APK.
#
# **这一段永远不卸载设备上的任何东西。** 覆盖安装（`adb install -r`）保住数据；
# 签名冲突时保留旧包并把原因说清楚（见下面的 signature 分支）。
# 唯一会主动卸载的是显式的 `-Reinstall`，那在 [Remove-AppForReinstall] 里，
# 而且那里先备份、备份不成就停。
function Install-Apk {
    param(
        [string] $Adb,
        [string] $Serial,
        [string] $ApkPath,
        [string] $AppId = '',
        [int]    $Attempts = 6,
        [int]    $TimeoutSec = 180,
        [bool]   $AutoRecover = $true
    )

    $last = ''
    $lastClass = 'transient'
    $allowDowngrade = $false

    for ($i = 1; $i -le $Attempts; $i++) {
        # Never install into a device that is not ready. This gate is what
        # the old script was missing: `pm path android` answers ~12 s before
        # the device accepts an install, and an install in that window comes
        # back as "device is still booting".
        $ready = Wait-ForInstallReady -Adb $Adb -Serial $Serial -TimeoutSec 120
        if (-not $ready.Ready) {
            $last = "the device is not ready to accept installs: $($ready.Reason)"
            $lastClass = 'transient'
        } else {
            $flags = @('-s', $Serial, 'install', '-r')
            if ($allowDowngrade) { $flags += '-d' }
            # The first attempt streams the APK. Retries push it instead: a push
            # reports progress and does not depend on install-streaming, which
            # is the half of the protocol that stalls on a busy device.
            if ($i -gt 1) { $flags += '--no-streaming' }
            $out = Invoke-NativeTimed -Exe $Adb -TimeoutSec $TimeoutSec -NativeArgs ($flags + @($ApkPath))

            if ($script:LastNativeExit -eq 0 -and $out -notmatch '(?i)failed to install') {
                return [pscustomobject]@{ Output = $out; Ok = $true; Attempts = $i; Class = 'ok' }
            }

            # A client that hangs is not proof of a failed install: the transfer
            # and the reporting of its result are separate steps, so a client
            # stuck waiting can leave a perfectly good package behind. On a hang
            # the device gets the last word, not the timeout.
            if ($script:LastNativeExit -eq -1 -and $AppId -and
                (Test-PackageInstalled -Adb $Adb -Serial $Serial -AppId $AppId)) {
                Write-Warn 'adb never reported back, but the package is on the device - treating as installed'
                return [pscustomobject]@{ Output = "$out (verified on device)"; Ok = $true; Attempts = $i; Class = 'ok' }
            }
            $last = $out
            $lastClass = Get-InstallErrorClass -Output $out
        }

        if ($i -ge $Attempts) { break }

        if ($lastClass -eq 'transient') {
            Write-Warn ("install attempt {0}/{1} did not go through: {2}" -f
                        $i, $Attempts, ($last -replace '\s+', ' '))
            Write-Info ("retrying in {0}s ..." -f (5 * $i))
            Start-Sleep -Seconds (5 * $i)
            # Drop the dead transport so the next attempt starts from a fresh one.
            Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @('reconnect', 'offline') | Out-Null
            continue
        }

        if ($lastClass -eq 'signature') {
            Write-Warn ("install attempt {0}/{1} was rejected: the app on the device is signed with a different key" -f
                        $i, $Attempts)
            # **不卸载。**
            #
            # 签名冲突时设备上那个旧包连同它的数据是用户的东西：把旧包删掉换来
            # 一个装得上的新包，代价是用户的数据没了 —— 而这个决定不该由一条
            # 预览脚本替他做（早先这里默认就卸载，只在备份失败时打个警告就继续，
            # 于是「脚本顺手把我的数据删了」这件事根本不用用户同意）。
            #
            # 能装上的办法只有一个：**用同一把密钥出的 APK**。没签名冲突的时候
            # `adb install -r` 本来就保住数据，那才是正常路径。
            Write-Warn 'the app on the device and its data are left untouched'
            if ($AutoRecover) {
                Write-Info 'Use an APK signed with the same key as the installed app.'
            }
            break
        }

        if ($lastClass -eq 'downgrade') {
            if (-not $allowDowngrade) {
                Write-Warn 'the app on the device is newer than this APK - retrying with -d (allow downgrade)'
                $allowDowngrade = $true
                continue
            }
            Write-Bad 'the device refuses even a downgrade - the app there is newer and signed differently'
            break
        }

        Write-Bad 'that is a real install failure, not a flaky device - not retrying'
        break
    }

    return [pscustomobject]@{ Output = $last; Ok = $false; Attempts = $Attempts; Class = $lastClass }
}

# Removes the app from the device. **Only ever called for an explicit -Reinstall.**
#
# Before any of that, ask the device whether the package exists. Not installed
# means there is nothing to back up or remove; a query that failed is not an
# answer, so the reinstall stops instead of guessing.
#
# The order is not negotiable:
#
#   1. copy the app data off the device first;
#   2. **stop if that did not work** - an uninstall takes the data with it,
#      and "we could not copy it but we removed it anyway" is the one outcome
#      that is worse than doing nothing;
#   3. remove the app, and **stop if the removal failed** - ignoring the return
#      value means carrying on and installing over an app that is still there.
#
# The copy is a copy, not a restore: the data is left in the folder for the
# user to put back by hand. Saying "restored" here would be a lie.
function Remove-AppForReinstall {
    param(
        [string] $Adb,
        [string] $Serial,
        [string] $AppId,
        [string] $ScriptRoot = ''
    )

    $installState = Get-PackageInstallState -Adb $Adb -Serial $Serial -AppId $AppId
    if ($installState -eq 'unknown') {
        Write-Bad 'the device did not say whether the app is installed - stopping without removing or installing anything.'
        return [pscustomobject]@{ Ok = $false; BackupPath = ''; Reason = 'could not determine whether the app is installed' }
    }
    if ($installState -eq 'not-installed') {
        Write-Info '-Reinstall was given, but the app is not installed yet - skipping the backup and uninstall.'
        return [pscustomobject]@{ Ok = $true; BackupPath = ''; Reason = '' }
    }

    Write-Warn "-Reinstall was given: the app and its data on the device are removed."
    Write-Info 'The data is copied off the device first - but only if that works.'

    if (-not $ScriptRoot) {
        Write-Bad 'there is no folder to copy the app data into, so nothing was removed.'
        return [pscustomobject]@{ Ok = $false; BackupPath = ''; Reason = 'no place to copy the app data to' }
    }

    $backup = Backup-AppData -Adb $Adb -Serial $Serial -AppId $AppId -DestRoot $ScriptRoot
    if (-not $backup.Ok) {
        Write-Bad ("the app data could not be copied off the device: {0}" -f $backup.Note)
        Write-Bad 'nothing was removed. The old app and its data are still there.'
        return [pscustomobject]@{ Ok = $false; BackupPath = ''; Reason = 'the app data could not be backed up' }
    }
    Write-Info "the app data was copied to $($backup.Path) ($($backup.Note))"

    if (-not (Remove-App -Adb $Adb -Serial $Serial -AppId $AppId)) {
        Write-Bad 'the app could not be removed - giving up'
        return [pscustomobject]@{ Ok = $false; BackupPath = ''; Reason = 'the app could not be removed' }
    }
    Write-Good 'the old copy was removed'

    return [pscustomobject]@{ Ok = $true; BackupPath = $backup.Path; Reason = '' }
}

# The install step of the preview: the optional -Reinstall, then the install.
#
# Both live in one function so that "the reinstall failed" and "the install
# must not happen" are the same piece of code - when they were two places, the
# second one happily ran after the first one gave up.
function Install-AppWithOptionalReinstall {
    param(
        [string] $Adb,
        [string] $Serial,
        [string] $ApkPath,
        [string] $AppId = '',
        [bool]   $Reinstall = $false,
        [string] $ScriptRoot = '',
        [int]    $TimeoutSec = 180,
        [bool]   $AutoRecover = $true
    )

    $backupPath = ''
    if ($Reinstall -and $AppId) {
        $reinstallResult = Remove-AppForReinstall -Adb $Adb -Serial $Serial -AppId $AppId -ScriptRoot $ScriptRoot
        if (-not $reinstallResult.Ok) {
            throw ("Reinstall stopped: {0}" -f $reinstallResult.Reason)
        }
        $backupPath = $reinstallResult.BackupPath
    }

    $install = Install-Apk -Adb $Adb -Serial $Serial -ApkPath $ApkPath -AppId $AppId `
                -TimeoutSec $TimeoutSec -AutoRecover $AutoRecover
    $install | Add-Member -NotePropertyName BackupPath -NotePropertyValue $backupPath -Force
    return $install
}

# The device, not adb, is the authority on whether a package is there.
#
# "Not installed" and "could not ask" are different answers: a device that is
# offline, unauthorized or too slow must never be mistaken for a clean install
# target, because the explicit reinstall would then skip the backup and remove
# nothing. `pm path` answers a missing package with no output at all (newer
# versions exit 1, older ones exit 0); a transport or permission failure always
# prints a diagnostic, so only silence or a not-found message counts as absent.
function Get-PackageInstallState {
    param([string] $Adb, [string] $Serial, [string] $AppId)

    $out = Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @(
        '-s', $Serial, 'shell', 'pm', 'path', $AppId)

    if ($out -match 'package:') { return 'installed' }
    if ($out -match '(?i)(unknown package|package\s+\S+\s+(?:was\s+)?not found)') {
        return 'not-installed'
    }
    if ([string]::IsNullOrWhiteSpace($out)) { return 'not-installed' }
    return 'unknown'
}

function Test-PackageInstalled {
    param([string] $Adb, [string] $Serial, [string] $AppId)
    return ((Get-PackageInstallState -Adb $Adb -Serial $Serial -AppId $AppId) -eq 'installed')
}

# pidof is silent when there is no match, and equally silent when the transport
# is gone, so the only thing that counts as "it started" is a real pid.
function Get-AppPid {
    param([string] $Adb, [string] $Serial, [string] $AppId, [int] $TimeoutSec = 20)

    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    while ($clock.Elapsed.TotalSeconds -lt $TimeoutSec) {
        $found = (Invoke-NativeTimed -Exe $Adb -TimeoutSec 20 -NativeArgs @(
            '-s', $Serial, 'shell', 'pidof', $AppId)).Trim()
        if ($found -match '\d') { return ($found -split '\s+')[0] }
        Start-Sleep -Seconds 2
    }
    return ''
}

function Save-FailureLog {
    param([string] $Adb, [string] $Serial, [string] $ScriptRoot, [string] $Reason)

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $path = Join-Path $ScriptRoot ("preview-failure-{0}.log" -f $stamp)
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("# MiuList preview failure: $Reason")
    [void]$sb.AppendLine("# time   : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
    [void]$sb.AppendLine("# device : $Serial")
    [void]$sb.AppendLine()
    # Every call is bounded: the whole point of this file is to explain a
    # failure, and it used to be able to hang on the same broken adb.
    [void]$sb.AppendLine('===== adb devices -l =====')
    [void]$sb.AppendLine((Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs @('devices', '-l')))
    [void]$sb.AppendLine('===== adb get-state =====')
    [void]$sb.AppendLine((Invoke-NativeTimed -Exe $Adb -TimeoutSec 30 -NativeArgs `
        (Get-AdbArgs -Serial $Serial -Rest @('get-state'))))
    [void]$sb.AppendLine('===== logcat -b crash -d -t 200 =====')
    [void]$sb.AppendLine((Invoke-NativeTimed -Exe $Adb -TimeoutSec 45 -NativeArgs `
        @('-s', $Serial, 'logcat', '-b', 'crash', '-d', '-t', '200')))
    [void]$sb.AppendLine('===== logcat -d -t 400 =====')
    [void]$sb.AppendLine((Invoke-NativeTimed -Exe $Adb -TimeoutSec 45 -NativeArgs `
        @('-s', $Serial, 'logcat', '-d', '-t', '400')))
    [System.IO.File]::WriteAllText($path, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
    return $path
}

# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------
$serial = $null
$tool = $null
$stepStart = 0.0
# True when this run started the AVD itself. Only then may the script throw
# the saved snapshot away and boot cold - a device the user attached is not
# ours to restart.
$weStartedAvd = $false

try {
    if (-not (Test-Path $ProjectDir)) {
        throw "Project directory not found: $ProjectDir (pass -ProjectDir)."
    }

    # --- [1/5] toolchain ---------------------------------------------------
    Write-Step "[1/$TotalSteps] Toolchain"
    $stepStart = $Watch.Elapsed.TotalSeconds
    $tool = Resolve-Toolchain -InProjectDir $ProjectDir
    $appId = Resolve-AppId -InProjectDir $ProjectDir

    Write-Info "project : $ProjectDir"
    Write-Info "app id  : $appId"
    Write-Info "sdk     : $($tool.SdkRoot)"
    Write-Info "gradle  : $($tool.Gradle)"
    Write-Info "jdk     : $($tool.Jdk)"

    if ($DryRun) {
        Write-Take $stepStart
        $apk = Resolve-Apk -InProjectDir $ProjectDir
        Invoke-Native -Exe $tool.Adb -NativeArgs @('start-server') | Out-Null
        $devices = @(Get-OnlineDevices -Adb $tool.Adb)
        Write-Host ''
        Write-Host '--- plan (nothing was built, booted or installed) ---' -ForegroundColor Cyan
        Write-Info ("build    : " + $(if ($SkipBuild) { 'no (-SkipBuild)' } else { 'yes' }))
        Write-Info ("apk      : " + $(if ($apk) { $apk } else { '(none yet - would be built)' }))
        Write-Info ("emulator : " + $(if ($NoEmulator) { 'no (-NoEmulator)' } else { "yes, AVD '$Avd'" + $(if ($Headless) { ' (-Headless, no window)' } else { '' }) }))
        Write-Info ("gpu      : " + $(if ($Gpu) { "$Gpu (-gpu)" } elseif ($ColdBoot) { "AVD default, no -gpu passed" } else { "AVD default" }))
        Write-Info ("snapshot : " + $(if ($ColdBoot) { 'skipped (-no-snapshot-load -no-snapshot-save)' } else { 'loaded if present' }))
        Write-Info ("order    : the AVD boots while Gradle builds")
        Write-Info ("display  : " + $(switch ($Display) {
            'phone' { '用户真机 1080x2414 @520dpi, font 1.16 (332dp)' }
            'base'  { '设计基准 1080x2400 @420dpi, font 1.0 (411dp)' }
            default { 'left as it is' }
        }))
        Write-Info ("install  : " + $(if ($Reinstall) { 'the old app is removed first (-Reinstall - the data on the device goes with it)' } else { 'over the existing app, keeping its data' }))
        Write-Info ("recovery : " + $(if ($NoAutoRecover) { 'off (-NoAutoRecover - only the facts are printed)' } else { 'on - a differently signed app on the device is kept, with its data' }))
        Write-Info ("launch   : " + $(if ($NoLaunch) { 'no (-NoLaunch)' } else { 'yes' }))
        Write-Info ("attached : " + $(if ($devices.Count) { $devices -join ', ' } else { '(none - would boot the AVD)' }))
        exit 0
    }

    Write-Take $stepStart

    # --- [2/5] device ------------------------------------------------------
    # The AVD is started BEFORE the build on purpose. Booting an emulator
    # takes tens of seconds and Gradle takes about as long, so running them
    # one after the other doubled the wait for a preview. Started here, the
    # boot happens while Gradle compiles.
    Write-Step "[2/$TotalSteps] Device"
    $stepStart = $Watch.Elapsed.TotalSeconds
    Invoke-Native -Exe $tool.Adb -NativeArgs @('start-server') | Out-Null
    $devices = @(Get-OnlineDevices -Adb $tool.Adb)

    if ($Device) {
        if ($devices -notcontains $Device) {
            throw ("Requested -Device '$Device' is not attached and online. Attached: " +
                   $(if ($devices.Count) { $devices -join ', ' } else { '(none)' }))
        }
        $serial = $Device
        Write-Info "using requested device: $serial"
    } elseif ($devices.Count -eq 0) {
        if ($NoEmulator) {
            throw '-NoEmulator was passed but no device is attached.'
        }
        $serial = $null
    } elseif ($devices.Count -eq 1) {
        $serial = $devices[0]
        Write-Info "using attached device: $serial"
    } else {
        $emulators = @($devices | Where-Object { $_ -like 'emulator-*' })
        if ($emulators.Count -eq 1) {
            $serial = $emulators[0]
            Write-Info "several devices attached; using the emulator: $serial"
            Write-Warn ("ignored: " + (($devices | Where-Object { $_ -ne $serial }) -join ', '))
        } else {
            throw ("Several devices attached (" + ($devices -join ', ') +
                   '). Re-run with -Device <serial>.')
        }
    }

    # A headless emulator runs the app perfectly well and shows you nothing.
    # Unless headless was explicitly asked for, replace it with a windowed one.
    # Also restart if explicitly requested via -RestartEmulator.
    $needRestart = $RestartEmulator -or ($serial -and $serial -like 'emulator-*' -and -not $Headless -and (Test-HeadlessEmulatorRunning))
    if ($needRestart) {
        if ($RestartEmulator) {
            Write-Info "restarting emulator as requested (-RestartEmulator) ..."
        } else {
            Write-Warn "emulator $serial is running headless (-no-window), so there would be nothing to look at."
            Write-Info 'restarting it with a real window ...'
        }
        if ($serial) {
            # Bounded: `emu kill` goes over the emulator console, so it is one
            # more call that can block on exactly the transport that is already
            # misbehaving. Stop-Emulator below waits for the process regardless.
            Invoke-NativeTimed -Exe $tool.Adb -TimeoutSec 30 -NativeArgs @('-s', $serial, 'emu', 'kill') | Out-Null
        }
        Stop-Emulator
        $serial = $null
    }

    if (-not $serial) {
        if (-not (Test-Path $tool.Emulator)) {
            throw "emulator.exe not found under $($tool.SdkRoot)\emulator. Install it via sdkmanager, or attach a device and pass -NoEmulator."
        }
        if (Test-EmulatorRunning) {
            # Starting a second AVD here would only produce emulator-5556 and
            # split your preview across two windows.
            Write-Info 'an emulator is already running - waiting for it to register with adb'
        } else {
            Write-Info "no device attached, booting AVD '$Avd' ..."
            if ($Gpu) { Write-Info "gpu mode: $Gpu (-gpu overrides the AVD setting)" }
            if ($ColdBoot) { Write-Info 'cold boot (-no-snapshot-load -no-snapshot-save); slower to start' }
            if ($Headless) { Write-Info 'headless mode (-no-window); no window to clamp' }
            $logs = Start-Avd -Emulator $tool.Emulator -Avd $Avd -Gpu $Gpu -ColdBoot ([bool]$ColdBoot) `
                        -Headless ([bool]$Headless) -ScriptRoot $ScriptRoot
            Write-Info "emulator log: $($logs.OutLog)"
            Write-Info "            : $($logs.ErrLog)"
            $weStartedAvd = $true
            Write-Info 'the AVD boots while Gradle builds - no waiting for it'
        }
        $serial = 'pending'
    }
    Write-Take $stepStart

    # --- [3/5] build -------------------------------------------------------
    Write-Step "[3/$TotalSteps] Build debug APK"
    $stepStart = $Watch.Elapsed.TotalSeconds
    $apk = Resolve-Apk -InProjectDir $ProjectDir

    if ($SkipBuild) {
        if (-not $apk) {
            throw "-SkipBuild was passed but no APK exists under app\build\outputs\apk\debug."
        }
        Write-Info "skipping Gradle, reusing:"
        Write-Info "  $apk"
    } else {
        if ($apk) {
            Write-Info ("previous build : {0:yyyy-MM-dd HH:mm:ss}" -f (Get-Item $apk).LastWriteTime)
        }
        $env:JAVA_HOME = $tool.Jdk
        $env:ANDROID_JDK_HOME = $tool.Jdk
        $env:ANDROID_HOME = $tool.SdkRoot
        $env:ANDROID_SDK_ROOT = $tool.SdkRoot

        Write-Info 'gradle assembleDebug ...'
        Push-Location $ProjectDir
        try {
            & $tool.Gradle -p $ProjectDir assembleDebug --console=plain
            if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit $LASTEXITCODE)." }
        } finally {
            Pop-Location
        }

        $apk = Resolve-Apk -InProjectDir $ProjectDir
        if (-not $apk) { throw 'Build reported success but no APK was produced.' }
    }
    Write-Good ("APK: {0} ({1:n1} MB)" -f (Split-Path $apk -Leaf), ((Get-Item $apk).Length / 1MB))
    Write-Take $stepStart

    # --- [4/5] boot --------------------------------------------------------
    Write-Step "[4/$TotalSteps] Boot"
    $stepStart = $Watch.Elapsed.TotalSeconds
    if ($serial -eq 'pending') {
        # The serial shows up in `adb devices` long before the transport is
        # usable, so it can be picked up while the device is still offline.
        $found = ''
        $clock = [System.Diagnostics.Stopwatch]::StartNew()
        while (-not $found) {
            $found = Get-AttachedSerial -Adb $tool.Adb
            if ($found) { break }
            if ($clock.Elapsed.TotalSeconds -gt 90) {
                throw 'The AVD started but never showed up in `adb devices`.'
            }
            Start-Sleep -Seconds 2
        }
        $serial = $found
    }

    # Waiting for the boot is not a formality: an emulator reports 'device' to
    # adb seconds before it can accept an install, and an install in that
    # window comes back as "device is still booting".
    $boot = Wait-ForBoot -Adb $tool.Adb -Serial $serial -TimeoutSec $BootTimeoutSec
    if (-not $boot.Ok) {
        throw ("The device {0} did not finish booting: {1}" -f $serial, $boot.Reason)
    }
    Write-Good "device $serial is up (sys.boot_completed=1)"
    Write-Take $stepStart

    # --- [5/5] install + launch -------------------------------------------
    Write-Step "[5/$TotalSteps] Install and launch"
    $stepStart = $Watch.Elapsed.TotalSeconds

    # A freshly booted AVD reports 'device' to adb well before its transport
    # is usable, so this is a real gate rather than a formality.
    if (-not (Wait-ForDeviceOnline -Adb $tool.Adb -Serial $serial -TimeoutSec 120)) {
        throw ("Device {0} never became usable (adb state: {1})." -f
               $serial, (Get-AdbState -Adb $tool.Adb -Serial $serial))
    }
    Wait-ForPmReady -Adb $tool.Adb -Serial $serial

    # 'device' is not the same as "can accept an install". On this AVD the
    # package manager answers 12 s before the boot flag flips, and an install
    # in that window is rejected with "device is still booting".
    $ready = Wait-ForInstallReady -Adb $tool.Adb -Serial $serial -TimeoutSec $ReadyTimeoutSec
    if (-not $ready.Ready) {
        $canColdBoot = ($weStartedAvd -and -not $ColdBoot -and $serial -like 'emulator-*')
        if ($canColdBoot) {
            Write-Warn ("the device never became ready to accept installs ({0})" -f $ready.Reason)
            Write-Warn 'the saved snapshot is probably wedged - booting the AVD cold'
            $restart = Restart-AvdCold -Adb $tool.Adb -Serial $serial -Tool $tool -Avd $Avd `
                        -Headless ([bool]$Headless) -ScriptRoot $ScriptRoot `
                        -BootTimeoutSec $BootTimeoutSec -ReadyTimeoutSec $ReadyTimeoutSec
            if (-not $restart.Ok) {
                throw ("The AVD did not come up after a cold boot: {0}" -f $restart.Reason)
            }
            $serial = $restart.Serial
        } else {
            throw ("Device {0} never became ready to accept installs: {1}" -f $serial, $ready.Reason)
        }
    }
    Write-Good 'the device is ready to accept installs'

    $install = Install-AppWithOptionalReinstall -Adb $tool.Adb -Serial $serial -ApkPath $apk `
                -AppId $appId -Reinstall ([bool]$Reinstall) -ScriptRoot $ScriptRoot `
                -TimeoutSec $InstallTimeoutSec -AutoRecover (-not $NoAutoRecover)
    if (-not $install.Ok) {
        $state = Get-AdbState -Adb $tool.Adb -Serial $serial
        $log = Save-FailureLog -Adb $tool.Adb -Serial $serial -ScriptRoot $ScriptRoot `
                    -Reason ("install failed after {0} attempt(s): {1}" -f $install.Attempts, $install.Output)
        Write-Bad ("install failed after {0} attempt(s): {1}" -f $install.Attempts, $install.Output)
        Write-Info "adb state: $state"
        if ($install.Class -eq 'signature') {
            Write-Info ''
            Write-Warn 'the app on the device is signed with a different key than this APK.'
            Write-Info 'The installed app and its data were not removed by the failed install.'
            if (-not $NoAutoRecover) {
                Write-Info 'Use an APK signed with the same key as the installed app.'
            }
        }
        if ($install.Output -match '<no response from adb') {
            Write-Info ''
            Write-Warn 'adb install never returned, even though the device answered fine.'
            Write-Info 'On an AVD that almost always means the emulated storage in the'
            Write-Info 'restored snapshot has wedged. Re-run with -ColdBoot to start the'
            Write-Info 'emulator without loading a snapshot.'
            Write-Info ''
        }
        Write-Info "logcat saved to $log"
        Write-Warn 'On a MIUI / ColorOS / OriginOS device you also need "Install via USB" in Developer options.'
        exit 1
    }
    Write-Good 'installed'

    # Put the display back on a known profile, as late as possible before the
    # app comes up. A matrix run leaves the device on something like
    # 1600x1200 or a font at 2.0, and the preview would then show a layout
    # nobody asked for. Applying it after the install keeps it away from the
    # boot-time churn, and the app is started right after, on the new metrics.
    # (Not $display: PowerShell does not care about case, so that would be
    # the -Display parameter itself, and the assignment would be rejected
    # by its ValidateSet.)
    $displayProfile = Set-DisplayProfile -Adb $tool.Adb -Serial $serial -Profile $Display
    if ($displayProfile.Applied) {
        Write-Good ("display: {0} - {1} @ {2}dpi, font {3} ({4}dp wide)" -f
                    $displayProfile.Label, $displayProfile.Size, $displayProfile.Density,
                    $displayProfile.Font, $displayProfile.Dp)
    } else {
        Write-Info ("display left alone: {0}" -f $displayProfile.Label)
    }

    if ($install.BackupPath) {
        Write-Info ''
        Write-Info "the data of the removed app was copied to: $($install.BackupPath)"
        # **是「复制」，不是「恢复」。** 数据就放在那个文件夹里，放不回去得用户自己动手。
        Write-Info 'it was NOT put back - restore it by hand if you want it:'
        Write-Info ("  adb -s {0} root" -f $serial)
        Write-Info ("  adb -s {0} shell `"rm -f /data/data/{1}/databases/*`"" -f $serial, $appId)
        Write-Info ("  adb -s {0} push `"{1}/databases/.`" /data/data/{2}/databases/" -f $serial, $install.BackupPath, $appId)
        Write-Info ("  adb -s {0} shell `"chown -R `$(stat -c %u /data/data/{1}):`$(stat -c %g /data/data/{1}) /data/data/{1}`"" -f $serial, $appId)
    }

    if ($NoLaunch) {
        Write-Info '-NoLaunch given, not starting the app.'
    } else {
        # The display profile may have just changed under the app, and a
        # running process keeps the metrics it started with. A fresh start
        # is the only way to see the app at the new size and font.
        Invoke-NativeTimed -Exe $tool.Adb -TimeoutSec 30 -NativeArgs @(
            '-s', $serial, 'shell', 'am', 'force-stop', $appId) | Out-Null

        # monkey reliably hits whatever the manifest declared as LAUNCHER.
        Invoke-NativeTimed -Exe $tool.Adb -TimeoutSec 60 -NativeArgs @(
            '-s', $serial, 'shell', 'monkey',
            '-p', $appId, '-c', 'android.intent.category.LAUNCHER', '1') | Out-Null

        # $pid is a read-only automatic variable, so this must not be called $pid.
        $appPid = Get-AppPid -Adb $tool.Adb -Serial $serial -AppId $appId -TimeoutSec 20

        if (-not $appPid) {
            # Fall back to the component the package manager actually resolves.
            # Worth doing because monkey only ever reports "events injected: 1",
            # which says nothing about whether anything actually came up.
            Write-Info 'monkey did not start it, resolving the launcher activity ...'
            $resolved = Invoke-NativeTimed -Exe $tool.Adb -TimeoutSec 30 -NativeArgs @(
                '-s', $serial, 'shell', 'cmd', 'package', 'resolve-activity', '--brief',
                '-c', 'android.intent.category.LAUNCHER', $appId)
            $component = ''
            foreach ($line in ($resolved -split '\r?\n')) {
                $trimmed = $line.Trim()
                if ($trimmed -match '^[\w.]+/[\w.$]+$') { $component = $trimmed; break }
            }
            if ($component) {
                Write-Info "resolved launcher: $component"
                Invoke-NativeTimed -Exe $tool.Adb -TimeoutSec 60 -NativeArgs @(
                    '-s', $serial, 'shell', 'am', 'start', '-W', '-n', $component) | Out-Null
                $appPid = Get-AppPid -Adb $tool.Adb -Serial $serial -AppId $appId -TimeoutSec 20
            }
        }

        if ($appPid) {
            Write-Good "launched $appId (pid $appPid)"
        } else {
            $log = Save-FailureLog -Adb $tool.Adb -Serial $serial -ScriptRoot $ScriptRoot -Reason "$appId did not start"
            Write-Bad "$appId did not come up"
            Write-Info "logcat saved to $log"
            exit 1
        }
    }
    Write-Take $stepStart

    Write-Host ''
    Write-Host '============================================================' -ForegroundColor Green
    Write-Host (" DONE - {0} is running on {1} in {2:n1}s" -f $appId, $serial, $Watch.Elapsed.TotalSeconds) -ForegroundColor Green
    Write-Host (" Uninstall with:  adb -s {0} uninstall {1}" -f $serial, $appId)
    Write-Host '============================================================' -ForegroundColor Green
    exit 0
}
catch {
    Write-Host ''
    Write-Bad $_.Exception.Message
    if ($tool -and $serial -and $serial -ne 'pending') {
        try {
            $log = Save-FailureLog -Adb $tool.Adb -Serial $serial -ScriptRoot $ScriptRoot -Reason $_.Exception.Message
            Write-Info "logcat saved to $log"
        } catch {
            Write-Warn "could not save logcat: $($_.Exception.Message)"
        }
    }
    exit 1
}