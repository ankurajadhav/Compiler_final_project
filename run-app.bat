@echo off
setlocal
where mvn >nul 2>nul
if errorlevel 1 (
  echo Maven was not found. Install Maven 3.8+ and ensure mvn is on PATH.
  pause
  exit /b 1
)
mvn javafx:run
endlocal
