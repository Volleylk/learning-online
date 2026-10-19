@echo off
rem Stop: agent-service AI assistant
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop.ps1"
pause
