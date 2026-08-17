@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "APK=app\build\outputs\apk\debug\app-debug.apk"

if not exist "%ADB%" (
    echo adb not found: %ADB%
    exit /b 1
)
if not exist "%APK%" (
    echo APK not found: %APK%
    echo Run build.bat first.
    exit /b 1
)

rem --- make sure exactly one device is connected (USB or wireless) ---
"%ADB%" devices > "%TEMP%\adb_dev.txt" 2>&1
set /a DEVN=0
for /f "usebackq" %%d in (`findstr /R /C:"device$" "%TEMP%\adb_dev.txt"`) do set /a DEVN+=1
del "%TEMP%\adb_dev.txt" 2>nul

if %DEVN% GTR 1 (
    echo Multiple adb transports detected - resetting...
    "%ADB%" disconnect >nul 2>&1
    set /a DEVN=0
)

if %DEVN% EQU 0 (
    echo No adb device connected. Scanning for wireless debugging...
    set "OK="
    "%ADB%" mdns services > "%TEMP%\adb_mdns.txt" 2>&1
    for /f "usebackq tokens=3,4" %%p in (`findstr _adb-tls-connect "%TEMP%\adb_mdns.txt"`) do (
        if not defined OK (
            set "P=%%p"
            if not "%%q"=="" set "P=%%q"
            echo Trying !P! ...
            "%ADB%" connect !P! | findstr /C:"connected to" >nul && set "OK=1"
        )
    )
    del "%TEMP%\adb_mdns.txt" 2>nul
    "%ADB%" devices | findstr /R /C:"device$" >nul
    if errorlevel 1 (
        echo Could not connect to the phone.
        echo Check that Wireless debugging is enabled, then retry.
        exit /b 1
    )
)

rem --- install and launch ---
"%ADB%" install -r "%APK%" || exit /b 1
"%ADB%" shell am start -n com.avrremote.app/.MainActivity
