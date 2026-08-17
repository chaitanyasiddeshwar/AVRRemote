@echo off
setlocal
cd /d "%~dp0"

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

call gradlew.bat assembleDebug
if errorlevel 1 (
    echo.
    echo BUILD FAILED
    exit /b 1
)

echo.
echo APK ready: app\build\outputs\apk\debug\app-debug.apk
