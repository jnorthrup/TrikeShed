package org.xvm.runtime;

import java.lang.constant.ClassDesc;

/**
 * XVM primitive type translation table: XVM type name -> JVM descriptor, slot count,
 * box class, unbox method, SIMD mask, autovectorization lane width.
 *
 * Self-contained: inlines all ClassDesc constants from Builder.N_* strings.
 * No imports from javatools.
 *
 * Slot count key:
 *   1 = 1 JVM slot  (int, float, reference)
 *   2 = 2 JVM slots (long, double)
 *   4 = 4 JVM slots (Int128, UInt128, Dec128 via long[2])
 *
 * SIMD mask: bitmask for autovectorization of typed arrays.
 * Autovectorization lane width = 32 bytes (AVX2) / bytesPerSlot.
 */
public final class XvmPrimitiveTranslationTable {

    private XvmPrimitiveTranslationTable() {}

    public static final int SLOTS_1 = 1;
    public static final int SLOTS_2 = 2;
    public static final int SLOTS_4 = 4;

    // ── Inlined ClassDesc constants (mirrors Builder.N_* → CD_*) ───────────

    private static final ClassDesc CD_Boolean  = ClassDesc.of("org.xtclang.ecstasy.Boolean");
    private static final ClassDesc CD_Char     = ClassDesc.of("org.xtclang.ecstasy.text.Char");
    private static final ClassDesc CD_Nibble   = ClassDesc.of("org.xtclang.ecstasy.numbers.Nibble");
    private static final ClassDesc CD_Int8     = ClassDesc.of("org.xtclang.ecstasy.numbers.Int8");
    private static final ClassDesc CD_UInt8    = ClassDesc.of("org.xtclang.ecstasy.numbers.UInt8");
    private static final ClassDesc CD_Int16    = ClassDesc.of("org.xtclang.ecstasy.numbers.Int16");
    private static final ClassDesc CD_UInt16   = ClassDesc.of("org.xtclang.ecstasy.numbers.UInt16");
    private static final ClassDesc CD_Int32    = ClassDesc.of("org.xtclang.ecstasy.numbers.Int32");
    private static final ClassDesc CD_UInt32   = ClassDesc.of("org.xtclang.ecstasy.numbers.UInt32");
    private static final ClassDesc CD_Int64    = ClassDesc.of("org.xtclang.ecstasy.numbers.Int64");
    private static final ClassDesc CD_UInt64   = ClassDesc.of("org.xtclang.ecstasy.numbers.UInt64");
    private static final ClassDesc CD_Float32  = ClassDesc.of("org.xtclang.ecstasy.numbers.Float32");
    private static final ClassDesc CD_Float64  = ClassDesc.of("org.xtclang.ecstasy.numbers.Float64");
    private static final ClassDesc CD_Dec32    = ClassDesc.of("org.xtclang.ecstasy.numbers.Dec32");
    private static final ClassDesc CD_Dec64    = ClassDesc.of("org.xtclang.ecstasy.numbers.Dec64");
    private static final ClassDesc CD_Dec128   = ClassDesc.of("org.xtclang.ecstasy.numbers.Dec128");
    private static final ClassDesc CD_Int128   = ClassDesc.of("org.xtclang.ecstasy.numbers.Int128");
    private static final ClassDesc CD_UInt128  = ClassDesc.of("org.xtclang.ecstasy.numbers.UInt128");
    private static final ClassDesc CD_String   = ClassDesc.of("org.xtclang.ecstasy.text.String");
    private static final ClassDesc CD_Nullable  = ClassDesc.of("org.xtclang.ecstasy.Nullable");
    private static final ClassDesc CD_ArrayBit      = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Bit\u1424");
    private static final ClassDesc CD_ArrayBoolean  = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Boolean\u1424");
    private static final ClassDesc CD_ArrayChar     = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Char\u1424");
    private static final ClassDesc CD_ArrayNibble   = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Nibble\u1424");
    private static final ClassDesc CD_ArrayInt8     = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Int8\u1424");
    private static final ClassDesc CD_ArrayUInt8    = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424UInt8\u1424");
    private static final ClassDesc CD_ArrayInt16    = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Int16\u1424");
    private static final ClassDesc CD_ArrayUInt16   = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424UInt16\u1424");
    private static final ClassDesc CD_ArrayInt32    = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Int32\u1424");
    private static final ClassDesc CD_ArrayUInt32   = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424UInt32\u1424");
    private static final ClassDesc CD_ArrayInt64    = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Int64\u1424");
    private static final ClassDesc CD_ArrayUInt64   = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424UInt64\u1424");
    private static final ClassDesc CD_ArrayInt128   = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Int128\u1424");
    private static final ClassDesc CD_ArrayUInt128  = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424UInt128\u1424");
    private static final ClassDesc CD_ArrayFloat32  = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Float32\u1424");
    private static final ClassDesc CD_ArrayFloat64  = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Float64\u1424");
    private static final ClassDesc CD_ArrayDec32    = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Dec32\u1424");
    private static final ClassDesc CD_ArrayDec64    = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Dec64\u1424");
    private static final ClassDesc CD_ArrayDec128   = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Dec128\u1424");
    private static final ClassDesc CD_ArrayObj      = ClassDesc.of("org.xtclang.ecstasy.collections.Array\u1424Object\u1424");
    private static final ClassDesc CD_nConst     = ClassDesc.of("org.xtclang.ecstasy.nConst");
    private static final ClassDesc CD_nEnum      = ClassDesc.of("org.xtclang.ecstasy.nEnum");
    private static final ClassDesc CD_nException = ClassDesc.of("org.xtclang.ecstasy.nException");
    private static final ClassDesc CD_nFunction  = ClassDesc.of("org.xtclang.ecstasy.nFunction");
    private static final ClassDesc CD_nMethod    = ClassDesc.of("org.xtclang.ecstasy.nMethod");
    private static final ClassDesc CD_nModule    = ClassDesc.of("org.xtclang.ecstasy.nModule");
    private static final ClassDesc CD_nObj       = ClassDesc.of("org.xtclang.ecstasy.nObj");
    private static final ClassDesc CD_nPackage   = ClassDesc.of("org.xtclang.ecstasy.nPackage");
    private static final ClassDesc CD_nRef       = ClassDesc.of("org.xtclang.ecstasy.reflect.nRef");
    private static final ClassDesc CD_nType      = ClassDesc.of("org.xtclang.ecstasy.nType");
    private static final ClassDesc CD_nRangeInt8  = ClassDesc.of("org.xtclang.ecstasy.nRange\u1424Int8\u1424");
    private static final ClassDesc CD_nRangeInt64 = ClassDesc.of("org.xtclang.ecstasy.nRange\u1424Int64\u1424");

    public enum XvmPrimitive {
        // @formatter:off
        Bit         ("Z",  CD_Boolean, "booleanValue",  SLOTS_1,  4,  "0001"),
        Boolean     ("Z",  CD_Boolean, "booleanValue",  SLOTS_1,  4,  "0001"),
        Char        ("C",  CD_Char,    "charValue",     SLOTS_1,  4,  "0002"),
        Nibble      ("I",  CD_Nibble,  "byteValue",     SLOTS_1,  4,  "000F"),
        Int8        ("I",  CD_Int8,    "intValue",      SLOTS_1,  4,  "00FF"),
        UInt8       ("I",  CD_UInt8,   "intValue",      SLOTS_1,  4,  "00FF"),
        Int16       ("I",  CD_Int16,   "intValue",      SLOTS_1,  4,  "FFFF"),
        UInt16      ("I",  CD_UInt16,  "intValue",      SLOTS_1,  4,  "FFFF"),
        Int32       ("I",  CD_Int32,   "intValue",      SLOTS_1,  4,  "FFFFFFFF"),
        UInt32      ("J",  CD_UInt32,  "longValue",     SLOTS_2,  8,  "FFFFFFFF"),
        Int64       ("J",  CD_Int64,   "longValue",     SLOTS_2,  8,  "FFFFFFFFFFFFFFFF"),
        UInt64      ("J",  CD_UInt64,  "longValue",     SLOTS_2,  8,  "FFFFFFFFFFFFFFFF"),
        Float32     ("F",  CD_Float32, "floatValue",    SLOTS_1,  4,  "FFFFFFFF"),
        Float64     ("D",  CD_Float64, "doubleValue",   SLOTS_2,  8,  "FFFFFFFFFFFFFFFF"),
        Dec32       ("I",  CD_Dec32,   "longValue",     SLOTS_1,  8,  "00000000FFFFFFFF"),
        Dec64       ("J",  CD_Dec64,   "longValue",     SLOTS_2,  8,  "FFFFFFFFFFFFFFFF"),
        Int128      ("JJ", CD_Int128,  null,            SLOTS_4, 16,  "128-bit-wide"),
        UInt128     ("JJ", CD_UInt128, null,            SLOTS_4, 16,  "128-bit-wide"),
        Dec128      ("JJ", CD_Dec128,  null,            SLOTS_4, 16,  "128-bit-wide"),
        String      ("Ljava/lang/String;", CD_String,   null,    SLOTS_1,  8,  "ref"),
        Nullable    ("Ljava/lang/Object;", CD_Nullable,  null,    SLOTS_1,  8,  "ref");
        // @formatter:on

        private final String jvmDescriptor;
        private final ClassDesc boxClassDesc;
        private final String unboxMethod;
        private final int slotCount;
        private final int bytesPerSlot;
        private final String simdMask;

        XvmPrimitive(String jvmDescriptor, ClassDesc boxClassDesc, String unboxMethod,
                      int slotCount, int bytesPerSlot, String simdMask) {
            this.jvmDescriptor  = jvmDescriptor;
            this.boxClassDesc   = boxClassDesc;
            this.unboxMethod   = unboxMethod;
            this.slotCount     = slotCount;
            this.bytesPerSlot  = bytesPerSlot;
            this.simdMask      = simdMask;
        }

        public String jvmDescriptor()  { return jvmDescriptor; }
        public ClassDesc boxClassDesc() { return boxClassDesc; }
        public String unboxMethod()    { return unboxMethod; }
        public int slotCount()         { return slotCount; }
        public int bytesPerSlot()      { return bytesPerSlot; }
        public int totalBytes()        { return slotCount * bytesPerSlot; }
        public String simdMask()       { return simdMask; }
        public int avx2Lanes()         { return 32 / bytesPerSlot; }
        public double avx2Speedup()    { return avx2Lanes(); }
    }

    // ── Array type translations ────────────────────────────────────────────

    public enum XvmArray {
        // @formatter:off
        ArrayBoolean  ("[Z",  CD_ArrayBoolean, 1),
        ArrayBit      ("[Z",  CD_ArrayBit,     1),
        ArrayChar     ("[C",  CD_ArrayChar,    2),
        ArrayNibble   ("[I",  CD_ArrayNibble,  1),
        ArrayInt8     ("[I",  CD_ArrayInt8,    1),
        ArrayUInt8    ("[I",  CD_ArrayUInt8,   1),
        ArrayInt16    ("[I",  CD_ArrayInt16,   2),
        ArrayUInt16   ("[I",  CD_ArrayUInt16,  2),
        ArrayInt32    ("[I",  CD_ArrayInt32,   4),
        ArrayUInt32   ("[J",  CD_ArrayUInt32,  4),
        ArrayInt64    ("[J",  CD_ArrayInt64,   8),
        ArrayUInt64   ("[J",  CD_ArrayUInt64,  8),
        ArrayInt128   ("[J",  CD_ArrayInt128,  16),
        ArrayUInt128  ("[J",  CD_ArrayUInt128, 16),
        ArrayFloat32  ("[F",  CD_ArrayFloat32, 4),
        ArrayFloat64  ("[D",  CD_ArrayFloat64, 8),
        ArrayDec32    ("[I",  CD_ArrayDec32,   8),
        ArrayDec64    ("[J",  CD_ArrayDec64,   8),
        ArrayDec128   ("[J",  CD_ArrayDec128,  16),
        ArrayObj      ("[Ljava/lang/Object;", CD_ArrayObj, 8);
        // @formatter:on

        private final String jvmDescriptor;
        private final ClassDesc arrayClassDesc;
        private final int elementBytes;

        XvmArray(String jvmDescriptor, ClassDesc arrayClassDesc, int elementBytes) {
            this.jvmDescriptor  = jvmDescriptor;
            this.arrayClassDesc = arrayClassDesc;
            this.elementBytes   = elementBytes;
        }

        public String jvmDescriptor()    { return jvmDescriptor; }
        public ClassDesc arrayClassDesc() { return arrayClassDesc; }
        public int elementBytes()        { return elementBytes; }
        public int avx2LanesPerVector()  { return 32 / elementBytes; }
        public double avx2Speedup()      { return avx2LanesPerVector(); }
    }

    // ── N-ary types ────────────────────────────────────────────────────────

    public enum XvmNary {
        nConst    (CD_nConst),
        nEnum     (CD_nEnum),
        nException(CD_nException),
        nFunction (CD_nFunction),
        nMethod   (CD_nMethod),
        nModule   (CD_nModule),
        nObj      (CD_nObj),
        nPackage  (CD_nPackage),
        nRef      (CD_nRef),
        nType     (CD_nType),
        nRangeInt8 (CD_nRangeInt8),
        nRangeInt64(CD_nRangeInt64);

        private final ClassDesc classDesc;

        XvmNary(ClassDesc classDesc) { this.classDesc = classDesc; }
        public ClassDesc classDesc() { return classDesc; }
    }

    // ── Vtable options for typedef-mixin compositional contracts ──────────

    /**
     * How a type dispatches through its vtable.
     */
    public enum VtableLayout {
        VIRTUAL,   // standard virtual dispatch
        INTERFACE, // interface method table dispatch
        INLINE,    // inline expanded, no indirection
        BOXED      // boxed wrapper with forwarding
    }

    /**
     * Vtable characteristics for a primitive type used in typedef-mixin
     * composition.  Each field is tuned for Kotlin-compatible vtable
     * generation.
     *
     * @param layout     dispatch strategy
     * @param vtableSlots number of vtable entries (1 for primitives,
     *                    2 for wide, 0 for references)
     * @param mixinCompat bitmask for valid mixin compositions:
     *                    bit 0 (0x01): supports direct field access
     *                    bit 1 (0x02): supports method forwarding
     *                    bit 2 (0x04): supports type unification (union)
     *                    bit 3 (0x08): supports type intersection
     * @param nullSafe   whether the nullable variant has the same vtable layout
     */
    public record VtableOptions(
            VtableLayout layout,
            int vtableSlots,
            byte mixinCompat,
            boolean nullSafe) {}

    // indexed by XvmPrimitive.ordinal()
    private static final VtableOptions[] VTABLE_OPTIONS = {
        /*  0 Bit      */ new VtableOptions(VtableLayout.VIRTUAL,   1, (byte) 0x0F, true),
        /*  1 Boolean  */ new VtableOptions(VtableLayout.VIRTUAL,   1, (byte) 0x0F, true),
        /*  2 Char     */ new VtableOptions(VtableLayout.VIRTUAL,   1, (byte) 0x0F, false),
        /*  3 Nibble   */ new VtableOptions(VtableLayout.VIRTUAL,   1, (byte) 0x0F, false),
        /*  4 Int8     */ new VtableOptions(VtableLayout.VIRTUAL,   1, (byte) 0x0F, false),
        /*  5 UInt8    */ new VtableOptions(VtableLayout.VIRTUAL,   1, (byte) 0x0F, false),
        /*  6 Int16    */ new VtableOptions(VtableLayout.VIRTUAL,   1, (byte) 0x0F, false),
        /*  7 UInt16   */ new VtableOptions(VtableLayout.VIRTUAL,   1, (byte) 0x0F, false),
        /*  8 Int32    */ new VtableOptions(VtableLayout.VIRTUAL,   1, (byte) 0x0F, false),
        /*  9 UInt32   */ new VtableOptions(VtableLayout.VIRTUAL,   2, (byte) 0x0F, false),
        /* 10 Int64    */ new VtableOptions(VtableLayout.VIRTUAL,   2, (byte) 0x0F, false),
        /* 11 UInt64   */ new VtableOptions(VtableLayout.VIRTUAL,   2, (byte) 0x0F, false),
        /* 12 Float32  */ new VtableOptions(VtableLayout.VIRTUAL,   1, (byte) 0x0F, false),
        /* 13 Float64  */ new VtableOptions(VtableLayout.VIRTUAL,   2, (byte) 0x0F, false),
        /* 14 Dec32    */ new VtableOptions(VtableLayout.INLINE,    1, (byte) 0x07, false),
        /* 15 Dec64    */ new VtableOptions(VtableLayout.INLINE,    2, (byte) 0x07, false),
        /* 16 Int128   */ new VtableOptions(VtableLayout.INLINE,    4, (byte) 0x07, false),
        /* 17 UInt128  */ new VtableOptions(VtableLayout.INLINE,    4, (byte) 0x07, false),
        /* 18 Dec128   */ new VtableOptions(VtableLayout.INLINE,    4, (byte) 0x07, false),
        /* 19 String   */ new VtableOptions(VtableLayout.INTERFACE, 0, (byte) 0x0E, true),
        /* 20 Nullable */ new VtableOptions(VtableLayout.BOXED,     1, (byte) 0x0F, true),
    };

    /**
     * Returns the vtable options for the given primitive type.
     */
    public static VtableOptions vtableOptions(XvmPrimitive p) {
        return VTABLE_OPTIONS[p.ordinal()];
    }

    /**
     * Returns the number of vtable slots for the given JVM field descriptor,
     * or 0 if the descriptor does not map to a known primitive.
     */
    public static int vtableSlots(String jvmDescriptor) {
        var p = forDescriptor(jvmDescriptor);
        return p == null ? 0 : VTABLE_OPTIONS[p.ordinal()].vtableSlots();
    }

    /**
     * Returns true if all {@code requiredFlags} bits are set in the
     * mixin-compatibility bitmask for the given primitive type.
     *
     * @param p             the primitive to check
     * @param requiredFlags bitmask of required mixin capabilities
     *                      (0x01=field access, 0x02=method forwarding,
     *                      0x04=type unification, 0x08=type intersection)
     */
    public static boolean mixinCompat(XvmPrimitive p, int requiredFlags) {
        return (VTABLE_OPTIONS[p.ordinal()].mixinCompat() & requiredFlags) == requiredFlags;
    }

    // ── Lookup helpers ─────────────────────────────────────────────────────

    public static XvmPrimitive forName(String fqn) {
        return switch (fqn) {
            case "org.xtclang.ecstasy.Boolean"         -> XvmPrimitive.Boolean;
            case "org.xtclang.ecstasy.text.Char"        -> XvmPrimitive.Char;
            case "org.xtclang.ecstasy.numbers.Bit"      -> XvmPrimitive.Bit;
            case "org.xtclang.ecstasy.numbers.Nibble"    -> XvmPrimitive.Nibble;
            case "org.xtclang.ecstasy.numbers.Int8"     -> XvmPrimitive.Int8;
            case "org.xtclang.ecstasy.numbers.UInt8"    -> XvmPrimitive.UInt8;
            case "org.xtclang.ecstasy.numbers.Int16"    -> XvmPrimitive.Int16;
            case "org.xtclang.ecstasy.numbers.UInt16"   -> XvmPrimitive.UInt16;
            case "org.xtclang.ecstasy.numbers.Int32"    -> XvmPrimitive.Int32;
            case "org.xtclang.ecstasy.numbers.UInt32"   -> XvmPrimitive.UInt32;
            case "org.xtclang.ecstasy.numbers.Int64"    -> XvmPrimitive.Int64;
            case "org.xtclang.ecstasy.numbers.UInt64"   -> XvmPrimitive.UInt64;
            case "org.xtclang.ecstasy.numbers.Int128"   -> XvmPrimitive.Int128;
            case "org.xtclang.ecstasy.numbers.UInt128"  -> XvmPrimitive.UInt128;
            case "org.xtclang.ecstasy.numbers.Dec32"    -> XvmPrimitive.Dec32;
            case "org.xtclang.ecstasy.numbers.Dec64"    -> XvmPrimitive.Dec64;
            case "org.xtclang.ecstasy.numbers.Dec128"   -> XvmPrimitive.Dec128;
            case "org.xtclang.ecstasy.numbers.Float16"  -> XvmPrimitive.Float32;
            case "org.xtclang.ecstasy.numbers.Float32"  -> XvmPrimitive.Float32;
            case "org.xtclang.ecstasy.numbers.Float64"  -> XvmPrimitive.Float64;
            case "org.xtclang.ecstasy.text.String"      -> XvmPrimitive.String;
            case "org.xtclang.ecstasy.Nullable"          -> XvmPrimitive.Nullable;
            default -> null;
        };
    }

    public static XvmPrimitive forDescriptor(String desc) {
        return switch (desc) {
            case "Z" -> XvmPrimitive.Boolean;
            case "C" -> XvmPrimitive.Char;
            case "I" -> XvmPrimitive.Int32;
            case "J" -> XvmPrimitive.Int64;
            case "F" -> XvmPrimitive.Float32;
            case "D" -> XvmPrimitive.Float64;
            default  -> null;
        };
    }
}
