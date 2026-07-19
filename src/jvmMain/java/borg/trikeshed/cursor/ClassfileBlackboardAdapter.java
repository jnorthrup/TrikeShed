package borg.trikeshed.cursor;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Adapter that maps Classfile metadata to a ConfixBlackboard using the ASM library.
 */
public class ClassfileBlackboardAdapter {
    private static final Logger LOGGER = Logger.getLogger(ClassfileBlackboardAdapter.class.getName());
    private final ConfixBlackboard blackboard;

    public ClassfileBlackboardAdapter(ConfixBlackboard blackboard) {
        this.blackboard = blackboard;
    }

    public void attachClass(byte[] classfileBytes) {
        try {
            ClassReader cr = new ClassReader(classfileBytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            String id = cn.name;
            String json = buildJson(cn);
            ClassfileBlackboardAdapterExtKt.attachClassToBlackboard(blackboard, id, json);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to attach class", e);
        }
    }

    public void parseAndRegister(Path path, String id) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            String json = buildJson(cn);
            ClassfileBlackboardAdapterExtKt.attachClassToBlackboard(blackboard, id, json);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to parse and register class from path: " + path, e);
        }
    }

    private String buildJson(ClassNode cn) {
        StringBuilder json = new StringBuilder();
        json.append("{ \"name\": \"").append(escapeJson(cn.name)).append("\", ");
        json.append("\"fields\": [");
        boolean first = true;
        for (FieldNode fn : cn.fields) {
            if (!first) json.append(", ");
            json.append("\"").append(escapeJson(fn.name)).append("\"");
            first = false;
        }
        json.append("], \"methods\": [");
        first = true;
        for (MethodNode mn : cn.methods) {
            if (!first) json.append(", ");
            json.append("\"").append(escapeJson(mn.name)).append("\"");
            first = false;
        }
        json.append("] }");
        return json.toString();
    }

    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public void flush() {}
}
