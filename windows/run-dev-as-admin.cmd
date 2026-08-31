@echo off
setlocal
set "APP_DIR=%~dp0build\compose\binaries\main\app\Firefly"
if not exist "%APP_DIR%\Firefly.exe" (
  echo Firefly portable build not found.
  echo Run: gradlew.bat createDistributable
  pause
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -Verb RunAs -WorkingDirectory '%APP_DIR%' -FilePath '%APP_DIR%\Firefly.exe'"
endlocal
