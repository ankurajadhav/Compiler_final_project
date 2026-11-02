# V16 build audit — what is and is not verified

V16 resolves the dependency compatibility risk identified for the user's JDK 25:
JavaFX 25 is selected for JDK 23+, and RichTextFX 0.11.7 is paired with it. The
project keeps `maven.compiler.release=17`, so application source/bytecode remains
Java 17 compatible. JDK 17/21 and JDK 22 have separate matching JavaFX profiles.

The previous Surefire `forkCount=0` setting is retained because the user's earlier
screenshots showed project classes/resources present in `target/classes` but not
visible to forked test processes.

## Commands to run on Windows

```powershell
java -version
mvn -version
mvn -U clean test
mvn -U clean verify
mvn javafx:run
```

For a JDK 25 build, the effective JavaFX pair should be JavaFX 25.0.5 and
RichTextFX 0.11.7. The source target remains Java 17.

## Exact limitation

This environment lacks Maven and has no local Maven dependency cache. Network DNS to
Maven Central is unavailable. It is therefore impossible here to execute the real
Maven lifecycle or compile/run JavaFX against downloaded jars. V16 must not be
represented as having a successful official Maven build until the commands above
complete with `BUILD SUCCESS` on the user's machine.
