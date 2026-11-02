# V12 build-stability changes

This release addresses the two build problems shown in the supplied Windows screenshots.

## 1. Windows clean failure caused by a project-local `target` directory

The screenshots showed Maven Clean Plugin failing to delete `target/test-classes/com/policyscript` while the project was opened from a OneDrive-synchronized folder. V12 writes Maven output to:

```text
%USERPROFILE%\.policyscript\build\policyscript-compiler
```

This path is outside the project/OneDrive tree and is separate from the persistent academic data file:

```text
%USERPROFILE%\.policyscript\academic-data.psdb
```

Maven's `${project.build.directory}` is used for `classes`, `test-classes`, reports, and the JAR, so `mvn clean` no longer needs to delete the old project-local `target` tree. Any old `target` folder left by V8/V9/V10/V11 is ignored by the V12 Maven model.

## 2. Surefire test-runtime classpath

The earlier screenshots showed `NoClassDefFoundError` for classes that were present under `target/classes`, plus an unavailable classpath resource. V12 configures Surefire with:

```xml
<useModulePath>false</useModulePath>
<forkCount>0</forkCount>
<failIfNoTests>true</failIfNoTests>
```

Running the tests in Maven's own VM avoids the forked JVM's manifest-only classpath mechanism on the affected Windows/JDK 25 setup. This is a classpath isolation workaround, not a suppression of test failures: JUnit assertions still run and any failure still fails the build.

## 3. Pinned build plugins

V12 pins Maven Clean Plugin 3.4.1 and Resources Plugin 3.3.1, in addition to the existing Compiler Plugin and Surefire Plugin.

## 4. Recommended Windows commands

Close any running PolicyScript desktop instance before building. From the folder that contains `pom.xml`, run:

```powershell
mvn -U clean test
```

For the full lifecycle, use:

```powershell
mvn -U clean verify
```

Or double-click `verify-build.bat`.

The build output will be in `%USERPROFILE%\.policyscript\build\policyscript-compiler`, not in the project folder. The application data remains in `%USERPROFILE%\.policyscript\academic-data.psdb`.

## 5. Verification boundary

The authoring environment did not have Maven installed and DNS resolution for Maven Central was unavailable, so it was not possible to execute the actual Maven lifecycle here. The dependency-free compiler/academic verification script, all 88 JUnit test methods through a local JUnit-compatible harness, Java 17 source syntax parsing, and POM XML validation were run locally. These checks do not replace a successful `mvn clean verify` on the target Windows installation.
