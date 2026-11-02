$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $ProjectRoot
$BuildDir = Join-Path $env:TEMP 'PolicyScript-Core-Verification'
if (Test-Path $BuildDir) { Remove-Item -Recurse -Force $BuildDir }
New-Item -ItemType Directory -Path $BuildDir | Out-Null
$Sources = Get-ChildItem -Path 'src/main/java' -Recurse -Filter '*.java' | Where-Object { $_.Name -ne 'PolicyScriptApp.java' -and $_.Name -ne 'AcademicManagementView.java' } | ForEach-Object { $_.FullName}
$ArgFile = Join-Path $BuildDir 'sources.txt'
$Sources | ForEach-Object { '"' + $_.Replace('"', '\"') + '"' } | Set-Content -Encoding ascii $ArgFile
& javac --release 17 -d $BuildDir "@$ArgFile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac --release 17 -cp $BuildDir -d $BuildDir 'tools/CoreVerification.java'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$BuildDir;src/main/resources" CoreVerification
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac --release 17 -cp $BuildDir -d $BuildDir 'tools/AcademicVerification.java'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$BuildDir;src/main/resources" AcademicVerification
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& javac --release 17 -cp $BuildDir -d $BuildDir 'tools/AcademicPriorityMatrixVerification.java'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$BuildDir;src/main/resources" AcademicPriorityMatrixVerification
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& javac --release 17 -cp $BuildDir -d $BuildDir 'tools/PriorityConflictDiagnosticsVerification.java'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$BuildDir;src/main/resources" PriorityConflictDiagnosticsVerification
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& javac --release 17 -cp $BuildDir -d $BuildDir 'tools/ChoiceVerification.java'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $BuildDir ChoiceVerification
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac --release 17 -cp $BuildDir -d $BuildDir 'tools/UiIntegrationVerification.java'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $BuildDir UiIntegrationVerification
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac --release 17 -cp $BuildDir -d $BuildDir 'tools/WorkspaceManagementVerification.java'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$BuildDir;src/main/resources" WorkspaceManagementVerification
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac --release 17 -cp $BuildDir -d $BuildDir 'tools/DeletionIntegrityVerification.java'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp "$BuildDir;src/main/resources" DeletionIntegrityVerification
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& javac --release 17 -d $BuildDir 'tools/SourceSyntaxVerification.java'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $BuildDir SourceSyntaxVerification
exit $LASTEXITCODE
