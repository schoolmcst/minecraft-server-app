@echo off
setlocal
cd /d "%~dp0"

rem Opens the Minecraft Server app. The first time, it also downloads Java for it (about 50 MB).
rem Tip: inside the app, Settings > "Add desktop shortcut" gives you a one-click icon.

set "JAVAW=%~dp0runtime\bin\javaw.exe"
if exist "%JAVAW%" goto run

title Minecraft Server - first-time setup
echo.
echo   Setting up Java for the server app (one time only, about 50 MB)...
echo.
set "ARCH=x64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ARCH=aarch64"
if exist "runtime-download" rmdir /s /q "runtime-download"
mkdir "runtime-download"
curl.exe -fL --progress-bar -o "runtime-download\java.zip" "https://api.adoptium.net/v3/binary/latest/25/ga/windows/%ARCH%/jre/hotspot/normal/eclipse"
if errorlevel 1 goto fail
tar -xf "runtime-download\java.zip" -C "runtime-download"
if errorlevel 1 goto fail
for /d %%D in ("runtime-download\jdk*") do move "%%D" "runtime" >nul
rmdir /s /q "runtime-download"
if not exist "%JAVAW%" goto fail

:run
start "" "%JAVAW%" -jar "%~dp0launcher.jar"
exit /b 0

:fail
if exist "runtime-download" rmdir /s /q "runtime-download"
echo.
echo   ! Couldn't set up Java automatically. Check your internet connection and try again.
echo     Or do it by hand: download the Java 25 JRE .zip from
echo     https://adoptium.net/temurin/releases/?version=25 , unzip it into this folder,
echo     and rename the unzipped folder to  runtime
echo.
echo   Press any key to close this window.
pause >nul
exit /b 1
