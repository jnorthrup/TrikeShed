package borg.trikeshed.cursor;

import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

/**
 * ClassfileTaxonomy — JEP 484 ClassFile API via reflection, for TrikeShed cursor algebra.
 *
 * Uses reflection to access jdk.internal.classfile.* classes, which are
 * accessible but not directly importable on Corretto JDK 21.
 *
 * Column layout per element type:
 *   CLASS       → [thisClass, superClass, majorVersion, minorVersion, accessFlags, interfaceCount]
 *   FIELD       → [name, descriptor, accessFlags]
 *   METHOD      → [name, descriptor, accessFlags, maxStack, maxLocals, instructionCount]
 *   INSTRUCTION → [offset, opcode, mnemonic, owner, name]
 *   CONSTANT    → [index, tag, value]
 */
public class ClassfileTaxonomy {

    /** Element type discriminant */
    public enum Kind { CLASS, FIELD, METHOD, INSTRUCTION, CONSTANT }

    /** One row of the classfile taxonomy */
    public static final class Row {
        public final Kind kind;
        public final Object[] cols;
        public Row(Kind kind, Object[] cols) { this.kind = kind; this.cols = cols; }
        public Object get(int i) { return i < cols.length ? cols[i] : null; }
        public int length() { return cols.length; }
    }

    private final List<Row> rows;

    public ClassfileTaxonomy() { this.rows = new ArrayList<>(); }

    public void addClass(String thisClass, String superClass,
                         int majorVersion, int minorVersion,
                         int accessFlags, int interfaceCount) {
        rows.add(new Row(Kind.CLASS, new Object[] {
            thisClass, superClass, majorVersion, minorVersion, accessFlags, interfaceCount
        }));
    }

    public void addField(String name, String descriptor, int accessFlags) {
        rows.add(new Row(Kind.FIELD, new Object[] { name, descriptor, accessFlags }));
    }

    public void addMethod(String name, String descriptor, int accessFlags,
                          int maxStack, int maxLocals, int instructionCount) {
        rows.add(new Row(Kind.METHOD, new Object[] {
            name, descriptor, accessFlags, maxStack, maxLocals, instructionCount
        }));
    }

    public void addInstruction(int offset, int opcode, String mnemonic, String owner, String name) {
        rows.add(new Row(Kind.INSTRUCTION, new Object[] { offset, opcode, mnemonic, owner, name }));
    }

    public void addConstant(int index, String tag, String value) {
        rows.add(new Row(Kind.CONSTANT, new Object[] { index, tag, value }));
    }

    public int size() { return rows.size(); }
    public Row rowAt(int i) { return rows.get(i); }
    public Kind kindAt(int i) { return rows.get(i).kind; }

    public Map<String, Integer> instructionHistogram() {
        Map<String, Integer> hist = new HashMap<>();
        for (Row r : rows) {
            if (r.kind != Kind.INSTRUCTION) continue;
            String mnemonic = String.valueOf(r.cols[2]);
            hist.merge(mnemonic, 1, Integer::sum);
        }
        return hist;
    }

    public Map<String, Integer> invokeSummary() {
        Map<String, Integer> hist = new HashMap<>();
        Set<String> invokeSet = Set.of("invokevirtual", "invokestatic", "invokespecial", "invokeinterface");
        for (Row r : rows) {
            if (r.kind != Kind.INSTRUCTION) continue;
            String mnemonic = String.valueOf(r.cols[2]);
            if (!invokeSet.contains(mnemonic)) continue;
            String owner = String.valueOf(r.cols[3]);
            String name = String.valueOf(r.cols[4]);
            hist.merge(owner + "." + name, 1, Integer::sum);
        }
        return hist;
    }

    public List<Row> methods() {
        return rows.stream().filter(r -> r.kind == Kind.METHOD).toList();
    }

    public List<Row> instructions() {
        return rows.stream().filter(r -> r.kind == Kind.INSTRUCTION).toList();
    }

    // ── Factory via reflection ────────────────────────────────────────────

    public static ClassfileTaxonomy open(Path path) throws java.io.IOException {
        return openBytes(java.nio.file.Files.readAllBytes(path));
    }

    public static ClassfileTaxonomy openBytes(byte[] bytes) {
        try {
            return parseClassfileReflection(bytes);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to parse classfile via JEP 484", e);
        }
    }

    /** Reflective invocation of jdk.internal.classfile.Classfile.parse(byte[]) */
    private static ClassfileTaxonomy parseClassfileReflection(byte[] bytes)
            throws ReflectiveOperationException {

        Class<?> classfileClass = Class.forName("jdk.internal.classfile.Classfile");
        Object classModel = classfileClass.getMethod("parse", byte[].class).invoke(null, bytes);

        Class<?> classModelClass = classModel.getClass();
        Class<?> fieldModelClass = Class.forName("jdk.internal.classfile.FieldModel");
        Class<?> methodModelClass = Class.forName("jdk.internal.classfile.MethodModel");
        Class<?> codeModelClass = Class.forName("jdk.internal.classfile.CodeModel");
        Class<?> codeElementClass = Class.forName("jdk.internal.classfile.CodeElement");
        Class<?> instructionClass = Class.forName("jdk.internal.classfile.instruction.Instruction");
        Class<?> fieldInstructionClass = Class.forName("jdk.internal.classfile.instruction.FieldInstruction");
        Class<?> invokeInstructionClass = Class.forName("jdk.internal.classfile.instruction.InvokeInstruction");
        Class<?> constantPoolClass = Class.forName("jdk.internal.classfile.ConstantPool");

        ClassfileTaxonomy ct = new ClassfileTaxonomy();

        // class header
        Object thisClass = invoke(classModelClass, "thisClass", classModel);
        Object superClassOpt = invoke(classModelClass, "superclass", classModel);
        String superClass = String.valueOf(invokeOpt("asInternalName", superClassOpt, ""));
        ct.addClass(
            String.valueOf(invoke(classModelClass, "thisClass", classModel)),
            superClass,
            ((Number) invoke(classModelClass, "majorVersion", classModel)).intValue(),
            ((Number) invoke(classModelClass, "minorVersion", classModel)).intValue(),
            ((Number) invoke(classModelClass, "accessFlags", classModel,
                "get", classModelClass)).intValue(),
            ((Number) invoke(classModelClass, "interfaces", classModel)).intValue()
        );

        // fields
        Object fields = invoke(classModelClass, "fields", classModel);
        for (Object fm : (Iterable<?>) fields) {
            ct.addField(
                String.valueOf(invoke(fieldModelClass, "fieldName", fm, "stringValue")),
                String.valueOf(invoke(fieldModelClass, "descriptor", fm, "stringValue")),
                ((Number) invoke(fieldModelClass, "flags", fm, "get", int.class)).intValue()
            );
        }

        // methods + instructions
        Object methods = invoke(classModelClass, "methods", classModel);
        for (Object mm : (Iterable<?>) methods) {
            final int[] insnCount = {0};
            Object codeOpt = invokeOpt("ifPresent", mm, (java.util.function.Consumer<?>) c -> {
                try {
                    Object code = null;
                    for (Object ce : (Iterable<?>) invoke(codeModelClass, "elementList", c)) {
                        if (instructionClass.isInstance(ce)) {
                            insnCount[0]++;
                            String owner = "";
                            String name = "";
                            if (fieldInstructionClass.isInstance(ce)) {
                                owner = String.valueOf(invoke(fieldInstructionClass, "owner", ce, "asInternalName"));
                            } else if (invokeInstructionClass.isInstance(ce)) {
                                owner = String.valueOf(invoke(invokeInstructionClass, "owner", ce, "asInternalName"));
                                name = String.valueOf(invoke(invokeInstructionClass, "methodName", ce, "stringValue"));
                            }
                            int pos = ((Number) invoke(instructionClass, "position", ce)).intValue();
                            Object opcode = invoke(instructionClass, "opcode", ce);
                            int opCode = ((Number) opcode.getClass().getMethod("bytecode").invoke(opcode)).intValue();
                            String mnem = String.valueOf(opcode.getClass().getMethod("name").invoke(opcode));
                            ct.addInstruction(pos, opCode, mnem, owner, name);
                        }
                    }
                } catch (ReflectiveOperationException ex) {
                    throw new RuntimeException(ex);
                }
            });
            if (codeOpt != null) {
                // already processed in the lambda
            }
            Object codeOpt2 = invokeOpt("ifPresent", mm, (java.util.function.Consumer<?>) c -> {
                // just count
            });
            int maxStack = ((Number) invokeOpt("map", mm, (java.util.function.Function<?, ?>) o -> {
                try { return invoke(codeModelClass, "maxStack", o); }
                catch (Exception e) { return 0; }
            }, 0)).intValue();
            int maxLocals = ((Number) invokeOpt("map", mm, (java.util.function.Function<?, ?>) o -> {
                try { return invoke(codeModelClass, "maxLocals", o); }
                catch (Exception e) { return 0; }
            }, 0)).intValue();
            int accessFlags = ((Number) invoke(methodModelClass, "flags", mm, "get", int.class)).intValue();

            ct.addMethod(
                String.valueOf(invoke(methodModelClass, "methodName", mm, "stringValue")),
                String.valueOf(invoke(methodModelClass, "methodType", mm, "stringValue")),
                accessFlags,
                maxStack, maxLocals, insnCount[0]
            );
        }

        // constant pool
        Object cp = invoke(classModelClass, "constantPool", classModel);
        for (Object cpe : (Iterable<?>) cp) {
            try {
                int idx = ((Number) cpe.getClass().getMethod("index").invoke(cpe)).intValue();
                String tag = String.valueOf(cpe.getClass().getMethod("tag").invoke(cpe,
                    cpe.getClass().getMethod("name").invoke(cpe)));
                String val = String.valueOf(cpe.getClass().getMethod("stringValue").invoke(cpe));
                ct.addConstant(idx, tag, val);
            } catch (Exception ignored) {}
        }

        return ct;
    }

    // ── Reflection helpers ─────────────────────────────────────────────────

    private static Object invoke(Class<?> cls, String method, Object target) throws ReflectiveOperationException {
        return cls.getMethod(method).invoke(target);
    }

    private static Object invoke(Class<?> cls, String method, Object target, String helper) throws ReflectiveOperationException {
        for (var m : cls.getMethods()) {
            if (m.getName().equals(method)) {
                for (var p : m.getParameters()) {
                    if (p.getName().equals(helper)) {
                        return m.invoke(target);
                    }
                }
            }
        }
        return cls.getMethod(method).invoke(target);
    }

    private static Object invoke(Class<?> cls, String method, Object target, String helper, Class<?> retType) throws ReflectiveOperationException {
        for (var m : cls.getMethods()) {
            if (m.getName().equals(method)) {
                for (var p : m.getParameters()) {
                    if (p.getName().equals(helper)) {
                        return m.invoke(target);
                    }
                }
            }
        }
        return cls.getMethod(method).invoke(target);
    }

    private static Object invokeOpt(String method, Object target, Object arg, int defaultVal) {
        try {
            return invokeOpt(method, target, arg);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    private static Object invokeOpt(String method, Object target, Object arg) {
        try {
            java.lang.reflect.Method m = null;
            for (var meth : target.getClass().getMethods()) {
                if (meth.getName().equals(method)) {
                    m = meth;
                    break;
                }
            }
            if (m == null) return null;
            if (arg instanceof java.util.function.Consumer) {
                m.invoke(target, arg);
                return null;
            }
            if (arg instanceof java.util.function.Function) {
                return m.invoke(target, arg);
            }
            return m.invoke(target, arg);
        } catch (Exception e) {
            return null;
        }
    }

    private static String invokeOpt(String method, Object target, String defaultVal) {
        try {
            if (target == null) return defaultVal;
            var m = target.getClass().getMethod(method);
            return String.valueOf(m.invoke(target));
        } catch (Exception e) {
            return defaultVal;
        }
    }

    public static List<ClassfileTaxonomy> openTree(Path root) {
        List<ClassfileTaxonomy> results = new ArrayList<>();
        try {
            java.nio.file.Files.walk(root).forEach(p -> {
                if (p.toString().endsWith(".class")) {
                    try { results.add(open(p)); }
                    catch (Exception ignored) {}
                }
            });
        } catch (java.io.IOException ignored) {}
        return results;
    }
}
