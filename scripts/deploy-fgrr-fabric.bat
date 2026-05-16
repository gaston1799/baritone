@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy-fgrr-fabric.ps1" %*
exit /b %errorlevel%
