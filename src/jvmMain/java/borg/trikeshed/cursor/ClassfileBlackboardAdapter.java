package borg.trikeshed.cursor;

/**
 * Placeholder stub — jdk.internal.classfile requires JVM 25+ and --add-exports.
 * TODO: reimplement with a stable classfile parsing approach (ASM or jclasslib).
 */
public class ClassfileBlackboardAdapter {
    public ClassfileBlackboardAdapter(ConfixBlackboard blackboard) {}

    public void attachClass(byte[] classfileBytes) {}

    public void parseAndRegister(java.nio.file.Path path, String id) {}

    public void flush() {}
}
