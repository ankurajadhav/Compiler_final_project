@echo off
setlocal
if "%~1"=="" (
  mvn exec:java -Dexec.mainClass=com.policyscript.cli.PolicyScriptCli
) else (
  mvn exec:java -Dexec.mainClass=com.policyscript.cli.PolicyScriptCli -Dexec.args="%*"
)
endlocal
