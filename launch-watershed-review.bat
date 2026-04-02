@echo off
setlocal

set "ROOT_DIR=%~dp0"
set "PORT=8124"
set "URL=http://127.0.0.1:%PORT%/docs/watershed-review-tool.html"
set "REVIEW_FILE=%ROOT_DIR%watershed-review\watershed-review.json"
set "STATE_FILE=%ROOT_DIR%build\watershed-review\watershed-review-state.json"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$serverScript = [System.IO.Path]::GetFullPath('%ROOT_DIR%scripts\watershed_review_server.py');" ^
  "$existing = Get-CimInstance Win32_Process -Filter \"Name = 'python.exe' OR Name = 'py.exe'\" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like ('*' + $serverScript + '*') };" ^
  "if ($existing) { $existing | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue } ; Start-Sleep -Seconds 1 }" ^
  "$python = Get-Command py -ErrorAction SilentlyContinue;" ^
  "if (-not $python) { $python = Get-Command python -ErrorAction SilentlyContinue }" ^
  "if (-not $python) { throw 'Python introuvable. Installe py ou python pour lancer le serveur local.' }" ^
  "Start-Process -WindowStyle Hidden -WorkingDirectory '%ROOT_DIR%' -FilePath $python.Source -ArgumentList '%ROOT_DIR%scripts\watershed_review_server.py','%PORT%','--review-file','%REVIEW_FILE%','--state-file','%STATE_FILE%' | Out-Null;"

timeout /t 2 /nobreak >nul

if exist "%ProgramFiles%\Mozilla Firefox\firefox.exe" (
  start "" "%ProgramFiles%\Mozilla Firefox\firefox.exe" "%URL%"
  goto :eof
)

if exist "%ProgramFiles(x86)%\Mozilla Firefox\firefox.exe" (
  start "" "%ProgramFiles(x86)%\Mozilla Firefox\firefox.exe" "%URL%"
  goto :eof
)

start "" "%URL%"
