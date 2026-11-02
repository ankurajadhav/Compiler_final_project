# V16 JDK / JavaFX build compatibility

V15 pinned JavaFX 21.0.6 and RichTextFX 0.11.2. The user's earlier build screenshots
showed JDK 25. JavaFX 21's supported-JDK table lists JDK 17 and 21; JavaFX 25 is
designed for JDK 25 and requires JDK 23 or later. RichTextFX documents that versions
before 0.11.7 have a JavaFX 25 API clash and 0.11.7 fixes it.

V16 therefore selects matching dependencies with Maven JDK activation profiles:

| Active JDK | JavaFX | RichTextFX |
|---|---:|---:|
| 17–21 | 21.0.6 | 0.11.2 |
| 22 | 24.0.1 | 0.11.2 |
| 23+ (including 25) | 25.0.5 | 0.11.7 |

The project's own Java language/bytecode target stays at Java 17. This lets the
compiler source remain Java 17 while the UI dependency line matches the installed
JDK at runtime.

Additional build updates:
- JUnit Jupiter 5.14.4
- Maven Compiler Plugin 3.16.0
- Maven Resources Plugin 3.5.0
- Maven Jar Plugin 3.5.1 (explicitly pinned instead of relying on Maven's old
  super-POM default)
- Maven Surefire Plugin 3.6.1 with the current in-process classpath workaround
- Maven Clean Plugin 3.4.1
- JavaFX Maven Plugin 0.0.8

Build commands:

```powershell
java -version
mvn -version
mvn -U clean test
mvn -U clean verify
mvn javafx:run
```

The build configuration and dependency coordinates were checked against public
Maven/JavaFX/RichTextFX/JUnit release information. This authoring environment has
no Maven executable, no pre-populated Maven repository, and DNS access to Maven
Central is unavailable. Thus the real Maven dependency resolution and JavaFX build
remain unexecuted here. Do not treat dependency-free checks as a Maven build result.
