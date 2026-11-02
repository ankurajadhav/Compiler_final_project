#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Keep generated verification classes out of OneDrive/project folders.
BUILD_DIR="${TMPDIR:-/tmp}/policyscript-core-verification"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
find src/main/java -name '*.java' ! -name 'PolicyScriptApp.java' ! -name 'AcademicManagementView.java' -print > "$BUILD_DIR/sources.txt"
javac --release 17 -d "$BUILD_DIR" @"$BUILD_DIR/sources.txt"
javac --release 17 -cp "$BUILD_DIR" -d "$BUILD_DIR" tools/CoreVerification.java
java -cp "$BUILD_DIR:src/main/resources" CoreVerification
javac --release 17 -cp "$BUILD_DIR" -d "$BUILD_DIR" tools/AcademicVerification.java
java -cp "$BUILD_DIR:src/main/resources" AcademicVerification
javac --release 17 -cp "$BUILD_DIR" -d "$BUILD_DIR" tools/AcademicPriorityMatrixVerification.java
java -cp "$BUILD_DIR:src/main/resources" AcademicPriorityMatrixVerification
javac --release 17 -cp "$BUILD_DIR" -d "$BUILD_DIR" tools/PriorityConflictDiagnosticsVerification.java
java -cp "$BUILD_DIR:src/main/resources" PriorityConflictDiagnosticsVerification
javac --release 17 -cp "$BUILD_DIR" -d "$BUILD_DIR" tools/ChoiceVerification.java
java -cp "$BUILD_DIR" ChoiceVerification
javac --release 17 -cp "$BUILD_DIR" -d "$BUILD_DIR" tools/UiIntegrationVerification.java
java -cp "$BUILD_DIR" UiIntegrationVerification
javac --release 17 -cp "$BUILD_DIR" -d "$BUILD_DIR" tools/WorkspaceManagementVerification.java
java -cp "$BUILD_DIR:src/main/resources" WorkspaceManagementVerification
javac --release 17 -cp "$BUILD_DIR" -d "$BUILD_DIR" tools/DeletionIntegrityVerification.java
java -cp "$BUILD_DIR:src/main/resources" DeletionIntegrityVerification
javac --release 17 -d "$BUILD_DIR" tools/SourceSyntaxVerification.java
java -cp "$BUILD_DIR" SourceSyntaxVerification
bash tools/verify-cli-priority.sh "$BUILD_DIR"
