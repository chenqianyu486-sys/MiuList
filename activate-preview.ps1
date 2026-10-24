param(
    [string] $Match = 'MiuList Preview',
    [int] $Seconds = 20
)

$ErrorActionPreference = 'Stop'
$typeSource = @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public class ActivatePreviewWin {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowTextW(IntPtr hWnd, StringBuilder text, int max);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
    public const int SW_RESTORE = 9;
    public const int SW_SHOW = 5;
    public const uint SWP_NOMOVE = 0x0002;
    public const uint SWP_NOSIZE = 0x0001;
    public const uint SWP_SHOWWINDOW = 0x0040;
    public static string Title(IntPtr h) {
        var b = new StringBuilder(512);
        GetWindowTextW(h, b, 512);
        return b.ToString();
    }
}
'@
if (-not ('ActivatePreviewWin' -as [type])) { Add-Type -TypeDefinition $typeSource }

$deadline = (Get-Date).AddSeconds([Math]::Max(1, $Seconds))
while ((Get-Date) -lt $deadline) {
    $activated = [System.Collections.ArrayList]::new()
    $callback = [ActivatePreviewWin+EnumProc] {
        param($h, $l)
        if (-not [ActivatePreviewWin]::IsWindowVisible($h)) { return $true }
        $title = [ActivatePreviewWin]::Title($h)
        if ($title.IndexOf($Match, [StringComparison]::OrdinalIgnoreCase) -lt 0) { return $true }

        [void]$activated.Add($true)
        if ([ActivatePreviewWin]::IsIconic($h)) {
            [void][ActivatePreviewWin]::ShowWindow($h, [ActivatePreviewWin]::SW_RESTORE)
        }
        [void][ActivatePreviewWin]::ShowWindow($h, [ActivatePreviewWin]::SW_SHOW)
        [void][ActivatePreviewWin]::SetWindowPos(
            $h, [IntPtr]::Zero, 0, 0, 0, 0,
            [ActivatePreviewWin]::SWP_NOMOVE -bor [ActivatePreviewWin]::SWP_NOSIZE -bor [ActivatePreviewWin]::SWP_SHOWWINDOW
        )
        [void][ActivatePreviewWin]::BringWindowToTop($h)
        [void][ActivatePreviewWin]::SetForegroundWindow($h)
        return $false
    }
    [void][ActivatePreviewWin]::EnumWindows($callback, [IntPtr]::Zero)
    if ($activated.Count -gt 0) { exit 0 }
    Start-Sleep -Milliseconds 250
}
exit 1

