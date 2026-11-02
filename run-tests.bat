@echo off
setlocal
cd /d "%~dp0"
where mvn >nul 2>nul
if errorlevel 1 (
  echo ERROR: Maven was not found. Install Maven 3.8+ and ensure mvn is on PATH.
  exit /b 1
)
echo Running PolicyScript JUnit tests. Build output is stored outside this project folder.
call mvn -U clean test
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" (
  echo.
  echo BUILD FAILED. Review the Maven error above. Do not ignore failing tests.
)
exit /b %RESULT%
