package borg.trikeshed.couch.wal;

import borg.trikeshed.cursor.ConfixBlackboard;
import borg.trikeshed.cursor.ClassfileBlackboardAdapter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.List;

public class CouchWal {
    private final ConfixBlackboard blackboard;
    private final ClassfileBlackboardAdapter adapter;
    private final String projectRoot;

    public CouchWal(String projectRoot) {
        this.projectRoot = projectRoot;
        this.blackboard = new ConfixBlackboard();
        this.adapter = new ClassfileBlackboardAdapter(this.blackboard);
    }

    public void runGradleBuild() throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder();
        builder.directory(new File(projectRoot));

        // Use the gradle wrapper from the project root
        String gradlewCommand = "./gradlew";
        if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
            gradlewCommand = "gradlew.bat";
        }

        builder.command(gradlewCommand, "compileJvmMainJava", "compileKotlinJvm", "jvmJar");
        builder.redirectErrorStream(true);

        Process process = builder.start();

        // Log output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Gradle build failed with exit code: " + exitCode);
        }
    }

    public void parseBuiltClasses() throws IOException {
        List<Path> classesDirs = new ArrayList<>();

        Path kotlinClassesDir = Paths.get(projectRoot, "build", "classes", "kotlin", "jvm", "main");
        if (Files.exists(kotlinClassesDir)) {
            classesDirs.add(kotlinClassesDir);
        }

        Path javaClassesDir = Paths.get(projectRoot, "build", "classes", "java", "jvmMain");
        if (Files.exists(javaClassesDir)) {
            classesDirs.add(javaClassesDir);
        }

        if (classesDirs.isEmpty()) {
            System.err.println("Warning: no classes directory found in " + projectRoot + "/build/classes/");
            return;
        }

        for (Path classesDir : classesDirs) {
            try (Stream<Path> paths = Files.walk(classesDir)) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> p.toString().endsWith(".class"))
                     .forEach(p -> {
                         String id = classesDir.relativize(p).toString();
                         adapter.parseAndRegister(p, id);
                     });
            }
        }
    }

    public ConfixBlackboard getBlackboard() {
        return blackboard;
    }

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : ".";
        CouchWal wal = new CouchWal(root);
        System.out.println("Running gradle build wrapper...");
        wal.runGradleBuild();
        System.out.println("Parsing compiled classes...");
        wal.parseBuiltClasses();
        System.out.println("Done. Registered classes: " + wal.getBlackboard().ids().size());
    }
}
