package borg.trikeshed.cursor;

import jdk.internal.classfile.Classfile;
import jdk.internal.classfile.ClassModel;
import java.nio.file.Path;
import java.io.IOException;

/**
 * Adapter integrating jdk.internal.classfile.Classfile API with ConfixBlackboard.
 * Written in Java to easily consume the jdk.internal.classfile module with standard compiler args.
 */
public class ClassfileBlackboardAdapter {
    private final ConfixBlackboard blackboard;

    public ClassfileBlackboardAdapter(ConfixBlackboard blackboard) {
        this.blackboard = blackboard;
    }

    /**
     * Parses a .class file and maps its structural metadata into the blackboard.
     */
    public void parseAndRegister(Path classFilePath, String id) throws IOException {
        // Read the class model using the internal Classfile API (static parse method in early JDK 21 preview)
        ClassModel classModel = Classfile.parse(classFilePath);

        // Convert the ClassModel into a JSON representation string
        String jsonRepr = serializeClassModelToJson(classModel);

        // Parse the JSON into a ConfixDoc using the Kotlin global function
        Object doc = borg.trikeshed.parse.confix.ConfixKitKt.confixDoc(jsonRepr);

        // Register it in the blackboard
        blackboard.put(id, new borg.trikeshed.parse.confix.BlackBoardEntry(
                (borg.trikeshed.lib.Join) doc,
                borg.trikeshed.parse.confix.ConfixRole.OBSERVATION,
                0L,
                ""
        ));
    }

    private String serializeClassModelToJson(ClassModel model) {
        String className = model.thisClass().asInternalName();
        int majorVersion = model.majorVersion();
        int minorVersion = model.minorVersion();

        return "{\n" +
               "    \"className\": \"" + className + "\",\n" +
               "    \"majorVersion\": " + majorVersion + ",\n" +
               "    \"minorVersion\": " + minorVersion + "\n" +
               "}";
    }
}
