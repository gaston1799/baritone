@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch-curseforge-1.21.5.ps1"
if errorlevel 1 pause
endlocal
