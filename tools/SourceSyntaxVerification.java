import com.sun.source.util.JavacTask;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Parses production, JUnit and verification Java sources without dependency resolution; not a substitute for compilation. */
public final class SourceSyntaxVerification {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("A full JDK is required.");
        List<Path> sources = new ArrayList<>();
        for (String root : List.of("src/main/java", "src/test/java", "tools")) {
            try (var stream = Files.walk(Path.of(root))) {
                sources.addAll(stream.filter(p -> p.toString().endsWith(".java")).toList());
            }
        }
        sources.sort(Path::compareTo);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "17"), null, units);
            task.parse();
        }
        List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
        for (Diagnostic<?> error : errors) System.err.println(error);
        System.out.println("Java syntax (production + JUnit + tools): " + sources.size() + " files parsed; " + errors.size() + " syntax error(s).");
        if (!errors.isEmpty()) System.exit(1);
    }
}
