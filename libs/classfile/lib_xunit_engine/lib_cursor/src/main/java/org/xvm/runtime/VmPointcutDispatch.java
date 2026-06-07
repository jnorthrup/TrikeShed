package org.xvm.runtime;

/**
 * Auto-generated dispatch table for @Pointcut annotation on xvm opcodes.
 * Produced by the build script that reads Op.java opcode constants.
 *
 * Wireproto: opcode byte IS the codec selector (1 byte, 0-255).
 * Each entry carries: Pointcut.Kind + before/after flags.
 *
 * Usage:
 *   PointcutRegistry.init();  // call once at startup
 *   if (PointcutRegistry.hasBefore(opcode)) {
 *       VmPointcutPublisher.publish(opcode, method, addr);
 *   }
 *
 * Phase mapping:
 *   CALL  → call graph debt
 *   ALLOC → allocation churn
 *   RETURN → return site density
 *   FIELD → property access
 *   ASSERT → assertion density
 *   LOOP  → control flow complexity
 *   GAP   → Shannon entropy used as gap metric
 */
public final class VmPointcutDispatch {

    public static final int TABLE_SIZE = 256;

    /** Opcode → Pointcut.Kind */
    public static final Kind[] KIND_TABLE = new Kind[TABLE_SIZE];

    /** Opcode → fires on instantiate (decode) */
    public static final boolean[] BEFORE_TABLE = new boolean[TABLE_SIZE];

    /** Opcode → fires on write (re-encode) */
    public static final boolean[] AFTER_TABLE = new boolean[TABLE_SIZE];

    /** Opcode → simple class name */
    public static final String[] METHOD_TABLE = new String[TABLE_SIZE];

    static {
        // ── CALL family (0x10-0x1F) ──────────────────────────────────────────
        // Call_00=0x10, Call_01=0x11 ... Call_TT=0x1F
        initRange(0x10, 0x1F, Kind.CALL, true, true);

        // ── NVOK family (0x20-0x2F) ─────────────────────────────────────────
        initRange(0x20, 0x2F, Kind.CALL, true, true);

        // ── SYN_INIT (0x33) ────────────────────────────────────────────────
        initRange(0x33, 0x33, Kind.SYNC, true, false);

        // ── CONSTR family (0x34-0x37) ───────────────────────────────────────
        initRange(0x34, 0x37, Kind.ALLOC, true, false);

        // ── NEW family (0x38-0x3B) ─────────────────────────────────────────
        initRange(0x38, 0x3B, Kind.ALLOC, true, true);

        // ── NEWC family (0x40-0x43) ─────────────────────────────────────────
        initRange(0x40, 0x43, Kind.ALLOC, true, true);

        // ── NEWV family (0x48-0x4B) ─────────────────────────────────────────
        initRange(0x48, 0x4B, Kind.ALLOC, true, true);

        // ── RETURN family (0x4C-0x4F) ─────────────────────────────────────
        initRange(0x4C, 0x4F, Kind.RETURN, true, false);

        // ── MOV_TYPE (0x65) ────────────────────────────────────────────────
        initRange(0x65, 0x65, Kind.TYPE, true, true);

        // ── CAST (0x66) ───────────────────────────────────────────────────
        initRange(0x66, 0x66, Kind.TYPE, true, true);

        // ── LOOP / LOOP_END / JMP / JMPT / JMPF (0x70-0x7F) ────────────────
        initRange(0x70, 0x73, Kind.LOOP, true, false);  // LOOP, LOOP_END, JMP
        initRange(0x74, 0x77, Kind.LOOP, true, false);  // JMPT, JMPF, PAD, PAD
        initRange(0x78, 0x7F, Kind.LOOP, true, false);  // remaining ctrl

        // ── ASSERT family (0x90-0x92) ───────────────────────────────────────
        initRange(0x90, 0x92, Kind.ASSERT, true, false);

        // ── L_GET / L_SET / P_GET / P_SET (0xA5-0xA8) ─────────────────────
        initRange(0xA5, 0xA5, Kind.FIELD, true, true);
        initRange(0xA6, 0xA6, Kind.FIELD, true, true);
        initRange(0xA7, 0xA7, Kind.FIELD, true, true);
        initRange(0xA8, 0xA8, Kind.FIELD, true, true);

        // Default: GAP, no instrumentation
    }

    private static void initRange(int from, int to, Kind kind, boolean before, boolean after) {
        for (int op = from; op <= to; op++) {
            if (op < TABLE_SIZE) {
                KIND_TABLE[op] = kind;
                BEFORE_TABLE[op] = before;
                AFTER_TABLE[op] = after;
            }
        }
    }

    public static Kind kindOf(int opcode) {
        if (opcode < 0 || opcode >= TABLE_SIZE) return Kind.GAP;
        Kind k = KIND_TABLE[opcode];
        return k != null ? k : Kind.GAP;
    }

    public static boolean hasBefore(int opcode) {
        return opcode >= 0 && opcode < TABLE_SIZE && BEFORE_TABLE[opcode];
    }

    public static boolean hasAfter(int opcode) {
        return opcode >= 0 && opcode < TABLE_SIZE && AFTER_TABLE[opcode];
    }

    /** Static aliases for tests — forwarding to package-static methods */
    public static Kind kindOfStatic(int opcode) { return kindOf(opcode); }
    public static boolean hasBeforeStatic(int opcode) { return hasBefore(opcode); }
    public static boolean hasAfterStatic(int opcode) { return hasAfter(opcode); }

    /** Fire BEFORE pointcut — call from Op.instantiate() */
    public static void beforeInstantiate(int opcode, String method, int addr) {
        if (hasBefore(opcode)) {
            VmPointcutPublisher.publish(opcode, method, addr);
        }
    }

    /** Fire AFTER pointcut — call from op.write() */
    public static void afterWrite(int opcode) {
        if (hasAfter(opcode)) {
            VmPointcutPublisher.publish(opcode, "AFTER", -1);
        }
    }

    public enum Kind {
        GAP,    // unclassified / catch-all
        CALL,   // direct call / virtual invoke
        ALLOC,  // object allocation / construction
        RETURN, // return site
        FIELD,  // property get/set
        TYPE,   // type/copy/cast
        ASSERT, // assertion
        LOOP,   // control flow
        SYNC    // synchronization
    }
}
