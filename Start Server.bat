@echo off
setlocal
cd /d "%~dp0"
title Minecraft Server
chcp 65001 >nul
cls

rem ---- Memory for the server. 4G suits 1-5 players; use 3G if this PC has only 8 GB of RAM.
set "RAM=4G"

set "JAVA=%~dp0runtime\bin\java.exe"
if not exist "%JAVA%" call :setup_java
if not exist "%JAVA%" goto :java_failed

"%JAVA%" -jar launcher.jar %RAM% %*

echo.
echo   Press any key to close this window.
pause >nul
exit /b


:setup_java
echo.
echo   Setting up Java for the server (one time only, about 50 MB)...
echo.
set "ARCH=x64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "ARCH=aarch64"
if exist "runtime-download" rmdir /s /q "runtime-download"
mkdir "runtime-download"
curl.exe -fL --progress-bar -o "runtime-download\java.zip" "https://api.adoptium.net/v3/binary/latest/25/ga/windows/%ARCH%/jre/hotspot/normal/eclipse"
if errorlevel 1 goto :eof
tar -xf "runtime-download\java.zip" -C "runtime-download"
if errorlevel 1 goto :eof
for /d %%D in ("runtime-download\jdk*") do move "%%D" "runtime" >nul
rmdir /s /q "runtime-download"
cls
goto :eof


:java_failed
if exist "runtime-download" rmdir /s /q "runtime-download"
echo.
echo   ! Couldn't set up Java automatically. Check your internet connection and try again.
echo     Or do it by hand: download the Java 25 JRE .zip from
echo     https://adoptium.net/temurin/releases/?version=25 , unzip it here,
echo     and rename the unzipped folder to  runtime
echo.
echo   Press any key to close this window.
pause >nul
exit /b 1
