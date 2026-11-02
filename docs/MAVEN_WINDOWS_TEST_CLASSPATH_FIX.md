# V7 Maven test-runtime classpath fix

## Observed V6 failure

On a Windows 11 machine running Maven 3.9.15 and JDK 25.0.4, `mvn clean test` reached the JUnit phase but reported `NoClassDefFoundError` for project types that were present under `target/classes`, along with failure to find `/config/roles-resources.json`. The available terminal screenshots showed `Ast.class`, `Conditions$Constant.class`, and the configuration file in the build output, but did not establish the presence of the two time-parser class files or the exact classpath passed to the forked test JVM.

This is consistent with a test-runtime classpath/fork issue, but the screenshots alone do not prove the precise root cause.

## V7 changes

- Upgrade `maven-surefire-plugin` from 3.5.2 to 3.6.1.
- Keep `useModulePath=false` for the existing JavaFX/JUnit classpath build.
- Disable the manifest-only classpath JAR (`useManifestOnlyJar=false`) to avoid an extra manifest-based path-resolution layer on Windows.
- Explicitly retain the system class loader for the forked test JVM.
- Add `BuildClasspathSmokeTest`, which verifies runtime visibility of `Ast`, `Conditions$Constant`, `Optimizer`, `MinuteTimeParser`, `AccessTimeInputParser`, and `/config/roles-resources.json`.

## Required verification on the target Windows machine

From the project root, outside OneDrive, run:

```powershell
mvn -version
mvn clean test
```

The test report must show `BuildClasspathSmokeTest` passing as well as the existing compiler and academic tests. If it still fails, capture the complete output of:

```powershell
Get-ChildItem .\target\classes\com\policyscript\util
Test-Path -LiteralPath '.\target\classes\com\policyscript\ast\Conditions$Constant.class'
Test-Path -LiteralPath '.\target\classes\config\roles-resources.json'
javap -classpath .\target\classes com.policyscript.util.AccessTimeInputParser
javap -classpath .\target\classes 'com.policyscript.ast.Conditions$Constant'
```

Do not report the Maven/JUnit build as verified until `mvn clean test` ends in `BUILD SUCCESS`.
