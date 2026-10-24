<#
.SYNOPSIS
    Keep a window inside the visible desktop area.

.DESCRIPTION
    Windows sometimes places a freshly created console / emulator window at
    coordinates that sit outside every monitor, or gives it a size larger than
    the work area, so it can no longer be dragged back with the mouse.
    This script finds the target window by title substring and/or owning
    process name and moves it back into view (shrinking it only when it does
    not fit at all). It then keeps watching for as long as the window exists,
    because the Android emulator re-creates and resizes its window while the
    AVD boots, and a console window can be pushed off-screen again as Gradle
    keeps printing. It exits when the window is gone, when Seconds elapse, or
    - when -StableChecks is set - when the window has been still for that many
    consecutive checks.

.PARAMETER StableChecks
    0 (default) means "keep watching until the window disappears".

.PARAMETER MissingChecks
    Consecutive passes with no window found before giving up on a window that
    was seen before. This is what ends the watcher after the batch file ends.

.PARAMETER WaitSeconds
    How long to keep waiting for a window that has never appeared.

.PARAMETER OutChecks
    Consecutive passes a window must stay out of bounds before it is moved, so
    a transient relayout is not fought with.

.EXAMPLE
    .\clamp-window.ps1 -Match 'MiuList Preview' -Seconds 6

.EXAMPLE
    .\clamp-window.ps1 -Match 'MiuList_Preview' -ProcessName 'emulator,qsn' -Seconds 1800
#>
[CmdletBinding()]
param(
    [string] $Match         = '',
    [string] $ProcessName   = '',
    [int]    $Seconds       = 8,
    [int]    $IntervalMs    = 400,
    [int]    $StableChecks  = 0,
    [int]    $MissingChecks = 60,
    [int]    $WaitSeconds   = 120,
    [int]    $OutChecks     = 2
)

$ErrorActionPreference = 'Stop'

if (-not $Match -and -not $ProcessName) {
    Write-Output 'clamp: nothing to do, pass -Match and/or -ProcessName.'
    exit 2
}

$typeSource = @'
using System;
using System.Text;
using System.Runtime.InteropServices;

public class ClampWin
{
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);

    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hWnd, StringBuilder text, int max);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint procId);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll", SetLastError = true)] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr insertAfter, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);

    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }

    public const uint SWP_NOZORDER   = 0x0004;
    public const uint SWP_NOACTIVATE = 0x0010;
    public const uint SWP_SHOWWINDOW = 0x0040;
    public const int  SW_RESTORE     = 9;
}
'@

if (-not ('ClampWin' -as [type])) { Add-Type -TypeDefinition $typeSource }
Add-Type -AssemblyName System.Windows.Forms

function Get-WindowTitle {
    param([IntPtr] $Handle)
    $buffer = New-Object System.Text.StringBuilder 512
    [void][ClampWin]::GetWindowTextW($Handle, $buffer, $buffer.Capacity)
    return $buffer.ToString()
}

function Get-TargetWindows {
    param([string] $TitleFilter, [string] $ProcessRegex)

    $handles = New-Object System.Collections.ArrayList

    $callback = [ClampWin+EnumProc] {
        param($handle, $lParam)

        if ([ClampWin]::IsWindowVisible($handle)) {
            $title = Get-WindowTitle -Handle $handle

            $ownerId = 0
            [void][ClampWin]::GetWindowThreadProcessId($handle, [ref]$ownerId)
            $ownerName = ''
            try { $ownerName = (Get-Process -Id $ownerId -ErrorAction Stop).ProcessName } catch { $ownerName = '' }

            $hit = $false
            if ($ProcessRegex -and $ownerName -and $ownerName -match $ProcessRegex) { $hit = $true }
            if ((-not $hit) -and $TitleFilter -and $title.IndexOf($TitleFilter, [StringComparison]::OrdinalIgnoreCase) -ge 0) { $hit = $true }

            if ($hit -and $title) { [void]$handles.Add($handle) }
        }
        return $true
    }

    [void][ClampWin]::EnumWindows($callback, [IntPtr]::Zero)
    return $handles
}

function Get-ClampPlan {
    param([ClampWin+RECT] $Rect)

    $best = $null
    $bestArea = 0
    foreach ($screen in [System.Windows.Forms.Screen]::AllScreens) {
        $ix = [Math]::Max(0, [Math]::Min($Rect.Right, $screen.Bounds.Right) - [Math]::Max($Rect.Left, $screen.Bounds.Left))
        $iy = [Math]::Max(0, [Math]::Min($Rect.Bottom, $screen.Bounds.Bottom) - [Math]::Max($Rect.Top, $screen.Bounds.Top))
        $area = $ix * $iy
        if ($area -gt $bestArea) { $bestArea = $area; $best = $screen }
    }
    if ($null -eq $best) { $best = [System.Windows.Forms.Screen]::PrimaryScreen }

    $areaBox = $best.WorkingArea
    $width  = $Rect.Right - $Rect.Left
    $height = $Rect.Bottom - $Rect.Top

    $newWidth  = $width
    $newHeight = $height
    $resize = $false
    if ($newWidth  -gt $areaBox.Width)  { $newWidth  = $areaBox.Width;  $resize = $true }
    if ($newHeight -gt $areaBox.Height) { $newHeight = $areaBox.Height; $resize = $true }

    $x = $Rect.Left
    $y = $Rect.Top
    if ($x + $newWidth  -gt $areaBox.Right) { $x = $areaBox.Right  - $newWidth }
    if ($x             -lt $areaBox.Left)  { $x = $areaBox.Left }
    if ($y + $newHeight -gt $areaBox.Bottom){ $y = $areaBox.Bottom - $newHeight }
    if ($y             -lt $areaBox.Top)   { $y = $areaBox.Top }

    $changed = $resize -or ($x -ne $Rect.Left) -or ($y -ne $Rect.Top)

    return [pscustomobject]@{
        Changed  = $changed
        X        = $x
        Y        = $y
        Width    = $newWidth
        Height   = $newHeight
        Resizing = $resize
        Monitor  = $best.DeviceName
    }
}

$processRegex = ''
if ($ProcessName) {
    $names = @($ProcessName -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($names.Count -gt 0) {
        $processRegex = '(?i)^(' + (($names | ForEach-Object { [regex]::Escape($_) }) -join '|') + ')$'
    }
}

$started  = Get-Date
$deadline = $started.AddSeconds([Math]::Max(1, $Seconds))
$waitFor  = $started.AddSeconds([Math]::Max(1, $WaitSeconds))
$stable   = 0
$missing  = 0
$everSeen = $false
$moved    = 0
$pending  = @{}

# The batch file is our parent: if it goes away, so should this watcher, even
# if some window still matches the filter.
$parentId = 0
try {
    $self  = Get-CimInstance Win32_Process -Filter "ProcessId = $PID"
    $parentId = [int] $self.ParentProcessId
} catch {
    $parentId = 0
}

while ((Get-Date) -lt $deadline) {
    if ($parentId -gt 0 -and -not (Get-Process -Id $parentId -ErrorAction SilentlyContinue)) {
        Write-Output 'clamp: parent process is gone, done.'
        break
    }

    $targets = @(Get-TargetWindows -TitleFilter $Match -ProcessRegex $processRegex)
    $touchedThisPass = $false

    foreach ($handle in $targets) {
        $rect = New-Object ClampWin+RECT
        if (-not [ClampWin]::GetWindowRect($handle, [ref]$rect)) { continue }

        $width  = $rect.Right - $rect.Left
        $height = $rect.Bottom - $rect.Top
        if ($width -le 0 -or $height -le 0) { continue }

        $plan = Get-ClampPlan -Rect $rect
        if (-not $plan.Changed) {
            $pending.Remove($handle.ToInt64())
            continue
        }

        # Debounce: the emulator re-lays out its own window while booting, so
        # only act once the same window has been out of bounds for two passes.
        $key  = $handle.ToInt64()
        $seen = [int] $pending[$key]
        $pending[$key] = $seen + 1
        if ($seen -lt $OutChecks - 1) { continue }

        if ([ClampWin]::IsIconic($handle)) { [void][ClampWin]::ShowWindow($handle, [ClampWin]::SW_RESTORE) }

        $flags = [ClampWin]::SWP_NOZORDER -bor [ClampWin]::SWP_NOACTIVATE -bor [ClampWin]::SWP_SHOWWINDOW
        [void][ClampWin]::SetWindowPos($handle, [IntPtr]::Zero, $plan.X, $plan.Y, $plan.Width, $plan.Height, $flags)

        # Windows may refuse the size (minimum window size); retry anchored top-left.
        $after = New-Object ClampWin+RECT
        if ([ClampWin]::GetWindowRect($handle, [ref]$after)) {
            $recheck = Get-ClampPlan -Rect $after
            if ($recheck.Changed) {
                [void][ClampWin]::SetWindowPos($handle, [IntPtr]::Zero, $recheck.X, $recheck.Y, $after.Right - $after.Left, $after.Bottom - $after.Top, $flags)
                $plan = $recheck
            }
        }

        $touchedThisPass = $true
        $moved++
        $verb = if ($plan.Resizing) { 'resized' } else { 'moved' }
        Write-Output ("clamp: {0} -> {1} {2},{3} {4}x{5} on {6}" -f
            (Get-WindowTitle -Handle $handle), $verb, $plan.X, $plan.Y, $plan.Width, $plan.Height, $plan.Monitor)
    }

    # Stay alive for as long as the window exists: the emulator re-creates and
    # repositions itself at several points while the AVD boots, and a console
    # window can be pushed off-screen again as Gradle keeps printing.
    if ($targets.Count -gt 0) {
        $everSeen = $true
        $missing  = 0
        if ($touchedThisPass) { $stable = 0 } else { $stable++ }
        if ($StableChecks -gt 0 -and $stable -ge $StableChecks) { break }
    } else {
        $missing++
        if ($everSeen) {
            if ($missing -ge [Math]::Max(2, $MissingChecks)) { break }
        } elseif ((Get-Date) -ge $waitFor) {
            Write-Output 'clamp: target window never showed up, giving up.'
            break
        }
    }

    Start-Sleep -Milliseconds ([Math]::Max(100, $IntervalMs))
}

if ($moved -eq 0) { Write-Output 'clamp: window already inside the screen, nothing moved.' }
exit 0
