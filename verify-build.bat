@echo off
setlocal
cd /d "%~dp0"
where mvn >nul 2>nul
if errorlevel 1 (
  echo ERROR: Maven was not found. Install Maven 3.8+ and ensure mvn is on PATH.
  exit /b 1
)
where java >nul 2>nul
if errorlevel 1 (
  echo ERROR: Java was not found. Install JDK 17 or later and configure PATH.
  exit /b 1
)
echo === Java version ===
java -version
if errorlevel 1 exit /b 1
echo.
echo === Maven version ===
mvn -version
if errorlevel 1 exit /b 1
echo.
echo === Clean test and package verification ===
call mvn -U clean verify
set "RESULT=%ERRORLEVEL%"
if not "%RESULT%"=="0" (
  echo.
  echo BUILD VERIFICATION FAILED. The Maven output above contains the cause.
  echo Build output path: %USERPROFILE%\.policyscript\build\policyscript-compiler
  exit /b %RESULT%
)
echo.
echo BUILD SUCCESS: tests and verify lifecycle completed.
exit /b 0
