package org.xvm.runtime;

/**
 * Columnar typedef cascade table — the SIMD-ready SoA layer.
 *
 * Captures the 5-tier production system cascade as columnar dense arrays:
 *   Tier 0: leaf scan (ConstantPool object graph) — pointer chase, NOT captured here
 *   Tier 1: columnar reduce over depth/kind/scope histograms — SIMD-amenable
 *   Tier 2: classifyKind — sealed-hierarchy dispatch results materialized here
 *   Tier 3: adjacency — forEachUnderlying edge counts materialized here
 *   Tier 4: rule match — AdjacentRule columns for ClassfilePointcutRewriter
 *
 * Layout: Structure-of-Arrays (SoA) for SIMD reduce.
 * All columns are dense, contiguous, no pointer chase.
 *
 * Self-contained: no imports from javatools. The dispatch tables are inlined
 * as byte[] arrays mirrored from VmPointcutDispatch.
 *
 * Column definitions:
 *   kind:    byte — CALL=0, ALLOC=1, RETURN=2, FIELD=3, TYPE=4, ASSERT=5, LOOP=6, SYNC=7, GAP=8
 *   depth:   byte — 0..5 scope depth
 *   scope:   byte — MODULE=0, PACKAGE=1, CLASS=2, METHOD=3
 *   success: byte — 0=fail, 1=ok
 *   siteOrd: int  — callsite ordinal
 *   poolId:  int  — pool identity
 *
 * Reduce: depth histogram (6 buckets), kind histogram (10 buckets),
 *         scope histogram (4 buckets), success rate.
 *
 * Rule columns (AdjacentRule SoA):
 *   rule_opcodes:    int[]  — opcode byte for each rule
 *   rule_siteOrd:    int[]  — callsite ordinal for each rule
 *   rule_depth:      byte[] — scope depth
 *   rule_kind:       byte[] — kind byte
 *
 * @see FieldSynapse for the RingSeries → slab → subscriber pipeline
 */
public final class TypedefCascadeTable {

    // ── Kind constants (self-contained, mirrors VmPointcutDispatch.Kind) ────

    public static final byte KIND_CALL   = 0;
    public static final byte KIND_ALLOC  = 1;
    public static final byte KIND_RETURN = 2;
    public static final byte KIND_FIELD  = 3;
    public static final byte KIND_TYPE   = 4;
    public static final byte KIND_ASSERT = 5;
    public static final byte KIND_LOOP   = 6;
    public static final byte KIND_SYNC   = 7;
    public static final byte KIND_GAP    = 8;
    public static final byte KIND_COUNT  = 9;

    // ── Scope constants ────────────────────────────────────────────────────

    public static final byte SCOPE_MODULE  = 0;
    public static final byte SCOPE_PACKAGE = 1;
    public static final byte SCOPE_CLASS   = 2;
    public static final byte SCOPE_METHOD  = 3;
    public static final byte SCOPE_COUNT   = 4;

    public static final int MAX_DEPTH = 6;
    public static final int DISPATCH_TABLE_SIZE = 256;

    // ── Inlined dispatch tables (mirrors VmPointcutDispatch static init) ───

    /** Opcode → kind byte */
    private static final byte[] DISPATCH_KIND = new byte[DISPATCH_TABLE_SIZE];
    /** Opcode → hasBefore */
    private static final boolean[] DISPATCH_BEFORE = new boolean[DISPATCH_TABLE_SIZE];
    /** Opcode → hasAfter */
    private static final boolean[] DISPATCH_AFTER = new boolean[DISPATCH_TABLE_SIZE];

    static {
        // Initialize all to GAP
        java.util.Arrays.fill(DISPATCH_KIND, KIND_GAP);
        dispatchInitRange(0x10, 0x1F, KIND_CALL,   true,  true);   // CALL
        dispatchInitRange(0x20, 0x2F, KIND_CALL,   true,  true);   // NVOK
        dispatchInitRange(0x33, 0x33, KIND_SYNC,   true,  false);  // SYN_INIT
        dispatchInitRange(0x34, 0x37, KIND_ALLOC,  true,  false);  // CONSTR
        dispatchInitRange(0x38, 0x3B, KIND_ALLOC,  true,  true);   // NEW
        dispatchInitRange(0x40, 0x43, KIND_ALLOC,  true,  true);   // NEWC
        dispatchInitRange(0x48, 0x4B, KIND_ALLOC,  true,  true);   // NEWV
        dispatchInitRange(0x4C, 0x4F, KIND_RETURN, true,  false);  // RETURN
        dispatchInitRange(0x65, 0x65, KIND_TYPE,   true,  true);   // MOV_TYPE
        dispatchInitRange(0x66, 0x66, KIND_TYPE,   true,  true);   // CAST
        dispatchInitRange(0x70, 0x73, KIND_LOOP,   true,  false);  // LOOP
        dispatchInitRange(0x74, 0x77, KIND_LOOP,   true,  false);  // JMPT/JMPF
        dispatchInitRange(0x78, 0x7F, KIND_LOOP,   true,  false);  // ctrl
        dispatchInitRange(0x90, 0x92, KIND_ASSERT, true,  false);  // ASSERT
        dispatchInitRange(0xA5, 0xA8, KIND_FIELD,  true,  true);   // L_GET..P_SET
    }

    private static void dispatchInitRange(int from, int to, byte kind, boolean before, boolean after) {
        for (int op = from; op <= to; op++) {
            if (op < DISPATCH_TABLE_SIZE) {
                DISPATCH_KIND[op] = kind;
                DISPATCH_BEFORE[op] = before;
                DISPATCH_AFTER[op] = after;
            }
        }
    }

    // ── SoA columns ────────────────────────────────────────────────────────

    private int n;
    private byte[] kind;
    private byte[] depth;
    private byte[] scope;
    private byte[] success;
    private int[] siteOrd;
    private int[] poolId;

    // ── Reduce accumulators ────────────────────────────────────────────────

    private final int[] depthHistogram  = new int[MAX_DEPTH];
    private final int[] kindHistogram   = new int[KIND_COUNT];
    private final int[] scopeHistogram  = new int[SCOPE_COUNT];
    private int successCount;
    private int failCount;

    // ── Rule columns ───────────────────────────────────────────────────────

    private int ruleCount;
    private int[] rule_opcodes;
    private int[] rule_siteOrd;
    private byte[] rule_depth;
    private byte[] rule_kind;

    // ── Constructor ────────────────────────────────────────────────────────

    public TypedefCascadeTable(int capacity) {
        this.n = 0;
        this.kind    = new byte[capacity];
        this.depth   = new byte[capacity];
        this.scope   = new byte[capacity];
        this.success = new byte[capacity];
        this.siteOrd = new int[capacity];
        this.poolId  = new int[capacity];

        this.ruleCount = 0;
        this.rule_opcodes = new int[64];
        this.rule_siteOrd = new int[64];
        this.rule_depth   = new byte[64];
        this.rule_kind    = new byte[64];
    }

    // ── Row append ─────────────────────────────────────────────────────────

    public void appendRow(byte kindVal, byte depthVal, byte scopeVal,
                          byte successVal, int siteOrdVal, int poolIdVal) {
        ensureCapacity(n + 1);
        kind[n]    = kindVal;
        depth[n]   = depthVal;
        scope[n]   = scopeVal;
        success[n] = successVal;
        siteOrd[n] = siteOrdVal;
        poolId[n]  = poolIdVal;
        n++;
    }

    // ── Rule append ────────────────────────────────────────────────────────

    public void appendRule(int opcode, int siteOrdVal, byte depthVal, byte kindVal) {
        ensureRuleCapacity(ruleCount + 1);
        rule_opcodes[ruleCount] = opcode;
        rule_siteOrd[ruleCount] = siteOrdVal;
        rule_depth[ruleCount]   = depthVal;
        rule_kind[ruleCount]    = kindVal;
        ruleCount++;
    }

    // ── Reduce ─────────────────────────────────────────────────────────────

    public void reduce() {
        for (int i = 0; i < MAX_DEPTH; i++)  depthHistogram[i] = 0;
        for (int i = 0; i < KIND_COUNT; i++) kindHistogram[i]  = 0;
        for (int i = 0; i < SCOPE_COUNT; i++) scopeHistogram[i] = 0;
        successCount = 0;
        failCount = 0;

        for (int i = 0; i < n; i++) {
            if (depth[i] >= 0 && depth[i] < MAX_DEPTH) depthHistogram[depth[i]]++;
            if (kind[i] >= 0 && kind[i] < KIND_COUNT)  kindHistogram[kind[i]]++;
            if (scope[i] >= 0 && scope[i] < SCOPE_COUNT) scopeHistogram[scope[i]]++;
            if (success[i] != 0) successCount++; else failCount++;
        }
    }

    // ── Rule match ─────────────────────────────────────────────────────────

    public int matchRule(int opcode) {
        for (int i = 0; i < ruleCount; i++) {
            if (rule_opcodes[i] == opcode) return rule_siteOrd[i];
        }
        return -1;
    }

    public int matchRuleCount(int opcode, byte kindFilter) {
        int count = 0;
        for (int i = 0; i < ruleCount; i++) {
            if (rule_opcodes[i] == opcode && rule_kind[i] == kindFilter) count++;
        }
        return count;
    }

    // ── Column-router: opcode → row (self-contained dispatch) ─────────────

    /**
     * Route opcode through the inlined dispatch tables and materialize into
     * this cascade table. No external dependencies.
     */
    public void routeOpcode(int opcode, String method, int addr) {
        byte kindByte = (opcode >= 0 && opcode < DISPATCH_TABLE_SIZE)
                        ? DISPATCH_KIND[opcode] : KIND_GAP;
        boolean before = (opcode >= 0 && opcode < DISPATCH_TABLE_SIZE)
                         && DISPATCH_BEFORE[opcode];
        boolean after  = (opcode >= 0 && opcode < DISPATCH_TABLE_SIZE)
                         && DISPATCH_AFTER[opcode];

        byte scopeByte = opcodeToScope(opcode);
        byte depthByte = 0;
        byte successByte = (byte) ((before || after) ? 1 : 0);

        appendRow(kindByte, depthByte, scopeByte, successByte, -1, 0);
    }

    /**
     * Fold a single event into the cascade table by raw fields.
     * Callers extract fields from their own event types.
     */
    public void fold(int opcode, String method, int addr) {
        routeOpcode(opcode, method, addr);
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    public int rowCount()               { return n; }
    public int ruleCount()              { return ruleCount; }
    public byte[] kindColumn()          { return kind; }
    public byte[] depthColumn()         { return depth; }
    public byte[] scopeColumn()         { return scope; }
    public byte[] successColumn()       { return success; }
    public int[]  siteOrdColumn()       { return siteOrd; }
    public int[]  poolIdColumn()        { return poolId; }
    public int[]  depthHistogram()      { return depthHistogram; }
    public int[]  kindHistogram()       { return kindHistogram; }
    public int[]  scopeHistogram()      { return scopeHistogram; }
    public int    successCount()        { return successCount; }
    public int    failCount()           { return failCount; }
    public int[]  ruleOpcodes()         { return rule_opcodes; }

    // ── Reset ──────────────────────────────────────────────────────────────

    public void reset() {
        n = 0;
        ruleCount = 0;
        for (int i = 0; i < MAX_DEPTH; i++)  depthHistogram[i] = 0;
        for (int i = 0; i < KIND_COUNT; i++) kindHistogram[i]  = 0;
        for (int i = 0; i < SCOPE_COUNT; i++) scopeHistogram[i] = 0;
        successCount = 0;
        failCount = 0;
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private void ensureCapacity(int needed) {
        if (needed <= kind.length) return;
        int newCap = kind.length * 2;
        while (newCap < needed) newCap *= 2;
        kind    = copyOf(kind, newCap);
        depth   = copyOf(depth, newCap);
        scope   = copyOf(scope, newCap);
        success = copyOf(success, newCap);
        siteOrd = copyOf(siteOrd, newCap);
        poolId  = copyOf(poolId, newCap);
    }

    private void ensureRuleCapacity(int needed) {
        if (needed <= rule_opcodes.length) return;
        int newCap = rule_opcodes.length * 2;
        while (newCap < needed) newCap *= 2;
        rule_opcodes = copyOf(rule_opcodes, newCap);
        rule_siteOrd = copyOf(rule_siteOrd, newCap);
        rule_depth   = copyOf(rule_depth, newCap);
        rule_kind    = copyOf(rule_kind, newCap);
    }

    private static byte[] copyOf(byte[] src, int newLen) {
        byte[] dst = new byte[newLen];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    private static int[] copyOf(int[] src, int newLen) {
        int[] dst = new int[newLen];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    private static byte opcodeToScope(int opcode) {
        if (opcode >= 0x10 && opcode <= 0x2F) return SCOPE_METHOD;
        if (opcode >= 0x38 && opcode <= 0x4B) return SCOPE_CLASS;
        if (opcode >= 0x4C && opcode <= 0x4F) return SCOPE_METHOD;
        if (opcode >= 0xA5 && opcode <= 0xA8) return SCOPE_CLASS;
        return SCOPE_MODULE;
    }
}
