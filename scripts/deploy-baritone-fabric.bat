@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy-baritone-fabric.ps1" %*
exit /b %errorlevel%
