Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32Diag {
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)] public struct WINDOWPLACEMENT {
        public int length, flags, showCmd;
        public POINT ptMinPosition, ptMaxPosition;
        public RECT rcNormalPosition;
    }
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool GetWindowPlacement(IntPtr hWnd, ref WINDOWPLACEMENT placement);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
}
"@ | Out-Null

$before = @(Get-Process cmd -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id)
$p = Start-Process -FilePath (Join-Path $PSScriptRoot 'start-preview.bat') -ArgumentList "-DryRun" -PassThru
Start-Sleep -Seconds 6
$new = @(Get-Process cmd -ErrorAction SilentlyContinue | Where-Object { $before -notcontains $_.Id })
"started bat -> PID $($p.Id), new cmd count $($new.Count)"
foreach ($proc in $new) {
    $proc.Refresh()
    $h = $proc.MainWindowHandle
    "cmd PID=$($proc.Id) title='$($proc.MainWindowTitle)' handle=$h"
    if ($h -ne 0) {
        $r = New-Object Win32Diag+RECT
        [Win32Diag]::GetWindowRect($h, [ref]$r) | Out-Null
        $visible = [Win32Diag]::IsWindowVisible($h)
        $wp = New-Object Win32Diag+WINDOWPLACEMENT
        $wp.length = [Runtime.InteropServices.Marshal]::SizeOf($wp)
        [Win32Diag]::GetWindowPlacement($h, [ref]$wp) | Out-Null
        "  visible=$visible rect=($($r.Left),$($r.Top))-($($r.Right),$($r.Bottom)) showCmd=$($wp.showCmd) normal=($($wp.rcNormalPosition.Left),$($wp.rcNormalPosition.Top))-($($wp.rcNormalPosition.Right),$($wp.rcNormalPosition.Bottom))"
    }
}
"workarea: $([System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea)"
