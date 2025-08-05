@echo off
REM Batch wrapper for PowerShell FIX file fetching scripts
REM Usage: fetch-fix-files.bat [server] [remote_path] [local_path]

setlocal enabledelayedexpansion

REM Default values
set SERVER=%1
set REMOTE_PATH=%2
set LOCAL_PATH=%3

if "%SERVER%"=="" set SERVER=localhost
if "%REMOTE_PATH%"=="" set REMOTE_PATH=C:\fix_logs
if "%LOCAL_PATH%"=="" set LOCAL_PATH=.\downloaded_fix_files

echo.
echo ========================================
echo    FIX File Fetcher
echo ========================================
echo Server: %SERVER%
echo Remote Path: %REMOTE_PATH%
echo Local Path: %LOCAL_PATH%
echo.

REM Check if PowerShell scripts exist
if not exist "%~dp0simple-fix-file-fetcher.ps1" (
    echo ERROR: simple-fix-file-fetcher.ps1 not found in script directory
    echo Please ensure all PowerShell scripts are in the same directory as this batch file
    pause
    exit /b 1
)

REM Set execution policy for current process
powershell -Command "Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope Process -Force"

REM Run the PowerShell script
echo Running FIX file fetcher...
powershell -File "%~dp0simple-fix-file-fetcher.ps1" -RemoteComputer "%SERVER%" -RootPath "%REMOTE_PATH%" -LocalPath "%LOCAL_PATH%"

if %ERRORLEVEL% equ 0 (
    echo.
    echo SUCCESS: FIX file fetching completed
    echo Files saved to: %LOCAL_PATH%
) else (
    echo.
    echo ERROR: FIX file fetching failed with error code %ERRORLEVEL%
)

echo.
pause