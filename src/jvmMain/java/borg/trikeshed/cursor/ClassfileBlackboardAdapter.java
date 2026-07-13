package borg.trikeshed.cursor;

import jdk.internal.classfile.Classfile;
import jdk.internal.classfile.ClassModel;
import jdk.internal.classfile.FieldModel;
import jdk.internal.classfile.MethodModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Adapter that maps Classfile metadata to a ConfixBlackboard using JDK 21+ Classfile API.
 */
public class ClassfileBlackboardAdapter {
    private final ConfixBlackboard blackboard;

    public ClassfileBlackboardAdapter(ConfixBlackboard blackboard) {
        this.blackboard = blackboard;
    }

    public void attachClass(byte[] classfileBytes) {
        try {
            ClassModel cm = Classfile.parse(classfileBytes);
            String id = cm.thisClass().asInternalName();
            String json = buildJson(cm);
            ClassfileBlackboardAdapterExtKt.attachClassToBlackboard(blackboard, id, json);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void parseAndRegister(Path path, String id) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            ClassModel cm = Classfile.parse(bytes);
            String json = buildJson(cm);
            ClassfileBlackboardAdapterExtKt.attachClassToBlackboard(blackboard, id, json);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String buildJson(ClassModel cm) {
        StringBuilder json = new StringBuilder();
        json.append("{ \"name\": \"").append(cm.thisClass().asInternalName()).append("\", ");
        json.append("\"fields\": [");
        boolean first = true;
        for (FieldModel fm : cm.fields()) {
            if (!first) json.append(", ");
            json.append("\"").append(fm.fieldName().stringValue()).append("\"");
            first = false;
        }
        json.append("], \"methods\": [");
        first = true;
        for (MethodModel mm : cm.methods()) {
            if (!first) json.append(", ");
            json.append("\"").append(mm.methodName().stringValue()).append("\"");
            first = false;
        }
        json.append("] }");
        return json.toString();
    }

    public void flush() {}
}
