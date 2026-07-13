package borg.trikeshed.cursor;

import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Adapter bridging Java Classfile API with ConfixBlackboard.
 * Uses ClassfileTaxonomy to reflectively parse classes on JVM 21+.
 */
public class ClassfileBlackboardAdapter {
    private final ConfixBlackboard blackboard;

    public ClassfileBlackboardAdapter(ConfixBlackboard blackboard) {
        this.blackboard = blackboard;
    }

    public void attachClass(byte[] classfileBytes) {
        attachClass(classfileBytes, "anonymous-" + System.identityHashCode(classfileBytes));
    }

    private void attachClass(byte[] classfileBytes, String id) {
        try {
            ClassfileTaxonomy taxonomy = ClassfileTaxonomy.openBytes(classfileBytes);
            String json = buildJson(taxonomy);
            ClassfileBlackboardAdapterExtKt.attachClassToBlackboard(blackboard, id, json);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void parseAndRegister(Path path, String id) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(path);
            attachClass(bytes, id);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void flush() {}

    private String buildJson(ClassfileTaxonomy taxonomy) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        sb.append("\"classes\": [");
        sb.append(taxonomy.classes().stream().map(c -> {
            return String.format("{\"name\":\"%s\", \"superName\":\"%s\", \"major\":%d, \"minor\":%d, \"flags\":%d, \"interfaces\":%d}",
                escapeJson(c.name), escapeJson(c.desc), c.majorVersion, c.minorVersion, c.accessFlags, c.interfacesCount);
        }).collect(Collectors.joining(",")));
        sb.append("],");

        sb.append("\"fields\": [");
        sb.append(taxonomy.fields().stream().map(f -> {
            return String.format("{\"name\":\"%s\", \"desc\":\"%s\", \"flags\":%d}",
                escapeJson(f.name), escapeJson(f.desc), f.accessFlags);
        }).collect(Collectors.joining(",")));
        sb.append("],");

        sb.append("\"methods\": [");
        sb.append(taxonomy.methods().stream().map(m -> {
            return String.format("{\"name\":\"%s\", \"desc\":\"%s\", \"flags\":%d, \"maxStack\":%d, \"maxLocals\":%d, \"insnCount\":%d}",
                escapeJson(m.name), escapeJson(m.desc), m.accessFlags, m.maxStack, m.maxLocals, m.insnCount);
        }).collect(Collectors.joining(",")));
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
