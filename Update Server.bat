@echo off
rem Downloads the newest Paper, Geyser and Floodgate, then starts the server.
rem Your world is backed up automatically before the server starts.
cd /d "%~dp0"
call "Start Server.bat" --update
