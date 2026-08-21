package borg.trikeshed.cursor;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ConstantPool;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;

import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Optional;

/**
 * ClassfileTaxonomy — JEP 484 ClassFile API, for TrikeShed cursor algebra.
 *
 * Uses java.lang.classfile (JDK 25+ public API).
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
    private final List<borg.trikeshed.classfile.model.PointcutCoordinate> pointcuts;

    public ClassfileTaxonomy() {
        this.rows = new ArrayList<>();
        this.pointcuts = new ArrayList<>();
    }

    public List<borg.trikeshed.classfile.model.PointcutCoordinate> pointcuts() {
        return pointcuts;
    }

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

    // ── Factory via public ClassFile API ──────────────────────────────────────

    public static ClassfileTaxonomy open(Path path) throws java.io.IOException {
        return openBytes(java.nio.file.Files.readAllBytes(path));
    }

    public static ClassfileTaxonomy openBytes(byte[] bytes) {
        try {
            return parseClassfile(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse classfile via JEP 484", e);
        }
    }

    /** Parse classfile using JDK 25+ java.lang.classfile API */
    private static ClassfileTaxonomy parseClassfile(byte[] bytes) throws Exception {
        ClassFile classfile = ClassFile.of();
        ClassModel classModel = classfile.parse(bytes);

        ClassfileTaxonomy ct = new ClassfileTaxonomy();

        String sourceFile = classModel.findAttribute(java.lang.classfile.Attributes.sourceFile())
            .map(sfa -> sfa.sourceFile().stringValue())
            .orElse("Unknown");
        String language = sourceFile.endsWith(".kt") ? "kotlin" :
                          sourceFile.endsWith(".java") ? "java" : "unknown";

        // class header
        String thisClass = classModel.thisClass().asInternalName();
        String superClass = classModel.superclass()
            .map(ClassEntry::asInternalName).orElse("");
        ct.addClass(
            thisClass,
            superClass,
            classModel.majorVersion(),
            classModel.minorVersion(),
            classModel.flags().flagsMask(),
            classModel.interfaces().size()
        );

        // fields
        for (FieldModel fm : classModel.fields()) {
            ct.addField(
                fm.fieldName().stringValue(),
                fm.fieldType().stringValue(),
                fm.flags().flagsMask()
            );
        }

        // methods + instructions
        for (MethodModel mm : classModel.methods()) {
            int accessFlags = mm.flags().flagsMask();
            int maxStack = 0;
            int maxLocals = 0;
            int insnCount = 0;

            String methodName = mm.methodName().stringValue();
            String methodDesc = mm.methodType().stringValue();

            Optional<CodeModel> codeOpt = mm.code();
            if (codeOpt.isPresent()) {
                CodeModel code = codeOpt.get();

                // attributes() contains CodeAttribute with maxStack/maxLocals
                for (var attr : code.attributes()) {
                    if (attr instanceof java.lang.classfile.attribute.CodeAttribute ca) {
                        maxStack = ca.maxStack();
                        maxLocals = ca.maxLocals();
                        break;
                    }
                }

                int currentLine = -1;
                int bytecodeOffset = 0;

                // CodeModel is Iterable<CodeElement> via CompoundElement
                for (CodeElement ce : code) {
                    if (ce instanceof java.lang.classfile.instruction.LineNumber ln) {
                        currentLine = ln.line();
                    } else if (ce instanceof Instruction inst) {
                        insnCount++;
                        String owner = "";
                        String name = "";
                        String desc = "";

                        if (inst instanceof FieldInstruction fi) {
                            owner = fi.owner().asInternalName();
                            name = fi.name().stringValue();
                            desc = fi.type().stringValue();
                        } else if (inst instanceof InvokeInstruction ii) {
                            owner = ii.owner().asInternalName();
                            name = ii.name().stringValue();
                            desc = ii.type().stringValue();
                        } else if (inst instanceof java.lang.classfile.instruction.NewObjectInstruction noi) {
                            owner = noi.className().asInternalName();
                        } else if (inst instanceof java.lang.classfile.instruction.TypeCheckInstruction tci) {
                            owner = tci.type().asInternalName();
                        }

                        // Instruction doesn't have position() - skip offset for now
                        int opCode = inst.opcode().bytecode();
                        String mnem = inst.opcode().name();
                        ct.addInstruction(-1, opCode, mnem, owner, name);

                        borg.trikeshed.classfile.model.BytecodePointcutKind kind = switch (inst) {
                            case FieldInstruction fi -> {
                                java.lang.classfile.Opcode op = fi.opcode();
                                yield op == java.lang.classfile.Opcode.GETSTATIC ? borg.trikeshed.classfile.model.BytecodePointcutKind.STATIC_FIELD_READ :
                                      op == java.lang.classfile.Opcode.PUTSTATIC ? borg.trikeshed.classfile.model.BytecodePointcutKind.STATIC_FIELD_WRITE :
                                      op == java.lang.classfile.Opcode.GETFIELD ? borg.trikeshed.classfile.model.BytecodePointcutKind.INSTANCE_FIELD_READ :
                                      borg.trikeshed.classfile.model.BytecodePointcutKind.INSTANCE_FIELD_WRITE;
                            }
                            case InvokeInstruction ii -> borg.trikeshed.classfile.model.BytecodePointcutKind.INVOKE;
                            case java.lang.classfile.instruction.LoadInstruction li -> borg.trikeshed.classfile.model.BytecodePointcutKind.LOCAL_READ;
                            case java.lang.classfile.instruction.StoreInstruction si -> borg.trikeshed.classfile.model.BytecodePointcutKind.LOCAL_WRITE;
                            case java.lang.classfile.instruction.ReturnInstruction ri -> borg.trikeshed.classfile.model.BytecodePointcutKind.RETURN;
                            case java.lang.classfile.instruction.NewObjectInstruction noi -> borg.trikeshed.classfile.model.BytecodePointcutKind.NEW_VALUE;
                            case java.lang.classfile.instruction.TypeCheckInstruction tci -> borg.trikeshed.classfile.model.BytecodePointcutKind.TYPE_CHECK;
                            case java.lang.classfile.instruction.BranchInstruction bi -> borg.trikeshed.classfile.model.BytecodePointcutKind.BRANCH;
                            case java.lang.classfile.instruction.ArrayLoadInstruction ali -> borg.trikeshed.classfile.model.BytecodePointcutKind.ARRAY_READ;
                            case java.lang.classfile.instruction.ArrayStoreInstruction asi -> borg.trikeshed.classfile.model.BytecodePointcutKind.ARRAY_WRITE;
                            case java.lang.classfile.instruction.ConstantInstruction ci -> borg.trikeshed.classfile.model.BytecodePointcutKind.CONSTANT;
                            default -> null;
                        };

                        if (kind != null) {
                            borg.trikeshed.classfile.model.SourceCoordinate srcCoord =
                                new borg.trikeshed.classfile.model.SourceCoordinate(
                                    sourceFile, currentLine, 0, language, bytecodeOffset
                                );
                            borg.trikeshed.classfile.model.SymbolCoordinate symCoord =
                                new borg.trikeshed.classfile.model.SymbolCoordinate(
                                    owner, name, desc, methodName, methodDesc
                                );
                            ct.pointcuts.add(new borg.trikeshed.classfile.model.PointcutCoordinate(
                                kind, mnem, bytecodeOffset, srcCoord, symCoord
                            ));
                        }

                        bytecodeOffset += inst.sizeInBytes();
                    }
                }
            }

            ct.addMethod(
                mm.methodName().stringValue(),
                mm.methodType().stringValue(),
                accessFlags,
                maxStack, maxLocals, insnCount
            );
        }

        // constant pool
        ConstantPool cp = classModel.constantPool();
        int index = 1;
        for (PoolEntry pe : cp) {
            try {
                String tag = pe.getClass().getSimpleName();
                String val = "";
                if (pe instanceof Utf8Entry u8) {
                    val = u8.stringValue();
                } else if (pe instanceof ClassEntry ce) {
                    val = ce.asInternalName();
                } else {
                    val = pe.toString();
                }
                ct.addConstant(index, tag, val);
            } catch (Exception ignored) {}
            index++;
        }

        return ct;
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