import com.sun.source.util.JavacTask;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Parse Java files without Android SDK. Only checks grammar, NOT Android dependencies or build. */
public class JavaSyntaxCheck {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK compiler required");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<File> files = new ArrayList<>();
        for (String arg : args) files.add(new File(arg));
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjectsFromFiles(files);
            JavacTask task = (JavacTask)compiler.getTask(null, manager, diagnostics,
                    Arrays.asList("-proc:none", "-source", "17", "-target", "17"), null, units);
            task.parse();
        }
        boolean hasErrors = false;
        for (Diagnostic<? extends JavaFileObject> item : diagnostics.getDiagnostics()) {
            if (item.getKind() == Diagnostic.Kind.ERROR) {
                System.err.println(item.toString());
                hasErrors = true;
            }
        }
        if (hasErrors) System.exit(1);
        System.out.println("PASS: Java syntax parsed for " + files.size() + " files (no Android SDK)");
    }
}
