package org.xvm.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.Arrays;
import java.util.List;

/**
 * TDD: ListCtorCowPointcut — intercepts ArrayList/List ctors and rewrites to
 * CowSeriesHandle (VersioningMutableSeries COW body) with .toSeries().toList()
 * as the unary getter output.
 *
 * The pointcut captures:
 *   new ArrayList<>()     → COW delegate → toSeries().toList()
 *   new ArrayList<>(n)    → COW delegate → toSeries().toList()
 */
public class ListCtorCowPointcutTest {

    // ── 1. ArrayList() default ctor → captured by ALLOC pointcut ─────────

    @org.junit.jupiter.api.Test
    public void arrayListCtor_publishesAllocEvent() {
        byte[] original = buildClassWithArrayListCtor("ArrayListTarget1");
        var site = new ClassfilePointcutRewriter.PointcutSite(0x38, "ArrayListTarget1.test", 0, "()V");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, List.of(site));

        assertTrue(containsInvokestaticPublish(rewritten),
                "new ArrayList() should trigger publish() via ALLOC(0x38) pointcut");
    }

    // ── 2. ArrayList(n) sized ctor → captured by ALLOC pointcut ───────────

    @org.junit.jupiter.api.Test
    public void arrayListSizedCtor_publishesAllocEvent() {
        byte[] original = buildClassWithArrayListSizedCtor("ArrayListTarget2");
        var site = new ClassfilePointcutRewriter.PointcutSite(0x38, "ArrayListTarget2.test", 0, "()V");

        byte[] rewritten = ClassfilePointcutRewriter.rewrite(original, List.of(site));

        assertTrue(containsInvokestaticPublish(rewritten),
                "new ArrayList(100) should trigger publish() via ALLOC(0x38) pointcut");
    }

    // ── 3. CowSeriesHandle is the VersioningMutableSeries delegate ───────

    @org.junit.jupiter.api.Test
    public void cowSeriesHandle_tracksVersion() {
        var items = Arrays.asList("a", "b", "c");
        var backing = borg.trikeshed.lib.SeriesKt.toSeries(items);
        var body = new borg.trikeshed.lib.COWSeriesBody<>(backing, null);
        var cow = new borg.trikeshed.lib.CowSeriesHandle<>(body, null, null);

        assertEquals(3, cow.getA());
        assertNull(body.getVersion());

        cow.add("d");
        assertEquals(4, cow.getA());
        assertNotNull(cow.getVersion());
    }

    // ── 4. CowSeriesHandle.add → .toSeries().toList() round-trip ────────

    @org.junit.jupiter.api.Test
    public void cowSeries_toSeries_toList_roundTrip() {
        var items = Arrays.asList("x", "y", "z");
        var backing = borg.trikeshed.lib.SeriesKt.toSeries(items);
        var body = new borg.trikeshed.lib.COWSeriesBody<>(backing, null);
        var cow = new borg.trikeshed.lib.CowSeriesHandle<>(body, null, null);

        cow.add("w");

        // toSeries().toList() as unary getter — raw cast needed for Kotlin variance
        var asList = borg.trikeshed.lib.SeriesKt.toList((borg.trikeshed.lib.Join) cow);

        assertEquals(4, asList.size());
        assertEquals("x", asList.get(0));
        assertEquals("y", asList.get(1));
        assertEquals("z", asList.get(2));
        assertEquals("w", asList.get(3));
    }

    // ── 5. ListCtorCowBridge captures ArrayList ctor → COW ─────────────

    @org.junit.jupiter.api.Test
    public void bridge_wrapsArrayListInCow() {
        var list = new java.util.ArrayList<String>();
        list.add("hello");

        var cow = ListCtorCowBridge.wrap(list);
        cow.add("world");

        var result = cow.toSeriesToList();
        assertEquals(2, result.size());
        assertEquals("hello", result.get(0));
        assertEquals("world", result.get(1));
    }

    // ── 6. Bridge with initial capacity ────────────────────────────────

    @org.junit.jupiter.api.Test
    public void bridge_wrapsSizedArrayList() {
        var list = new java.util.ArrayList<Integer>(100);
        var cow = ListCtorCowBridge.wrap(list);
        for (int i = 0; i < 5; i++) cow.add(i);

        var result = cow.toSeriesToList();
        assertEquals(5, result.size());
        assertEquals(0, result.get(0));
        assertEquals(4, result.get(4));
    }

    // ── 7. Bridge publishes to VmPointcutPublisher ─────────────────────

    @org.junit.jupiter.api.Test
    public void bridge_publishesOnWrap() {
        VmPointcutPublisher.reset();
        VmPointcutPublisher.active = true;
        try {
            var list = new java.util.ArrayList<String>();
            ListCtorCowBridge.wrapAndPublish(list, "TestTarget.test", 0);

            assertEquals(1, VmPointcutPublisher.size());
            var evt = VmPointcutPublisher.peek(0);
            assertEquals(0x38, evt.opcode);
            assertEquals("TestTarget.test", evt.methodName());
        } finally {
            VmPointcutPublisher.active = false;
        }
    }

    // ── 8. Version tracks across mutations ─────────────────────────────

    @org.junit.jupiter.api.Test
    public void bridge_versionBumpsOnMutation() {
        var list = new java.util.ArrayList<String>();
        var cow = ListCtorCowBridge.wrap(list);

        Object v0 = cow.version();
        cow.add("first");
        Object v1 = cow.version();
        cow.add("second");
        Object v2 = cow.version();

        assertNotEquals(v0, v1, "version must change on first add");
        assertNotEquals(v1, v2, "version must change on second add");
    }

    // ══════════════════════════════════════════════════════════════════════
    // Test class builders
    // ══════════════════════════════════════════════════════════════════════

    private static final ClassDesc ARRAYLIST_CD = ClassDesc.of("java.util.ArrayList");
    private static final MethodTypeDesc AL_CTOR_DESC = MethodTypeDesc.of(ClassDesc.of("void"));
    private static final MethodTypeDesc AL_CTOR_INT_DESC = MethodTypeDesc.of(ClassDesc.of("void"), ClassDesc.of("int"));

    /** Build: void test() { new ArrayList(); } */
    private static byte[] buildClassWithArrayListCtor(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withMethod("test", MethodTypeDesc.of(ClassDesc.of("void")),
                java.lang.reflect.AccessFlag.STATIC.mask(),
                mb -> mb.withCode(code -> {
                    code.new_(ARRAYLIST_CD);
                    code.dup();
                    code.invokespecial(ARRAYLIST_CD, "<init>", AL_CTOR_DESC);
                    code.pop();
                    code.return_();
                }));
        });
    }

    /** Build: void test() { new ArrayList(100); } */
    private static byte[] buildClassWithArrayListSizedCtor(String className) {
        return ClassFile.of().build(ClassDesc.of(className), cb -> {
            cb.withMethod("test", MethodTypeDesc.of(ClassDesc.of("void")),
                java.lang.reflect.AccessFlag.STATIC.mask(),
                mb -> mb.withCode(code -> {
                    code.new_(ARRAYLIST_CD);
                    code.dup();
                    code.bipush(100);
                    code.invokespecial(ARRAYLIST_CD, "<init>", AL_CTOR_INT_DESC);
                    code.pop();
                    code.return_();
                }));
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // Verification helpers
    // ══════════════════════════════════════════════════════════════════════

    private static boolean containsInvokestaticPublish(byte[] classBytes) {
        ClassModel cm = ClassFile.of().parse(classBytes);
        for (MethodModel mm : cm.methods()) {
            if (mm.code().isEmpty()) continue;
            for (CodeElement ce : mm.code().get().elementList()) {
                if (ce instanceof InvokeInstruction inv) {
                    if (inv.owner().asInternalName().equals("org/xvm/runtime/VmPointcutPublisher")
                            && inv.name().stringValue().equals("publish")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
