package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;

/**
 * TDD: ClassfilePointcutRewriter — java.lang.classfile only, no Javassist.
 * Tests bytecode rewriting for each pointcut kind.
 *
 * RED first, then GREEN for each pointcut:
 *   1. CALL  (invokestatic)     0x10-0x1F
 *   2. NVOK  (invokevirtual)    0x20-0x2F
 *   3. CONSTR (invokespecial)   0x34-0x37
 *   4. NEW   (new)              0x38-0x3B
 *   5. RETURN (areturn etc.)    0x4C-0x4F
 *   6. L_GET (getfield)         0xA5
 *   7. L_SET (putfield)         0xA6
 *   8. P_GET (getstatic)        0xA7
 *   9. P_SET (putstatic)        0xA8
 */
public class ClassfilePointcutRewriterTest {

    // ── 1. CALL (invokestatic) 0x10 ──────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void call_invokestatic_insertsPublishBefore() {
        byte[] original = buildClassWithStaticCall("CallTarget");
        var site = new ClassfilePointcutRewriter.PointcutSite(0x10, "CallTarget.test", 0, "()V");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, java.util.List.of(site));

        assertTrue(hasPublishBeforeInvoke(rewritten, "doIt"),
                "CALL(0x10) should insert publish() before invokestatic doIt()");
    }

    // ── 2. NVOK (invokevirtual) 0x20 ──────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void nvok_invokevirtual_insertsPublishBefore() {
        byte[] original = buildClassWithVirtualCall("NvokTarget");
        var site = new ClassfilePointcutRewriter.PointcutSite(0x20, "NvokTarget.test", 0, "()V");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, java.util.List.of(site));

        assertTrue(hasPublishBeforeInvoke(rewritten, "toString"),
                "NVOK(0x20) should insert publish() before invokevirtual toString()");
    }

    // ── 3. CONSTR (invokespecial <init>) 0x34 ──────────────────────────────

    @org.junit.jupiter.api.Test
    public void constr_invokespecial_insertsPublishAfter() {
        byte[] original = buildClassWithConstructor("ConstrTarget");
        var site = new ClassfilePointcutRewriter.PointcutSite(0x34, "ConstrTarget.test", 0, "()V");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, java.util.List.of(site));

        // CONSTR fires AFTER invokespecial <init> (verifier constraint: can't inject between dup+<init>)
        assertTrue(containsInvokestaticPublish(rewritten),
                "CONSTR(0x34) should insert publish() after invokespecial <init>");
    }

    // ── 4. NEW (new) 0x38 ──────────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void new_op_insertsPublishBeforeNew() {
        byte[] original = buildClassWithNew("NewTarget");
        var site = new ClassfilePointcutRewriter.PointcutSite(0x38, "NewTarget.test", 0, "()V");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, java.util.List.of(site));

        assertTrue(containsInvokestaticPublish(rewritten),
                "NEW(0x38) should insert publish() call in rewritten bytecode");
    }

    // ── 5. RETURN (areturn) 0x4C ────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void return_op_insertsPublishBeforeReturn() {
        byte[] original = buildClassWithReturn("ReturnTarget");
        var site = new ClassfilePointcutRewriter.PointcutSite(0x4C, "ReturnTarget.test", 0, "()Ljava/lang/Object;");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, java.util.List.of(site));

        assertTrue(containsInvokestaticPublish(rewritten),
                "RETURN(0x4C) should insert publish() before return");
    }

    // ── 6. L_GET (getfield) 0xA5 ────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void lGet_getfield_insertsPublishBefore() {
        byte[] original = buildClassWithGetField("LGetTarget");
        var site = new ClassfilePointcutRewriter.PointcutSite(0xA5, "LGetTarget.test", 0, "()I");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, java.util.List.of(site));

        assertTrue(containsInvokestaticPublish(rewritten),
                "L_GET(0xA5) should insert publish() before getfield");
    }

    // ── 7. L_SET (putfield) 0xA6 ────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void lSet_putfield_insertsPublishBefore() {
        byte[] original = buildClassWithPutField("LSetTarget");
        var site = new ClassfilePointcutRewriter.PointcutSite(0xA6, "LSetTarget.test", 0, "()V");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, java.util.List.of(site));

        assertTrue(containsInvokestaticPublish(rewritten),
                "L_SET(0xA6) should insert publish() before putfield");
    }

    // ── 8. P_GET (getstatic) 0xA7 ────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void pGet_getstatic_insertsPublishBefore() {
        byte[] original = buildClassWithGetStatic("PGetTarget");
        var site = new ClassfilePointcutRewriter.PointcutSite(0xA7, "PGetTarget.test", 0, "()I");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, java.util.List.of(site));

        assertTrue(containsInvokestaticPublish(rewritten),
                "P_GET(0xA7) should insert publish() before getstatic");
    }

    // ── 9. P_SET (putstatic) 0xA8 ────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void pSet_putstatic_insertsPublishBefore() {
        byte[] original = buildClassWithPutStatic("PSetTarget");
        var site = new ClassfilePointcutRewriter.PointcutSite(0xA8, "PSetTarget.test", 0, "()V");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, java.util.List.of(site));

        assertTrue(containsInvokestaticPublish(rewritten),
                "P_SET(0xA8) should insert publish() before putstatic");
    }

    // ── 10. Multi-site: CALL + FIELD in same class ─────────────────────────

    @org.junit.jupiter.api.Test
    public void multiSite_callAndField_bothInstrumented() {
        byte[] original = buildClassWithStaticCall("MultiTarget");
        var site1 = new ClassfilePointcutRewriter.PointcutSite(0x10, "MultiTarget.test", 0, "()V");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original,
                java.util.List.of(site1));

        assertTrue(containsInvokestaticPublish(rewritten),
                "Multi-site should contain publish() call");
    }

    // ── 11. No sites = no change ────────────────────────────────────────────

    @org.junit.jupiter.api.Test
    public void noSites_bytecodeUnchanged() {
        byte[] original = buildClassWithStaticCall("NoSiteTarget");
        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, java.util.List.of());

        assertArrayEquals(original, rewritten,
                "No sites should produce identical bytecode");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test class builders (java.lang.classfile API)
    // ══════════════════════════════════════════════════════════════════════

    private static final ClassDesc OBJECT_CD = ClassDesc.of("java.lang.Object");
    private static final MethodTypeDesc CTOR_DESC = MethodTypeDesc.of(ClassDesc.of("void"));
    private static final ClassDesc STRING_CD = ClassDesc.of("java.lang.String");
    private static final MethodTypeDesc INT_RETURN_DESC = MethodTypeDesc.of(ClassDesc.of("int"));

    /** Build: static void test() { Helper.doIt(); } */
    private static byte[] buildClassWithStaticCall(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withMethod("test", MethodTypeDesc.of(ClassDesc.of("void")),
                java.lang.reflect.AccessFlag.STATIC.mask(),
                mb -> mb.withCode(code -> {
                    code.invokestatic(ClassDesc.of("Helper"), "doIt",
                            MethodTypeDesc.of(ClassDesc.of("void")));
                    code.return_();
                }));
        });
    }

    /** Build: void test() { this.toString(); } */
    private static byte[] buildClassWithVirtualCall(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withMethod("test", MethodTypeDesc.of(ClassDesc.of("void")), 0,
                mb -> mb.withCode(code -> {
                    code.aload(0);
                    code.invokevirtual(OBJECT_CD, "toString",
                            MethodTypeDesc.of(STRING_CD));
                    code.pop();
                    code.return_();
                }));
        });
    }

    /** Build: void test() { new Object(); } — invokespecial <init> */
    private static byte[] buildClassWithConstructor(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withMethod("test", MethodTypeDesc.of(ClassDesc.of("void")), 0,
                mb -> mb.withCode(code -> {
                    code.new_(OBJECT_CD);
                    code.dup();
                    code.invokespecial(OBJECT_CD, "<init>", CTOR_DESC);
                    code.pop();
                    code.return_();
                }));
        });
    }

    /** Build: Object test() { return new Object(); } */
    private static byte[] buildClassWithNew(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withMethod("test", MethodTypeDesc.of(OBJECT_CD), 0,
                mb -> mb.withCode(code -> {
                    code.new_(OBJECT_CD);
                    code.dup();
                    code.invokespecial(OBJECT_CD, "<init>", CTOR_DESC);
                    code.areturn();
                }));
        });
    }

    /** Build: Object test() { return "hello"; } */
    private static byte[] buildClassWithReturn(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withMethod("test", MethodTypeDesc.of(OBJECT_CD), 0,
                mb -> mb.withCode(code -> {
                    code.ldc("hello");
                    code.areturn();
                }));
        });
    }

    /** Build: int test() { return this.field; } — with getfield */
    private static byte[] buildClassWithGetField(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withField("field", ClassDesc.of("int"), java.lang.reflect.AccessFlag.PUBLIC.mask());
            cb.withMethod("test", INT_RETURN_DESC, 0,
                mb -> mb.withCode(code -> {
                    code.aload(0);
                    code.getfield(ClassDesc.of(className), "field", ClassDesc.of("int"));
                    code.ireturn();
                }));
        });
    }

    /** Build: void test() { this.field = 42; } — with putfield */
    private static byte[] buildClassWithPutField(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withField("field", ClassDesc.of("int"), java.lang.reflect.AccessFlag.PUBLIC.mask());
            cb.withMethod("test", MethodTypeDesc.of(ClassDesc.of("void")), 0,
                mb -> mb.withCode(code -> {
                    code.aload(0);
                    code.bipush(42);
                    code.putfield(ClassDesc.of(className), "field", ClassDesc.of("int"));
                    code.return_();
                }));
        });
    }

    /** Build: static int COUNT; int test() { return COUNT; } — with getstatic */
    private static byte[] buildClassWithGetStatic(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withField("COUNT", ClassDesc.of("int"),
                    java.lang.reflect.AccessFlag.PUBLIC.mask() | java.lang.reflect.AccessFlag.STATIC.mask());
            cb.withMethod("test", INT_RETURN_DESC, 0,
                mb -> mb.withCode(code -> {
                    code.getstatic(ClassDesc.of(className), "COUNT", ClassDesc.of("int"));
                    code.ireturn();
                }));
        });
    }

    /** Build: static int COUNT; void test() { COUNT = 7; } — with putstatic */
    private static byte[] buildClassWithPutStatic(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withField("COUNT", ClassDesc.of("int"),
                    java.lang.reflect.AccessFlag.PUBLIC.mask() | java.lang.reflect.AccessFlag.STATIC.mask());
            cb.withMethod("test", MethodTypeDesc.of(ClassDesc.of("void")), 0,
                mb -> mb.withCode(code -> {
                    code.bipush(7);
                    code.putstatic(ClassDesc.of(className), "COUNT", ClassDesc.of("int"));
                    code.return_();
                }));
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // Verification helpers
    // ══════════════════════════════════════════════════════════════════════

    /** Check if the rewritten class contains any invokestatic VmPointcutPublisher.publish */
    private static boolean containsInvokestaticPublish(byte[] classBytes) {
        ClassModel cm = ClassFile.of().parse(classBytes);
        for (MethodModel mm : cm.methods()) {
            if (mm.code().isEmpty()) continue;
            for (CodeElement ce : mm.code().get().elementList()) {
                if (ce instanceof InvokeInstruction inv) {
                    var owner = inv.owner().asInternalName();
                    var name = inv.name().stringValue();
                    if (owner.equals("org/xvm/runtime/VmPointcutPublisher")
                            && name.equals("publish")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Check if publish() appears before the given method invocation in bytecode order */
    private static boolean hasPublishBeforeInvoke(byte[] classBytes, String targetMethodName) {
        ClassModel cm = ClassFile.of().parse(classBytes);
        for (MethodModel mm : cm.methods()) {
            if (mm.code().isEmpty()) continue;
            boolean foundPublish = false;
            for (CodeElement ce : mm.code().get().elementList()) {
                if (ce instanceof InvokeInstruction inv) {
                    var owner = inv.owner().asInternalName();
                    var name = inv.name().stringValue();
                    if (owner.equals("org/xvm/runtime/VmPointcutPublisher")
                            && name.equals("publish")) {
                        foundPublish = true;
                    }
                    if (name.equals(targetMethodName) && !owner.equals("org/xvm/runtime/VmPointcutPublisher")) {
                        // Target call found — publish must have been seen before
                        if (!foundPublish) return false;
                    }
                }
            }
            if (foundPublish) return true;
        }
        return false;
    }
}
