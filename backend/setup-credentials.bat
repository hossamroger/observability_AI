@echo off
setlocal
cd /d "%~dp0"
if not exist "config\credentials.yml" (
    copy "config\credentials.example.yml" "config\credentials.yml" >nul
    if errorlevel 1 exit /b 1
)
start "" notepad.exe "%~dp0config\credentials.yml"
