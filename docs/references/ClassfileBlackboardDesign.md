# Classfile Blackboard: Pointcut Taxonomy as Animated Algebra

## Thesis

The JVM classfile format is a `FacetedRow<CFK<*>>` — a tagged union over constant-pool keys, field descriptors, method signatures, and attributes — and field pointcut events (`FieldSynapse`) are `Cursor<FieldSynapse>` — a time-indexed sequence of `BEFORE/AFTER` phase pairs that squeeze and animate the classfile taxonomy.

```
ClassFile = FacetedRow<CFK<*>>
           ├── CPK<*>     — constant pool (indexed by cp_index)
           ├── FieldK<*>  — field_info[] (tagged by access_flags + name_index)
           ├── MethodK<*> — method_info[] (tagged by access_flags + name_index)
           ├── AttrK<*>   — attributes[] (tagged by attr_name_index)
           └── Head       — magic, version, this_class, super_class, access_flags

FieldSynapse = Cursor<FieldSynapse>          ← 24-byte wireproto records
             = Series<FieldSynapse>          ← index = seq
             └── phase: BEFORE(0) | AFTER(1)
                 opcode: L_GET(0xA5) | L_SET(0xA6) | P_GET(0xA7) | P_SET(0xA8)
```

**Squeeze**: FieldSynapse projects a classfile down to its field-access subspace — only the fields touched by a particular execution trace.

**Animate**: Phase pairs (BEFORE, AFTER) track state mutation over time — before a `P_SET`, after a `P_SET` — enabling time-travel diffs over the classfile taxonomy.

---

## Taxonomy: ClassFile as FacetedRow

```kotlin
// ── CFK: ClassFile key family ────────────────────────────────────────────────
//
// All keys under the same OpK root. Matches ColK / TextK pattern exactly.
// New facets are new subclasses, not modifications to existing ones.

sealed class CFK<out R> : OpK<R>() {

    // ── Constant Pool Facets ────────────────────────────────────────

    data class CpTag(val index: Int)              : CFK<CP_TAG>()
    data class CpUtf8(val index: Int)             : CFK<String>()
    data class CpInt(val index: Int)              : CFK<Int>()
    data class CpFloat(val index: Int)            : CFK<Float>()
    data class CpLong(val index: Int)             : CFK<Long>()
    data class CpDouble(val index: Int)           : CFK<Double>()
    data class CpClass(val index: Int)            : CFK<CharSequence>()   // indexes CpUtf8
    data class CpString(val index: Int)           : CFK<CharSequence>()   // indexes CpUtf8
    data class CpFieldRef(val index: Int)        : CFK<Join<CharSequence, CharSequence>>() // class × name
    data class CpMethodRef(val index: Int)       : CFK<Join<CharSequence, CharSequence>>()
    data class CpInterfaceMethodRef(val index: Int) : CFK<Join<CharSequence, CharSequence>>()
    data class CpNameAndType(val index: Int)      : CFK<Join<CharSequence, CharSequence>>() // name × descriptor

    // ── Class Header Facets ──────────────────────────────────────────

    data object Magic                              : CFK<Int>()     // 0xCAFEBABE
    data object MinorVersion                       : CFK<Int>()
    data object MajorVersion                       : CFK<Int>()
    data object ThisClass                          : CFK<Int>()     // cp_index → CpClass
    data object SuperClass                         : CFK<Int>()     // cp_index → CpClass
    data object AccessFlags                        : CFK<Int>()

    // ── Collection Facets ───────────────────────────────────────────

    data object ConstantPoolSize                   : CFK<Int>()
    data object FieldCount                          : CFK<Int>()
    data class FieldInfo(val index: Int)            : CFK<FieldInfoRow>()
    data object MethodCount                         : CFK<Int>()
    data class MethodInfo(val index: Int)          : CFK<MethodInfoRow>()
    data object AttributeCount                      : CFK<Int>()
    data class Attribute(val index: Int)           : CFK<AttributeRow>()

    // ── Nested Facets ───────────────────────────────────────────────

    data class FieldByName(val name: CharSequence) : CFK<FieldInfoRow>()
    data class MethodByName(val name: CharSequence) : CFK<MethodInfoRow>()
    data class FieldBySlot(val slot: Int)           : CFK<FieldInfoRow>()
    data class MethodBySlot(val slot: Int)          : CFK<MethodInfoRow>()
    data class AttributeByName(val name: CharSequence) : CFK<AttributeRow>()

    // ── Synthetic Facets (computed, not stored) ────────────────────

    data object ThisClassName                      : CFK<CharSequence>()   // resolved: CpClass → CpUtf8
    data object SuperClassName                     : CFK<CharSequence>()
    data object InterfaceCount                     : CFK<Int>()
    data object SourceFile                         : CFK<CharSequence>()    // from SourceFile attr
    data object InnerClasses                       : CFK<Series<InnerClassRow>>()
    data object RuntimeVisibleAnnotations          : CFK<Series<AnnotationRow>>()
    data object EnclosingMethod                    : CFK<Join<CharSequence, CharSequence>>()
}

// ── Compound rows (typed sub-documents) ────────────────────────────────────────

data class FieldInfoRow(
    val accessFlags: Int,
    val nameIndex: Int,        // → CpUtf8
    val descriptorIndex: Int,  // → CpUtf8  (e.g. "I", "Ljava/lang/String;")
    val attributesCount: Int,
    val attributeIndex: Series<Int>,  // → Attribute[]
) {
    val isStatic: Boolean get() = (accessFlags and 0x0008) != 0
    val isFinal: Boolean get() = (accessFlags and 0x0010) != 0
    val isPrivate: Boolean get() = (accessFlags and 0x0002) != 0
    val isPublic: Boolean get() = (accessFlags and 0x0001) != 0
}

data class MethodInfoRow(
    val accessFlags: Int,
    val nameIndex: Int,
    val descriptorIndex: Int,  // → CpUtf8  (e.g. "(II)V", "(Ljava/lang/String;)I")
    val attributesCount: Int,
    val attributeIndex: Series<Int>,
    val codeAttribute: CodeAttributeRow? = null,  // synthesized from Code attr
)

data class AttributeRow(
    val nameIndex: Int,        // → CpUtf8
    val length: Int,
    val info: Series<Byte>,    // raw bytes
) {
    companion object {
        // Common attribute name indices (from CpUtf8)
        const val CODE            = 0
        const val LINE_NUMBER_TABLE = 1
        const val SOURCE_FILE     = 2
        const val CONSTANT_VALUE  = 3
        const val STACK_MAP_TABLE = 4
        const val BOOTSTRAP       = 5
        const val INNER_CLASSES   = 6
        const val ENCLOSING_METHOD = 7
        const val RUNTIME_VISIBLE_ANNOTATIONS = 8
    }
}

data class CodeAttributeRow(
    val maxStack: Int,
    val maxLocals: Int,
    val codeLength: Int,
    val code: Series<Byte>,           // bytecode bytes
    val exceptionTableLength: Int,
    val exceptionTable: Series<ExceptionTableRow>,
    val attributesCount: Int,
)

data class ExceptionTableRow(
    val startPc: Int,
    val endPc: Int,
    val handlerPc: Int,
    val catchType: Int,   // 0 = any, else cp_index → CpClass
)

data class InnerClassRow(
    val innerClassInfoIndex: Int,   // cp_index → CpClass
    val outerClassInfoIndex: Int,   // cp_index → CpClass (0 if not member)
    val innerNameIndex: Int,        // cp_index → CpUtf8 (0 if anonymous)
    val innerClassAccessFlags: Int,
)

data class AnnotationRow(
    val typeIndex: Int,             // cp_index → CpUtf8  (e.g. "Ljavax/inject/Named;")
    val elementValuePairsCount: Int,
    val elementValuePairs: Series<Join<CharSequence, ElementValue>>,
)

sealed class ElementValue {
    data class Const(val tag: Byte, val cpIndex: Int) : ElementValue()
    data class EnumConst(val typeNameIndex: Int, val constNameIndex: Int) : ElementValue()
    data class Class(val classInfoIndex: Int) : ElementValue()
    data class Annotation(val annotation: AnnotationRow) : ElementValue()
    data class Array(val values: Series<ElementValue>) : ElementValue()
}
```

---

## Phase Model: FieldSynapse as Cursor of Classfile States

The `FieldSynapse` wireproto maps directly onto the classfile field subspace:

```kotlin
// Wireproto (24 bytes, little-endian):
//   offset  0: opcode       u8     — 0xA5=L_GET, 0xA6=L_SET, 0xA7=P_GET, 0xA8=P_SET
//   offset  1: phase        u8     — 0=BEFORE, 1=AFTER
//   offset  2: methodIdx    u16    — InternPool index (→ CpMethodRef)
//   offset  4: addr         i32    — bytecode PC address
//   offset  8: seq          i32    — monotonic sequence (cursor index)
//   offset 12: nano         i64    — System.nanoTime() at publish
//   offset 20: callsiteHash u16   — FNV-1a of (opcode, methodIdx, addr)
//   offset 22: templateIdx  u16    — InternPool index of format template

data class FieldSynapse(
    val phase: Byte,         // 0=BEFORE, 1=AFTER
    val opcode: Byte,       // 0xA5=L_GET, 0xA6=L_SET, 0xA7=P_GET, 0xA8=P_SET
    val methodIdx: Int,      // InternPool index → CpMethodRef
    val addr: Int,           // PC address
    val seq: Int,           // cursor index
    val nano: Long,         // wall time
    val callsiteHash: Int,  // callsite fingerprint
    val templateIdx: Int,   // template selector
) {
    companion object {
        const val TPL_BEFORE_GET = 0
        const val TPL_AFTER_GET  = 1
        const val TPL_BEFORE_SET = 2
        const val TPL_AFTER_SET  = 3

        const val OP_L_GET: Byte = 0xA5
        const val OP_L_SET: Byte = 0xA6
        const val OP_P_GET: Byte = 0xA7
        const val OP_P_SET: Byte = 0xA8
    }

    val isStatic: Boolean get() = opcode == OP_L_GET || opcode == OP_L_SET
    val isPolymorphic: Boolean get() = opcode == OP_P_GET || opcode == OP_P_SET
    val isSet: Boolean get() = opcode == OP_L_SET || opcode == OP_P_SET
    val isGet: Boolean get() = opcode == OP_L_GET || opcode == OP_P_GET
    val isBefore: Boolean get() = phase == 0.toByte()
    val isAfter: Boolean get() = phase == 1.toByte()
}
```

**4 × 2 = 8 distinct operation shapes**, each with its own wireproto template:

| Opcode | Phase | Template | Canonical shape |
|--------|-------|----------|-----------------|
| L_GET | BEFORE | TPL_BEFORE_GET | observe static field before read |
| L_GET | AFTER | TPL_AFTER_GET | observe static field after read |
| L_SET | BEFORE | TPL_BEFORE_SET | observe static field before write |
| L_SET | AFTER | TPL_AFTER_SET | observe static field after write |
| P_GET | BEFORE | TPL_BEFORE_GET | observe instance field before read |
| P_GET | AFTER | TPL_AFTER_GET | observe instance field after read |
| P_SET | BEFORE | TPL_BEFORE_SET | observe instance field before write |
| P_SET | AFTER | TPL_AFTER_SET | observe instance field after write |

---

## Squeeze: Projecting ClassFile → FieldSynapse subspace

The squeeze maps a `ClassFile` + a `Cursor<FieldSynapse>` into a **filtered classfile view**:

```kotlin
// SqueezedClassFile: classfile projected onto field-synapse events
data class SqueezedClassFile(
    val classFile: ClassFile,                  // original (immutable snapshot)
    val synapses: Cursor<FieldSynapse>,        // field events for this trace
    val fieldProjection: Series<Int>,           // which field slots were touched
    val methodProjection: Series<Int>,          // which methods contain touch sites
)

// Project a classfile to its field-access subspace
fun ClassFile.squeeze(synapses: Cursor<FieldSynapse>): SqueezedClassFile {
    // Collect touched field slots from all P_SET / P_GET / L_SET / L_GET
    val fieldSlots = mutableSetOf<Int>()
    val methodSlots = mutableSetOf<Int>()

    synapses.view.forEach { synapse ->
        // methodIdx → method slot (reverse-resolve from CpMethodRef)
        methodSlots.add(resolveMethodSlot(synapse.methodIdx))
        // addr → field slot (resolve from bytecode at addr)
        val fieldSlot = resolveFieldSlot(synapse.addr)
        if (fieldSlot >= 0) fieldSlots.add(fieldSlot)
    }

    return SqueezedClassFile(
        classFile = this,
        synapses = synapses,
        fieldProjection = fieldSlots.toList().sorted().series(),
        methodProjection = methodSlots.toList().sorted().series(),
    )
}

// Resolve bytecode addr → field slot
// Walks the method's CodeAttribute, resolves aload_0 / aload_N / getfield / putfield / getstatic / putstatic
fun ClassFile.resolveFieldSlot(addr: Int): Int {
    val method = methodContaining(addr) ?: return -1
    val code = method.codeAttribute ?: return -1
    return resolveFieldSlotFromBytecode(code.code, addr)
}
```

---

## Animate: Phase pairs as time-indexed classfile states

The phase pairs enable **state diffing** over the classfile:

```kotlin
// Cursor<FieldSynapse> indexed by seq
// Group by (opcode, methodIdx, addr) to get BEFORE/AFTER pairs
fun Cursor<FieldSynapse>.phasePairs(): Series<Join<FieldSynapse, FieldSynapse>> {
    // Group by callsite
    val grouped = this groupBy { it.callsiteHash }
    return grouped.size j { i ->
        val pair = grouped.b(i)
        // pair[0] = BEFORE (phase=0), pair[1] = AFTER (phase=1)
        pair[0] j pair[1]
    }
}

// Compute field mutation delta between BEFORE and AFTER
data class FieldDelta(
    val fieldInfo: FieldInfoRow,      // which field changed
    val beforeValue: Any?,             // value before P_SET / L_SET
    val afterValue: Any?,             // value after P_SET / L_SET
    val deltaNano: Long,              // time between phases
    val owner: CharSequence,           // class name
)

// Build a time-series of field mutations from a trace
fun Cursor<FieldSynapse>.toFieldDeltas(): Series<FieldDelta> {
    val pairs = phasePairs()
    return pairs.size j { i ->
        val (before, after) = pairs.b(i)
        val fieldInfo = classFile[CFK.FieldBySlot(resolveFieldSlot(after.addr))]
        val owner = classFile[CFK.ThisClassName]
        FieldDelta(
            fieldInfo = fieldInfo,
            beforeValue = extractValue(before),
            afterValue = extractValue(after),
            deltaNano = after.nano - before.nano,
            owner = owner,
        )
    }
}

// Render the delta as a RowVec (for Cursor composition)
fun FieldDelta.toRowVec(): RowVec = 5 j { col ->
    when (col) {
        0 -> this j { fieldInfo.name }           // field name
        1 -> this j { fieldInfo.descriptor }      // type
        2 -> this j { beforeValue }              // before
        3 -> this j { afterValue }               // after
        4 -> this j { deltaNano }                // duration
    }
}
```

---

## Blackboard: Composing ClassFile + FieldSynapse + Cursor

The `BlackboardOverlay` pattern extends to the classfile domain:

```kotlin
// Classfile blackboard: read from classfile, write to trace
data class ClassFileBlackboard(
    val surface: ClassFile,         // immutable classfile snapshot
    val sense: Cursor<FieldSynapse>, // field event trace
) : Join<ClassFile, Cursor<FieldSynapse>> {
    override val a: ClassFile = surface
    override val b: Cursor<FieldSynapse> = sense
}

// Facet routing for classfile blackboard
sealed class ClassK<out R> : OpK<R>() {
    // Classfile facets (read from surface)
    data object Fields                    : ClassK<Series<FieldInfoRow>>()
    data class Field(val name: CharSequence) : ClassK<FieldInfoRow>()
    data object Methods                   : ClassK<Series<MethodInfoRow>>()
    data class Method(val name: CharSequence) : ClassK<MethodInfoRow>()
    data object ConstantPool              : ClassK<Series<Any>>()

    // Trace facets (read from sense)
    data object TracedFields              : ClassK<Series<FieldInfoRow>>()  // squeezed
    data object FieldDeltas              : ClassK<Series<FieldDelta>>()    // animated
    data object TraceCoverage             : ClassK<Double>()                // % fields touched
    data object HotFields                : ClassK<Series<Join<CharSequence, Int>>>() // field × hitcount
    data object CallGraph                 : ClassK<Cursor<MethodGraphRow>>()

    // Composite facets (read from both)
    data class FieldSnapshot(val slot: Int) : ClassK<Join<Any?, Any?>>()  // before j after
    data class MethodCoverage(val name: CharSequence) : ClassK<Double>()
}

// Blackboard operations
fun ClassFileBlackboard.read(key: ClassK<*>): Any? = when (key) {
    is ClassK.Fields -> surface.allFields()
    is ClassK.Field -> surface.fieldByName(key.name)
    is ClassK.TracedFields -> sense.toFieldDeltas().map { it.fieldInfo }.distinct()
    is ClassK.FieldDeltas -> sense.toFieldDeltas()
    is ClassK.TraceCoverage -> {
        val touched = sense.toFieldDeltas().map { it.fieldInfo }.distinct()
        touched.size.toDouble() / surface.fields.size
    }
    is ClassK.HotFields -> sense.groupBy { resolveFieldSlot(it.addr) }
        .map { slot j it.value.size }
        .sortedByDescending { it.b }
    is ClassK.FieldSnapshot -> {
        val delta = sense.toFieldDeltas().find { it.fieldInfo.slot == key.slot }
        delta?.beforeValue j delta?.afterValue
    }
    else -> null
}

// Convert blackboard to Cursor for further algebra
fun ClassFileBlackboard.toCursor(): Cursor {
    val deltas = sense.toFieldDeltas()
    val meta: Series<ColumnMeta> = 5 j { i ->
        when (i) {
            0 -> ColumnMeta("field", IOMemento.IoString)
            1 -> ColumnMeta("type", IOMemento.IoString)
            2 -> ColumnMeta("before", IOMemento.IoString)
            3 -> ColumnMeta("after", IOMemento.IoString)
            4 -> ColumnMeta("duration_ns", IOMemento.IoLong)
            else -> error("unreachable")
        }
    }
    val rows: Series<RowVec> = deltas.size j { i ->
        deltas[i].toRowVec()
    }
    return rows
}
```

---

## Taxonomy Summary

```
ClassFileBlackboard
  ├── surface: ClassFile = FacetedRow<CFK<*>>
  │     ├── CPK<*>        → constant pool (indexed by cp_index)
  │     ├── FieldK<*>     → fields[] (tagged by slot + name)
  │     ├── MethodK<*>    → methods[] (tagged by slot + name)
  │     ├── AttrK<*>      → attributes[] (tagged by name_index)
  │     └── Head          → magic, version, access_flags
  │
  └── sense: Cursor<FieldSynapse> = Series<FieldSynapse>
        ├── seq             → cursor index
        ├── phase           → BEFORE(0) | AFTER(1)
        ├── opcode          → L_GET(0xA5) | L_SET(0xA6) | P_GET(0xA7) | P_SET(0xA8)
        ├── methodIdx       → InternPool index → CpMethodRef
        ├── addr            → PC address → field slot
        └── nano            → wall time

Squeeze:     ClassFile × Cursor<FieldSynapse> → SqueezedClassFile
             (project classfile to traced field subspace)

Animate:     Cursor<FieldSynapse>             → Series<Join<FieldDelta, FieldDelta>>
             (pair BEFORE/AFTER phases into time-indexed deltas)

Render:      Series<FieldDelta>               → Cursor<RowVec>
             (delta rows → blackboard view → table / graph / flame chart)
```

---

## Wireproto to ClassFile (JVM)

The `FieldSynapse` wireproto maps onto the classfile through these resolvers:

| Wireproto field | Resolves to | ClassFile facet |
|---|---|---|
| `methodIdx` | InternPool[index] → CpMethodRef | `CFK.MethodInfo(slot)` |
| `addr` | Bytecode[addr] → field slot | `CFK.FieldBySlot(slot)` |
| `callsiteHash` | FNV-1a(opcode, methodIdx, addr) | `ClassK.HotFields` grouping |
| `templateIdx` | TPL_BEFORE_GET / _AFTER / _BEFORE_SET / _AFTER_SET | phase pair template |
| `seq` | cursor index | `Cursor<FieldSynapse>` index |

---

## Implementation Notes

1. **Bytecode resolution**: `resolveFieldSlotFromBytecode` walks `CodeAttribute.code` at `addr`, decoding `getfield(0xB4)`, `putfield(0xB5)`, `getstatic(0xB2)`, `putstatic(0xB3)`, `aload_0` before field op, extracting the `constant_pool_index` operand.

2. **Constant pool dereferencing**: `CpMethodRef(index)` → `CpNameAndType(descriptorIndex)` → `(name: CpUtf8, descriptor: CpUtf8)`. The deref chain is `CFK.CpMethodRef → CFK.CpNameAndType → CFK.CpUtf8`.

3. **Ring buffer backend**: `RingSeries<FieldSynapse>` (per `SynapseRing.jvm.kt`) is the hot-path journal. `ReduxMutableSeries.clear()` does NOT reset cached state — use journal recreation for singleton resets.

4. **Hot field ranking**: `series α { fieldSlot j hitCount }` sorted descending by hitCount gives the flame-graph ordering for visualization.

5. **Attribute synthesis**: `CodeAttributeRow`, `InnerClassRow`, `AnnotationRow` are synthesized from `CFK.Attribute(i)` raw bytes — not stored directly, computed on demand via `CFK.AttrK<*>` facet.
