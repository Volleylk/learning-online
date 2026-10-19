@echo off
rem One-click start: agent-service AI assistant
rem Content is ASCII to avoid codepage issues; the PowerShell script shows Chinese messages.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
