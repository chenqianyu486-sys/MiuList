@echo off
REM ============================================================
REM  MiuList - local preview launcher
REM  Double-click this file: builds the APK, boots the AVD,
REM  installs, and launches the app.
REM
REM  All the real work lives in preview.ps1. This file only sets up
REM  the console, keeps it on screen, and forwards arguments, e.g.
REM      start-preview.bat -SkipBuild
REM      start-preview.bat -Device emulator-5554
REM      start-preview.bat -RestartEmulator
REM      start-preview.bat -ColdBoot
REM      start-preview.bat -Gpu swiftshader
REM      start-preview.bat -Reinstall
REM      start-preview.bat -Display base
REM
REM  What it does about a slow boot:
REM    * the AVD is started BEFORE Gradle, so it boots while the build
REM      runs instead of after it;
REM    * it waits for sys.boot_completed=1 and for the package manager
REM      to answer before it installs, and it keeps waiting - an
REM      emulator that reports "device" to adb can still refuse an
REM      install for another dozen seconds;
REM    * if the device never becomes ready, it throws the saved
REM      snapshot away and boots cold, once, by itself.
REM
REM  What it does about the screen:
REM    * before the app comes up, the emulator is put back on a known
REM      display profile, so a leftover from the screenshot matrix
REM      (1600x1200, or a font at 2.0) cannot distort the preview.
REM        -Display phone (default)  用户真机 1080x2414 @520dpi, font 1.16
REM        -Display base             设计基准 1080x2400 @420dpi, font 1.0
REM        -Display keep             do not touch the display at all
REM      A real phone is never touched - its own ratio and font are
REM      the normal ones.
REM
REM  What it does about an app that is already on the device:
REM    * same signature  -> installed over it, data kept;
REM    * other signature -> the run stops and says so. The old copy and
REM      its data are left untouched; an APK signed with the same key is
REM      needed to install over it.
REM    * -Reinstall      -> the app is removed first (its data on the
REM      device goes with it). The data is copied off the device first,
REM      and if that does not work nothing is removed.
REM
REM  Black screen on boot?  Try, in this order:
REM      start-preview.bat -ColdBoot
REM      start-preview.bat -ColdBoot -Gpu swiftshader
REM  -ColdBoot skips the saved snapshot AND stops writing a new one.
REM  -Gpu overrides the AVD's renderer (auto/host/software/lavapipe/swiftshader/
REM  swangle); swiftshader never touches the host GPU.
REM
REM  NOTE: run this from YOUR OWN desktop session. Long-lived GUI
REM  processes launched from an agent/tool shell get reaped.
REM ============================================================

setlocal

set "SCRIPT_DIR=%~dp0"
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

REM Windows Terminal opens a double-clicked .bat as a tab in the last window.
REM If that window is behind the emulator, the batch looks like it did nothing.
REM Re-launch into a NEW Windows Terminal window; the guard stops recursion.
if defined WT_SESSION if not defined MIULIST_PREVIEW_NEW_WINDOW (
    where wt.exe >nul 2>&1
    if not errorlevel 1 (
        set "MIULIST_PREVIEW_NEW_WINDOW=1"
        start "" wt.exe -w -1 cmd /c ""%~f0" %*
        exit /b
    )
)

REM ------------------------------------------------------------
REM  This display is 3072x1920 @225% scaling, i.e. only 1365x853
REM  of usable desktop. Windows happily parks a new console (or
REM  the emulator) outside that area, and a console also grows
REM  past the edge while Gradle prints. clamp-window.ps1 keeps
REM  both back inside the work area; the console buffer below is
REM  pinned so the window cannot grow in the first place.
REM ------------------------------------------------------------
title MiuList Preview
mode con: cols=120 lines=42 >nul 2>&1

REM A new console can open minimized, behind the always-on-top emulator, or
REM restored from an off-screen Windows Terminal window. Restore + foreground
REM it as soon as the title exists; clamp-window.ps1 then keeps it in bounds.
start "" "%PS%" -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%SCRIPT_DIR%activate-preview.ps1" -Match "MiuList Preview" -Seconds 20

REM Stays alive while this console exists; exits ~7s after the window is gone.
start "" "%PS%" -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%SCRIPT_DIR%clamp-window.ps1" -Match "MiuList Preview" -Seconds 3600 -IntervalMs 300 -MissingChecks 25

"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%preview.ps1" %*
set "RC=%ERRORLEVEL%"

echo.
if not "%RC%"=="0" (
    echo Aborted - see the messages above.
    echo.
    echo The emulator is left running on purpose; close it from its own window,
    echo or:  emulator -avd MiuList_Preview
    echo A device-side dump is written to preview-failure-TIMESTAMP.log next to this file.
    echo Re-run with -ColdBoot if the emulator never finishes booting, -SkipBuild to skip Gradle,
    echo or -DryRun to print the resolved plan.
) else (
    echo Close this window when you are done - the emulator keeps running.
)
pause
exit /b %RC%