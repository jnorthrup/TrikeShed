package org.xvm.asm.op.annotation;

import java.lang.annotation.*;

/**
 * Mark an org.xvm.asm.op Op subclass as a pointcut site for xvm bytecode instrumentation.
 *
 * BEFORE: fires when Op.instantiate() decodes the opcode from DataInput.
 *   → XvmConfixBridge.onBeforePointcut(opcode, method, addr)
 *
 * AFTER: fires when op.write(DataOutput) re-encodes the opcode.
 *   → XvmConfixBridge.onAfterPointcut(opcode)
 *
 * Wireproto: the opcode byte IS the codec selector (1 byte, 0-255).
 * Op-specific fields are fixed-layout per subclass — read in order by Op.instantiate(),
 * written in same order by op.write().
 *
 * Annotation is processed by PointcutKspProcessor (lib_cursor_ksp) at compile time.
 * PointcutRegistry (lib_cursor) reads annotations at startup via classpath reflection.
 *
 * CRMS phases mapped from Kind:
 *   CALL  → phase = "CALL"   (high signal for call graph debt)
 *   ALLOC → phase = "ALLOC"  (object allocation churn)
 *   RETURN → phase = "RETURN" (return site analysis)
 *   FIELD → phase = "FIELD"  (property access density)
 *   GAP   → phase = "GAP"    (catch-all, Shannon entropy used as gap metric)
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Pointcut {
    Kind kind() default Kind.GAP;
    boolean before() default false;
    boolean after() default false;

    enum Kind {
        GAP,    // unclassified / catch-all
        CALL,   // direct call site
        ALLOC,  // object allocation / construction
        RETURN, // return site
        FIELD,  // property get/set
        TYPE,   // type/copy/cast
        ASSERT, // assertion
        LOOP,   // control flow
        SYNC,   // synchronization
    }
}
