<#
.SYNOPSIS
    preview.ps1 安装阶段的回归测试。

.DESCRIPTION
    **绝不碰真设备**：所有对 adb 的调用都被换成只记录调用的桩，
    卸载桩也只往一个数组里记一笔 —— 测试里跑不出一次真的 `adb uninstall`。

    钉住这些事：

    1. 签名冲突时**不卸载**：设备上的旧包与它的数据原样留着，
       并把「要用同一把密钥出的 APK」说清楚；
    2. 普通覆盖安装走 `adb install -r`，**不清数据**；
    3. 显式重装（-Reinstall）先确认安装状态、再备份，**备份失败就不卸载**；
    4. **卸载失败就不继续安装**；
    5. **未安装**时跳过备份与卸载，直接安装；**查询失败**时一律停下。

    做法是把 preview.ps1 里的函数用 AST 解析出来原样搬进本脚本，
    只跑函数、不跑脚本主体（脚本主体是要真设备与真 Gradle 的）。

    用法：
        powershell -ExecutionPolicy Bypass -File tools\test-preview-install.ps1
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$scriptPath = Join-Path $repoRoot 'preview.ps1'
if (-not (Test-Path $scriptPath)) {
    throw "preview.ps1 not found: $scriptPath"
}

# --------------------------------------------------------------------------
# 把 preview.ps1 里的函数原样搬过来
# --------------------------------------------------------------------------

$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    $scriptPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors -and $parseErrors.Count -gt 0) {
    throw ("preview.ps1 has syntax errors: " +
           (($parseErrors | ForEach-Object { $_.Message }) -join '; '))
}

$definitions = @($ast.FindAll({
    param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst]
}, $true))
if ($definitions.Count -eq 0) { throw 'no functions found in preview.ps1' }

foreach ($d in $definitions) { Invoke-Expression $d.Extent.Text }

# preview.ps1 的函数用 $script:LastNativeExit 发布原生命令的退出码。
$script:LastNativeExit = 0

# --------------------------------------------------------------------------
# 断言与记录
# --------------------------------------------------------------------------

$script:Passed = 0
$script:Failed = 0
$script:Calls = New-Object System.Collections.ArrayList
$script:Messages = New-Object System.Collections.ArrayList

function Assert-True {
    param([bool] $Condition, [string] $Message)
    if ($Condition) {
        $script:Passed++
        Write-Host ("  PASS  " + $Message)
    } else {
        $script:Failed++
        Write-Host ("  FAIL  " + $Message) -ForegroundColor Red
    }
}

function Reset-Recording {
    $script:Calls.Clear()
    $script:Messages.Clear()
    $script:LastNativeExit = 0
    # 默认「已安装」：绝大多数用例验的是安装之后的分支。
    $script:MockPmPath = 'package:/data/app/com.example/base.apk'
    $script:MockPmExit = 0
}

function Calls-Match { param([string] $Pattern) return (@($script:Calls) -match $Pattern).Count -gt 0 }
function Call-Count { param([string] $Pattern) return (@($script:Calls) -match $Pattern).Count }

# --------------------------------------------------------------------------
# 桩：所有会碰到设备的东西都在这里被换掉
# --------------------------------------------------------------------------

# preview.ps1 自己的输出函数 —— 收进数组，别把测试输出冲乱。
function Write-Info { param([string] $m) [void]$script:Messages.Add("INFO: $m") }
function Write-Warn { param([string] $m) [void]$script:Messages.Add("WARN: $m") }
function Write-Good { param([string] $m) [void]$script:Messages.Add("GOOD: $m") }
function Write-Bad  { param([string] $m) [void]$script:Messages.Add("BAD:  $m") }

# 原生命令的桩。$script:NativeHandler 决定返回什么。
$script:NativeHandler = { param($line) return '' }
$script:MockBackupOk = $true
$script:MockRemoveAppResult = $true
$script:MockPmPath = 'package:/data/app/com.example/base.apk'
$script:MockPmExit = 0

function Invoke-NativeTimed {
    param([string] $Exe, [string[]] $NativeArgs, [int] $TimeoutSec = 120)
    $line = ($NativeArgs -join ' ')
    [void]$script:Calls.Add("native|$line")
    # `pm path` 的输出就是「装没装」的唯一外部事实，单独由 MockPm* 控制，
    # 让生产代码里的状态判定原样跑。
    if ($line -match 'shell pm path ') {
        $script:LastNativeExit = $script:MockPmExit
        return [string]$script:MockPmPath
    }
    return [string](& $script:NativeHandler $line)
}

function Wait-ForInstallReady {
    param([string] $Adb, [string] $Serial, [int] $TimeoutSec = 120)
    return [pscustomobject]@{ Ready = $true; Reason = '' }
}

# Test-PackageInstalled / Get-PackageInstallState 不设桩：它们的判定逻辑
# 正是要验的东西，外部事实全部由上面的 pm path 输出提供。

function Remove-App {
    param([string] $Adb, [string] $Serial, [string] $AppId)
    [void]$script:Calls.Add("uninstall|$AppId")
    return [bool]$script:MockRemoveAppResult
}

function Backup-AppData {
    param([string] $Adb, [string] $Serial, [string] $AppId, [string] $DestRoot)
    [void]$script:Calls.Add("backup|$AppId")
    if ($script:MockBackupOk) {
        return @{ Ok = $true; Path = (Join-Path $DestRoot 'fake-backup'); Note = 'saved: databases' }
    }
    return @{ Ok = $false; Path = $DestRoot; Note = 'the device refuses root' }
}

# --------------------------------------------------------------------------
# 1. 签名冲突：不卸载，旧包与它的数据原样留着
# --------------------------------------------------------------------------

Write-Host "`n[1] a signature clash must not remove anything" -ForegroundColor Cyan
Reset-Recording
$script:NativeHandler = {
    param($line)
    $script:LastNativeExit = 1
    return 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.example signatures do not match]'
}
$result = Install-Apk -Adb 'adb' -Serial 'emulator-5554' -ApkPath 'app.apk' `
        -AppId 'com.example' -Attempts 3 -TimeoutSec 5 -AutoRecover $true

Assert-True ($result.Ok -eq $false) 'the install must fail'
Assert-True ($result.Class -eq 'signature') 'the failure must be classified as a signature clash'
Assert-True (-not (Calls-Match 'uninstall')) 'nothing may be uninstalled on a signature clash'
Assert-True (-not (Calls-Match 'native\|.*\buninstall\b')) 'no adb uninstall may be issued'
Assert-True ((@($script:Messages) -match 'left untouched').Count -gt 0) 'the old app and its data are said to be untouched'
Assert-True ((@($script:Messages) -match 'same key').Count -gt 0) 'the message points at the same signing key'

# --------------------------------------------------------------------------
# 2. 普通覆盖安装：install -r，不卸载、不清数据
# --------------------------------------------------------------------------

Write-Host "`n[2] a normal update installs over the app and keeps its data" -ForegroundColor Cyan
Reset-Recording
$script:NativeHandler = { param($line) $script:LastNativeExit = 0; return 'Success' }
$result = Install-Apk -Adb 'adb' -Serial 'emulator-5554' -ApkPath 'app.apk' `
        -AppId 'com.example' -Attempts 3 -TimeoutSec 5 -AutoRecover $true

Assert-True ($result.Ok -eq $true) 'the install must succeed'
Assert-True (Calls-Match "native\|.*\binstall\b.*-r") 'the install must use "adb install -r"'
Assert-True (-not (Calls-Match 'uninstall')) 'a normal update must not uninstall'

# --------------------------------------------------------------------------
# 3. 显式重装：备份失败就不卸载
# --------------------------------------------------------------------------

Write-Host "`n[3] an explicit reinstall stops when the backup fails" -ForegroundColor Cyan
Reset-Recording
$script:MockBackupOk = $false
$result = Remove-AppForReinstall -Adb 'adb' -Serial 'emulator-5554' `
        -AppId 'com.example' -ScriptRoot $env:TEMP

Assert-True ($result.Ok -eq $false) 'the reinstall must fail'
Assert-True (Calls-Match 'backup\|') 'the backup must have been attempted'
Assert-True (-not (Calls-Match 'uninstall')) 'a failed backup must not lead to an uninstall'
Assert-True ($result.BackupPath -eq '') 'no backup path may be reported when nothing was backed up'

# --------------------------------------------------------------------------
# 4. 显式重装：卸载失败就停
# --------------------------------------------------------------------------

Write-Host "`n[4] an explicit reinstall stops when the uninstall fails" -ForegroundColor Cyan
Reset-Recording
$script:MockBackupOk = $true
$script:MockRemoveAppResult = $false
$result = Remove-AppForReinstall -Adb 'adb' -Serial 'emulator-5554' `
        -AppId 'com.example' -ScriptRoot $env:TEMP

Assert-True ($result.Ok -eq $false) 'the reinstall must fail'
Assert-True (Calls-Match 'uninstall\|') 'the uninstall must have been attempted'
Assert-True (($result.Reason -match 'removed')) 'the reason must say the app could not be removed'

# --------------------------------------------------------------------------
# 5. 显式重装：备份在卸载之前
# --------------------------------------------------------------------------

Write-Host "`n[5] an explicit reinstall backs up before it removes" -ForegroundColor Cyan
Reset-Recording
$script:MockBackupOk = $true
$script:MockRemoveAppResult = $true
$result = Remove-AppForReinstall -Adb 'adb' -Serial 'emulator-5554' `
        -AppId 'com.example' -ScriptRoot $env:TEMP

Assert-True ($result.Ok -eq $true) 'the reinstall must succeed'
$order = @($script:Calls)
$backupAt = ($order | Select-String -Pattern '^backup\|').LineNumber
$uninstallAt = ($order | Select-String -Pattern '^uninstall\|').LineNumber
Assert-True ($backupAt -and $uninstallAt -and $backupAt -lt $uninstallAt) 'the backup must come before the uninstall'
Assert-True ($result.BackupPath -ne '') 'the backup path must be reported'

# --------------------------------------------------------------------------
# 6. 重装失败就不继续安装
# --------------------------------------------------------------------------

Write-Host "`n[6] a failed reinstall must not be followed by an install" -ForegroundColor Cyan
Reset-Recording
$script:MockBackupOk = $false
$threw = $false
try {
    Install-AppWithOptionalReinstall -Adb 'adb' -Serial 'emulator-5554' -ApkPath 'app.apk' `
        -AppId 'com.example' -Reinstall $true -ScriptRoot $env:TEMP -TimeoutSec 5 -AutoRecover $true | Out-Null
} catch {
    $threw = $true
}
Assert-True $threw 'the run must stop'
Assert-True (-not (Calls-Match 'native\|.*\binstall\b')) 'no install may be issued after a failed reinstall'
Assert-True (-not (Calls-Match 'uninstall')) 'nothing may be uninstalled after a failed backup'

# --------------------------------------------------------------------------
# 7. 不带 -Reinstall 的安装：既不备份也不卸载
# --------------------------------------------------------------------------

Write-Host "`n[7] an update without -Reinstall touches neither the backup nor the uninstall" -ForegroundColor Cyan
Reset-Recording
$script:MockBackupOk = $true
$script:MockRemoveAppResult = $true
$script:NativeHandler = { param($line) $script:LastNativeExit = 0; return 'Success' }
$result = Install-AppWithOptionalReinstall -Adb 'adb' -Serial 'emulator-5554' -ApkPath 'app.apk' `
        -AppId 'com.example' -Reinstall $false -ScriptRoot $env:TEMP -TimeoutSec 5 -AutoRecover $true

Assert-True ($result.Ok -eq $true) 'the install must succeed'
Assert-True (Calls-Match "native\|.*\binstall\b.*-r") 'the install must use "adb install -r"'
Assert-True (-not (Calls-Match 'uninstall')) 'an update must not uninstall'
Assert-True (-not (Calls-Match 'backup\|')) 'an update must not go through the backup path'

# --------------------------------------------------------------------------
# 8. 脚本里不许再有「让脚本自动卸载」的引导
# --------------------------------------------------------------------------

Write-Host "`n[8] the script must not offer to uninstall the old app for the user" -ForegroundColor Cyan
$text = Get-Content -Path $scriptPath -Raw -Encoding UTF8
Assert-True ($text -notmatch 'NoAutoRecover off \(the default\)') 'the old "-NoAutoRecover off lets the script do it" hint must be gone'
Assert-True ($text -notmatch 'removing the old copy and installing this one instead') 'the old "removing the old copy" line must be gone'

$installApkText = ($definitions | Where-Object { $_.Name -eq 'Install-Apk' }).Extent.Text
Assert-True ($installApkText -notmatch 'Remove-App') 'Install-Apk must not remove anything'
Assert-True ($installApkText -notmatch "'uninstall'") 'Install-Apk must not issue an uninstall'

Write-Host "`n[9] a successful reinstall continues to installation" -ForegroundColor Cyan
Reset-Recording
$script:MockBackupOk = $true
$script:MockRemoveAppResult = $true
$result = Install-AppWithOptionalReinstall -Adb 'adb' -Serial 'emulator-5554' -ApkPath 'app.apk' `
        -AppId 'com.example' -Reinstall $true -ScriptRoot $env:TEMP -TimeoutSec 5
Assert-True ($result.Ok -eq $true) 'the complete reinstall must succeed'
Assert-True ($result.BackupPath -eq (Join-Path $env:TEMP 'fake-backup')) 'the backup path must survive the install'
Assert-True ((@($script:Calls) -join ';') -match 'pm path com\.example.*;backup\|.*;uninstall\|.*;native\|.*install -r') 'query, backup, uninstall and install run in order'

Write-Host "`n[10] an uninstall failure stops the complete reinstall" -ForegroundColor Cyan
Reset-Recording
$script:MockRemoveAppResult = $false
$failure = ''
try {
    Install-AppWithOptionalReinstall -Adb 'adb' -Serial 'emulator-5554' -ApkPath 'app.apk' `
        -AppId 'com.example' -Reinstall $true -ScriptRoot $env:TEMP -TimeoutSec 5 | Out-Null
} catch {
    $failure = $_.Exception.Message
}
Assert-True ($failure -match 'the app could not be removed') 'failure must identify the unsuccessful uninstall'
Assert-True (-not (Calls-Match 'native\|.*\binstall\b')) 'installation must not follow a failed uninstall'

Write-Host "`n[11] a reinstall on a device without the app backs up and removes nothing" -ForegroundColor Cyan
Reset-Recording
# 现代 Android 的 pm path 对不存在的包返回 1 且没有任何输出。
$script:MockPmPath = ''
$script:MockPmExit = 1
$script:MockBackupOk = $true
$script:MockRemoveAppResult = $true
$script:NativeHandler = { param($line) $script:LastNativeExit = 0; return 'Success' }
$result = Install-AppWithOptionalReinstall -Adb 'adb' -Serial 'emulator-5554' -ApkPath 'app.apk' `
        -AppId 'com.example' -Reinstall $true -ScriptRoot $env:TEMP -TimeoutSec 5
Assert-True ($result.Ok -eq $true) 'a first install with -Reinstall must succeed'
Assert-True ($result.BackupPath -eq '') 'no backup path may be reported when there was no data'
Assert-True (-not (Calls-Match 'backup\|')) 'an app that is not installed must not be backed up'
Assert-True (-not (Calls-Match 'uninstall')) 'an app that is not installed must not be uninstalled'
Assert-True (Calls-Match "native\|.*\binstall\b.*-r") 'the install must still happen'
Assert-True ((@($script:Calls) -join ';') -match 'pm path com\.example.*;native\|.*install -r') 'the state query comes before the install'

Write-Host "`n[12] a query that fails is never read as an absent app" -ForegroundColor Cyan
foreach ($case in @(
    @{ Name = 'offline'; Output = 'error: device offline'; Exit = 1 },
    @{ Name = 'timeout'; Output = '<no response from adb after 30 s>'; Exit = -1 },
    @{ Name = 'permission'; Output = 'error: insufficient permissions for device'; Exit = 1 }
)) {
    Reset-Recording
    $script:MockPmPath = $case.Output
    $script:MockPmExit = $case.Exit
    $script:MockBackupOk = $true
    $script:MockRemoveAppResult = $true
    $script:NativeHandler = { param($line) $script:LastNativeExit = 0; return 'Success' }
    $failure = ''
    try {
        Install-AppWithOptionalReinstall -Adb 'adb' -Serial 'emulator-5554' -ApkPath 'app.apk' `
            -AppId 'com.example' -Reinstall $true -ScriptRoot $env:TEMP -TimeoutSec 5 | Out-Null
    } catch {
        $failure = $_.Exception.Message
    }
    Assert-True ($failure -match 'could not determine whether the app is installed') "a failed $($case.Name) query must stop the run"
    Assert-True (-not (Calls-Match 'backup\|')) "a failed $($case.Name) query must not back up"
    Assert-True (-not (Calls-Match 'uninstall')) "a failed $($case.Name) query must not uninstall"
    Assert-True (-not (Calls-Match 'native\|.*\binstall\b')) "a failed $($case.Name) query must not install"
}

Write-Host "`n[13] every reliable absent answer from pm still installs" -ForegroundColor Cyan
foreach ($case in @(
    @{ Name = 'empty with exit 0'; Output = ''; Exit = 0 },
    @{ Name = 'not-found message'; Output = 'Error: package com.example not found'; Exit = 1 }
)) {
    Reset-Recording
    $script:MockPmPath = $case.Output
    $script:MockPmExit = $case.Exit
    $script:MockBackupOk = $true
    $script:MockRemoveAppResult = $true
    $script:NativeHandler = { param($line) $script:LastNativeExit = 0; return 'Success' }
    $result = Install-AppWithOptionalReinstall -Adb 'adb' -Serial 'emulator-5554' -ApkPath 'app.apk' `
            -AppId 'com.example' -Reinstall $true -ScriptRoot $env:TEMP -TimeoutSec 5
    Assert-True ($result.Ok -eq $true) "the install must proceed with an absent answer: $($case.Name)"
    Assert-True (-not (Calls-Match 'backup\|')) "nothing is backed up for an absent app: $($case.Name)"
    Assert-True (-not (Calls-Match 'uninstall')) "nothing is uninstalled for an absent app: $($case.Name)"
    Assert-True (Calls-Match "native\|.*\binstall\b.*-r") "the install must happen: $($case.Name)"
}

# Exercise the real backup logic while keeping all native calls and filesystem writes mocked.
Invoke-Expression ($definitions | Where-Object { $_.Name -eq 'Backup-AppData' }).Extent.Text
function New-Item { param($ItemType, $Path, $ErrorAction) }
function Wait-ForDeviceOnline { param($Adb, $Serial, $TimeoutSec) return $true }
$script:MockRemoveAppResult = $true
$script:FailBackupStep = ''
$script:NativeHandler = {
    param($line)
    $script:LastNativeExit = 0
    if ($script:FailBackupStep -and $line -match $script:FailBackupStep) {
        $script:LastNativeExit = 1
        return 'operation failed'
    }
    if ($line -match 'shell id -u$') { return '0' }
    if ($line -match 'shell ls -1') { return "databases`nshared_prefs`nfiles" }
    return 'Success'
}

Write-Host "`n[14] any incomplete backup prevents uninstall and install" -ForegroundColor Cyan
foreach ($step in @('root$', 'shell id -u$', 'force-stop', 'shell ls -1', 'pull .*/databases ', 'pull .*/shared_prefs ', 'pull .*/files ')) {
    Reset-Recording
    $script:FailBackupStep = $step
    $failure = ''
    try {
        Install-AppWithOptionalReinstall -Adb 'adb' -Serial 'emulator-5554' -ApkPath 'app.apk' `
            -AppId 'com.example' -Reinstall $true -ScriptRoot $env:TEMP -TimeoutSec 5 | Out-Null
    } catch {
        $failure = $_.Exception.Message
    }
    Assert-True ($failure -match 'could not be backed up') "backup error must stop reinstall: $step"
    Assert-True (-not (Calls-Match 'uninstall')) "backup error must not uninstall: $step"
    Assert-True (-not (Calls-Match 'native\|.*\binstall\b')) "backup error must not install: $step"
}

Write-Host "`n[15] absent optional directories do not invalidate a complete backup" -ForegroundColor Cyan
Reset-Recording
$script:FailBackupStep = ''
$result = Install-AppWithOptionalReinstall -Adb 'adb' -Serial 'emulator-5554' -ApkPath 'app.apk' `
        -AppId 'com.example' -Reinstall $true -ScriptRoot $env:TEMP -TimeoutSec 5
Assert-True ($result.Ok -eq $true) 'complete backup permits reinstall'
Assert-True ((Call-Count 'native\|.*pull ') -eq 3) 'all three existing data directories must be copied'
Assert-True (-not (Calls-Match 'pull .*no_backup')) 'absent no_backup is not a copy failure'
Assert-True (Calls-Match 'force-stop') 'app is stopped before copying SQLite files'

Write-Host ''
Write-Host ("{0}/{1} checks passed" -f $script:Passed, ($script:Passed + $script:Failed)) -ForegroundColor Cyan
if ($script:Failed -gt 0) { exit 1 }
exit 0
