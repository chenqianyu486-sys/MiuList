# 在已启动的模拟器上驱动 MiuList 走一次真实的浙大同步。
# 凭据只作为参数传入，不落盘、不打印、不进日志；用完即随进程消失。
param(
    [Parameter(Mandatory = $true)][string] $StudentId,
    [Parameter(Mandatory = $true)][string] $Password,
    [int] $WaitSeconds = 15
)

$ErrorActionPreference = 'Continue'
$pkg = 'com.aistudio.scheduletodo.unmq'
$ime = 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'

# A-Z = 29..54，0-9 = 7..16（不带 shift 时产生小写）
$keymap = @{}
foreach ($c in ([char[]]'abcdefghijklmnopqrstuvwxyz')) { $keymap[[string]$c] = 29 + ($c - [char]'a') }
foreach ($n in 0..9) { $keymap[[string]$n] = 7 + $n }

function Get-Ui {
    & adb shell uiautomator dump /sdcard/_drive.xml 2>&1 | Out-Null
    (& adb shell cat /sdcard/_drive.xml 2>&1 | Out-String)
}

# 取 EditText 的中心点与当前长度
function Get-Fields([string] $xml) {
    $res = @()
    foreach ($n in [regex]::Matches($xml, '<node[^>]*class="android.widget.EditText"[^>]*>')) {
        $b = [regex]::Match($n.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        $res += [pscustomobject]@{
            X    = [int]((([int]$b.Groups[1].Value) + ([int]$b.Groups[3].Value)) / 2)
            Y    = [int]((([int]$b.Groups[2].Value) + ([int]$b.Groups[4].Value)) / 2)
            Len  = ([regex]::Match($n.Value, 'text="([^"]*)"').Groups[1].Value).Length
        }
    }
    return $res
}

# 按文本找节点中心
function Get-ByText([string] $xml, [string] $label) {
    $n = [regex]::Match($xml, ('<node[^>]*text="' + [regex]::Escape($label) + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'))
    if (-not $n.Success) { return $null }
    return [pscustomobject]@{
        X = [int]((([int]$n.Groups[1].Value) + ([int]$n.Groups[3].Value)) / 2)
        Y = [int]((([int]$n.Groups[2].Value) + ([int]$n.Groups[4].Value)) / 2)
    }
}

function Tap-Field($f, [string] $value, [switch] $UseKeyEvent) {
    & adb shell input tap $f.X $f.Y 2>&1 | Out-Null
    Start-Sleep -Milliseconds 600
    & adb shell input keyevent KEYCODE_MOVE_END 2>&1 | Out-Null
    for ($i = 0; $i -lt 32; $i++) { & adb shell input keyevent KEYCODE_DEL 2>&1 | Out-Null }
    if ($UseKeyEvent) {
        foreach ($ch in $value.ToCharArray()) {
            & adb shell input keyevent $keymap[[string]$ch] 2>&1 | Out-Null
            Start-Sleep -Milliseconds 220
        }
    } else {
        & adb shell input text $value 2>&1 | Out-Null
    }
    Start-Sleep -Milliseconds 700
}

# ── 1. 拉起 App、打开「更多」→ 浙大学号 ──
& adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
Start-Sleep -Seconds 4
& adb shell input tap 300 188 2>&1 | Out-Null     # 顶栏整行 = 抽屉入口
Start-Sleep -Seconds 2

$ui = Get-Ui
$row = Get-ByText $ui '浙大学号'
if (-not $row) {
    $row = [regex]::Match($ui, 'text="[^"]*浙大学号[^"]*"')
    if ($row.Success) { & adb shell input tap 400 379 2>&1 | Out-Null }
} else {
    & adb shell input tap 400 379 2>&1 | Out-Null
}
Start-Sleep -Seconds 2

# ── 2. 输入凭据（这一步必须先禁用输入法：它会吞掉字母键事件）──
& adb shell ime disable $ime 2>&1 | Out-Null
Start-Sleep -Seconds 1

$ui = Get-Ui
$f = Get-Fields $ui
if ($f.Count -lt 2) { Write-Output "✗ 没找到两个输入框（找到 $($f.Count) 个）"; exit 1 }

Tap-Field $f[0] $StudentId
$ui = Get-Ui; $f = Get-Fields $ui
Write-Output ("学号框长度={0}" -f $f[0].Len)

Tap-Field $f[1] $Password -UseKeyEvent
$ui = Get-Ui; $f = Get-Fields $ui
Write-Output ("密码框长度={0}" -f $f[1].Len)

& adb shell ime enable $ime 2>&1 | Out-Null
& adb shell ime set $ime 2>&1 | Out-Null
Start-Sleep -Seconds 1

# ── 3. 点「拉取云端数据并落盘本地缓存」──
$ui = Get-Ui
$btn = Get-ByText $ui '拉取云端数据并落盘本地缓存'
if (-not $btn) { Write-Output '✗ 没找到拉取按钮'; exit 1 }
& adb logcat -c 2>&1 | Out-Null
& adb shell input tap $btn.X $btn.Y 2>&1 | Out-Null
Write-Output '已点击，等待同步…'
Start-Sleep -Seconds $WaitSeconds

# ── 4. 把结果读出来（只取 App 自己显示的文案，不含凭据）──
$ui = Get-Ui
$fail = Get-ByText $ui '浙大教务系统异常'
$msg = [regex]::Matches($ui, 'text="([^"]{6,200})"') |
    ForEach-Object { $_.Groups[1].Value } |
    Where-Object { $_ -match 'HTTP|失败|成功|异常|缺|没有下发|跳到|票据|会话' } |
    Select-Object -Unique
Write-Output '--- 界面上的相关文案 ---'
$msg | ForEach-Object { Write-Output $_ }
