@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\run-core-verification.ps1"
exit /b %ERRORLEVEL%
