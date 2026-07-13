package org.xvm.runtime;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Opcode;
import java.lang.classfile.CodeModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashSet;

/**
 * JVM bytecode rewriter using java.lang.classfile — pure JDK, no Javassist.
 *
 * Two modes per site:
 *   PUBLISH — inject VmPointcutPublisher.publish() before/after the instruction
 *   REPLACE — remove new+dup+invokespecial sequence, emit invokestatic bridge instead
 *
 * Pointcut kinds and their JVM bytecode patterns:
 *   CALL  (0x10-0x1F) → invokestatic         → BEFORE
 *   NVOK  (0x20-0x2F) → invokevirtual/iface  → BEFORE
 *   CONSTR(0x34-0x37) → invokespecial <init>  → AFTER
 *   NEW   (0x38-0x3B) → new + invokespecial   → BEFORE  or  REPLACE
 *   RETURN(0x4C-0x4F) → areturn/ireturn/etc.  → BEFORE
 *   L_GET (0xA5)      → getfield              → BEFORE
 *   L_SET (0xA6)      → putfield              → BEFORE
 *   P_GET (0xA7)      → getstatic             → BEFORE
 *   P_SET (0xA8)      → putstatic             → BEFORE
 */
public final class ClassfilePointcutRewriter {

    private static final ClassDesc PUBLISHER_CD = ClassDesc.of("org.xvm.runtime.VmPointcutPublisher");
    private static final MethodTypeDesc PUBLISH_DESC = MethodTypeDesc.of(
            ClassDesc.of("void"),
            ClassDesc.of("int"), ClassDesc.of("java.lang.String"), ClassDesc.of("int")
    );

    /** A pointcut site — either PUBLISH (inject) or REPLACE (rewrite allocation). */
    public record PointcutSite(
            int opcode,
            String method,
            int addr,
            String desc,
            boolean replace,
            String bridgeOwner,
            String bridgeName,
            MethodTypeDesc bridgeDesc
    ) {
        /** PUBLISH-mode site — injects publish() at the matched instruction. */
        public PointcutSite(int opcode, String method, int addr, String desc) {
            this(opcode, method, addr, desc, false, null, null, null);
        }

        /** REPLACE-mode site — replaces new+dup+init with invokestatic bridge. */
        public static PointcutSite replace(
                int opcode, String method, int addr, String desc,
                String bridgeOwner, String bridgeName, MethodTypeDesc bridgeDesc
        ) {
            return new PointcutSite(opcode, method, addr, desc, true, bridgeOwner, bridgeName, bridgeDesc);
        }
    }

    /** Tracks a replacement range: element indices [newIdx..initIdx] to skip. */
    record ReplaceRange(int newIdx, int initIdx, PointcutSite site) {}

    /**
     * PUBLISH-only rewrite — injects publish() at each pointcut site.
     */
    public static byte[] rewrite(byte[] classBytes, List<PointcutSite> sites) {
        if (sites.isEmpty()) return classBytes;

        ClassModel cm = ClassFile.of().parse(classBytes);
        Map<String, List<PointcutSite>> sitesByMethod = indexByMethod(sites);
        final String[] currentMethod = new String[1];

        return ClassFile.of().transformClass(cm, ClassTransform.transformingMethods(
            mm -> {
                currentMethod[0] = mm.methodName().stringValue();
                return sitesByMethod.containsKey(currentMethod[0]);
            },
            MethodTransform.transformingCode(
                (codeBuilder, codeElement) -> {
                    List<PointcutSite> methodSites = sitesByMethod.getOrDefault(currentMethod[0], List.of());
                    boolean isAfterConstr = codeElement instanceof InvokeInstruction inv
                            && inv.opcode() == Opcode.INVOKESPECIAL
                            && inv.name().stringValue().equals("<init>");

                    if (!isAfterConstr) {
                        for (var site : methodSites) {
                            if (!site.replace() && matchesElement(codeElement, site)) {
                                emitPublish(codeBuilder, site);
                            }
                        }
                    }
                    codeBuilder.accept(codeElement);
                    if (isAfterConstr) {
                        for (var site : methodSites) {
                            if (!site.replace() && matchesElement(codeElement, site)) {
                                emitPublish(codeBuilder, site);
                            }
                        }
                    }
                }
            )
        ));
    }

    /**
     * REPLACE-aware rewrite — replaces new+dup+init with invokestatic bridge
     * for REPLACE sites, and injects publish() for PUBLISH sites.
     */
    public static byte[] rewriteReplace(byte[] classBytes, List<PointcutSite> sites) {
        if (sites.isEmpty()) return classBytes;

        ClassModel cm = ClassFile.of().parse(classBytes);
        Map<String, List<PointcutSite>> sitesByMethod = indexByMethod(sites);
        final String[] currentMethod = new String[1];
        final java.lang.classfile.MethodModel[] currentMM = new java.lang.classfile.MethodModel[1];

        return ClassFile.of().transformClass(cm, ClassTransform.transformingMethods(
            mm -> {
                currentMethod[0] = mm.methodName().stringValue();
                currentMM[0] = mm;
                if (!sitesByMethod.containsKey(currentMethod[0])) return false;

                // Pre-scan code model for REPLACE ranges
                if (mm.code().isPresent()) {
                    var codeElements = mm.code().get().elementList();
                    var ranges = findReplaceRanges(codeElements, sitesByMethod.getOrDefault(currentMethod[0], List.of()));

                    // Store ranges in a thread-local side channel for the code transform
                    replaceRanges.set(ranges);
                    replaceIndex.set(new java.util.concurrent.atomic.AtomicInteger(0));
                }
                return true;
            },
            MethodTransform.transformingCode(
                (codeBuilder, codeElement) -> {
                    List<PointcutSite> methodSites = sitesByMethod.getOrDefault(currentMethod[0], List.of());
                    List<ReplaceRange> ranges = replaceRanges.get();
                    int idx = replaceIndex.get().getAndIncrement();

                    // Check if this element is in a replacement range
                    for (var range : ranges) {
                        if (idx == range.newIdx()) {
                            // Emit bridge call instead of new+dup+init
                            var codeElems = currentMM[0].code().get().elementList();
                            // Replay ctor args (elements between dup and invokespecial)
                            for (int j = range.newIdx() + 2; j < range.initIdx(); j++) {
                                codeBuilder.accept(codeElems.get(j));
                            }
                            codeBuilder.invokestatic(
                                    ClassDesc.ofInternalName(range.site().bridgeOwner()),
                                    range.site().bridgeName(),
                                    range.site().bridgeDesc()
                            );
                            return; // skip emitting the new instruction
                        }
                        if (idx > range.newIdx() && idx <= range.initIdx()) {
                            // Skip dup, args, invokespecial — don't emit
                            return;
                        }
                    }

                    // Normal PUBLISH injection
                    boolean isConstrInit = codeElement instanceof InvokeInstruction inv
                            && inv.opcode() == Opcode.INVOKESPECIAL
                            && inv.name().stringValue().equals("<init>");

                    if (!isConstrInit) {
                        for (var site : methodSites) {
                            if (!site.replace() && matchesElement(codeElement, site)) {
                                emitPublish(codeBuilder, site);
                            }
                        }
                    }
                    codeBuilder.accept(codeElement);
                    if (isConstrInit) {
                        for (var site : methodSites) {
                            if (!site.replace() && matchesElement(codeElement, site)) {
                                emitPublish(codeBuilder, site);
                            }
                        }
                    }
                }
            )
        ));
    }

    // Side channels for pre-scanned replacement data (method-scoped, not thread-shared)
    private static final ThreadLocal<List<ReplaceRange>> replaceRanges = new ThreadLocal<>();
    private static final ThreadLocal<java.util.concurrent.atomic.AtomicInteger> replaceIndex = new ThreadLocal<>();

    /** Pre-scan elements to find new+dup+...+invokespecial ranges for REPLACE sites. */
    private static List<ReplaceRange> findReplaceRanges(List<CodeElement> elements, List<PointcutSite> sites) {
        var ranges = new java.util.ArrayList<ReplaceRange>();
        var replaceSites = sites.stream().filter(PointcutSite::replace).toList();
        if (replaceSites.isEmpty()) return ranges;

        for (int i = 0; i < elements.size(); i++) {
            if (!(elements.get(i) instanceof NewObjectInstruction)) continue;
            for (var site : replaceSites) {
                if (site.opcode() < 0x38 || site.opcode() > 0x3B) continue;
                int initIdx = findInvokeSpecialInit(elements, i + 1);
                if (initIdx < 0) continue;
                ranges.add(new ReplaceRange(i, initIdx, site));
                break; // one replacement per new instruction
            }
        }
        return ranges;
    }

    private static int findInvokeSpecialInit(List<CodeElement> elements, int fromIdx) {
        for (int i = fromIdx; i < elements.size(); i++) {
            var ce = elements.get(i);
            if (ce instanceof InvokeInstruction inv
                    && inv.opcode() == Opcode.INVOKESPECIAL
                    && inv.name().stringValue().equals("<init>")) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matchesElement(CodeElement ce, PointcutSite site) {
        int opcode = site.opcode();
        if (opcode >= 0x10 && opcode <= 0x1F)
            return ce instanceof InvokeInstruction inv && inv.opcode() == Opcode.INVOKESTATIC;
        if (opcode >= 0x20 && opcode <= 0x2F)
            return ce instanceof InvokeInstruction inv
                    && (inv.opcode() == Opcode.INVOKEVIRTUAL || inv.opcode() == Opcode.INVOKEINTERFACE);
        if (opcode >= 0x34 && opcode <= 0x37)
            return ce instanceof InvokeInstruction inv
                    && inv.opcode() == Opcode.INVOKESPECIAL && inv.name().stringValue().equals("<init>");
        if (opcode >= 0x38 && opcode <= 0x3B)
            return ce instanceof NewObjectInstruction;
        if (opcode >= 0x4C && opcode <= 0x4F)
            return ce instanceof ReturnInstruction;
        if (opcode == 0xA5)
            return ce instanceof FieldInstruction fi && fi.opcode() == Opcode.GETFIELD;
        if (opcode == 0xA6)
            return ce instanceof FieldInstruction fi && fi.opcode() == Opcode.PUTFIELD;
        if (opcode == 0xA7)
            return ce instanceof FieldInstruction fi && fi.opcode() == Opcode.GETSTATIC;
        if (opcode == 0xA8)
            return ce instanceof FieldInstruction fi && fi.opcode() == Opcode.PUTSTATIC;
        return false;
    }

    private static void emitPublish(java.lang.classfile.CodeBuilder codeBuilder, PointcutSite site) {
        codeBuilder.ldc(site.opcode());
        codeBuilder.ldc(site.method());
        codeBuilder.ldc(site.addr());
        codeBuilder.invokestatic(PUBLISHER_CD, "publish", PUBLISH_DESC);
    }

    private static Map<String, List<PointcutSite>> indexByMethod(List<PointcutSite> sites) {
        Map<String, List<PointcutSite>> map = new LinkedHashMap<>();
        for (var site : sites) {
            String simpleMethod = simpleMethodName(site.method());
            map.computeIfAbsent(simpleMethod, k -> new java.util.ArrayList<>()).add(site);
        }
        return map;
    }

    private static String simpleMethodName(String method) {
        int dot = method.lastIndexOf('.');
        if (dot < 0) return method;
        String rest = method.substring(dot + 1);
        int paren = rest.indexOf('(');
        return paren >= 0 ? rest.substring(0, paren) : rest;
    }
}
