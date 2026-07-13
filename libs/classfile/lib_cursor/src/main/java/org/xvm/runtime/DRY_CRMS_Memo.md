================================================================================
MEMO: DRY Analysis + CRMS Autovectorization Candidates
File: javatools/src/main/java/org/xvm/runtime/ClassfilePointcutRewriter.java
      javatools/src/main/java/org/xvm/runtime/TypedefCascadeTable.java
 javatools/src/main/java/org/xvm/runtime/VmPointcutDispatch.java
================================================================================

DRY — CROSS-FILE DUPLICATIONS
==============================

1. OPcode→Kind dispatch is triplicated
 ────────────────────────────────────
   ClassfilePointcutRewriter.matchesElement (lines 235-257):
     opcode0x10-0x1F → INVOKESTATIC
     opcode 0x20-0x2F → INVOKEVIRTUAL/INTERFACE
     opcode 0x34-0x37 → INVOKESPECIAL <init>
     opcode 0x38-0x3B → NewObject
     opcode 0x4C-0x4F → ReturnInstruction
     opcode 0xA5/0xA6/0xA7/0xA8 → FieldInstruction GET/PUT STATIC/FIELD

   VmPointcutDispatch lines 30-39:
     same4 parallel int[] arrays encoding the same opcode→Kind mapping

   TypedefCascadeTable.routeOpcode (lines 241-254):
     calls VmPointcutDispatch.kindOf() but then re-derives scope locally
     via opcodeToScope() — a second independent opcode→scope mapping

   CURE: VmPointcutDispatch is the canonical owner. ClassfilePointcutRewriter
   should call VmPointcutDispatch.kindOf(opcode) instead of re-implementing
   the range checks. TypedefCascadeTable.routeOpcode should NOT have its own
   opcodeToScope() — scope should come from the dispatcher's BEFORE_TABLE
   or a shared constant in VmPointcutDispatch.

2. Rule metadata is two separate records
   ─────────────────────────────────────
   ClassfilePointcutRewriter.PointcutSite (lines 49-71):
     int opcode, String method, int addr, String desc, boolean replace,
     String bridgeOwner, String bridgeName, MethodTypeDesc bridgeDesc

   TypedefCascadeTable.appendRule parameters:
     int opcode, int siteOrd, byte depth, byte kind
     (rule_opcodes[], rule_siteOrd[], rule_depth[], rule_kind[] SoA)

   The "rule" concept is the same domain object in both files but there is
   no shared interface or base record. The ClassfilePointcutRewriter carries
   far more detail (method name, descriptor, bridge) while the cascade table
   only carries the dispatch metadata (opcode, siteOrd, depth, kind).

   CURE: Extract a common `PointcutRule` interface:
     interface PointcutRule {
       int opcode();
       int siteOrd();
       byte depth();
       byte kind();
     }
 Then TypedefCascadeTable operates on PointcutRule[], and
   ClassfilePointcutRewriter.PointcutSite extends PointcutRule with the
   bridge metadata.

3. simpleMethodName extraction is ad-hoc
   ──────────────────────────────────────
   ClassfilePointcutRewriter.indexByMethod (lines 267-274) + simpleMethodName
   (lines 276-282) strip FQCN to bare method name for grouping.
   This is not shared with any other method-name parsing in the codebase.

   CURE: Push into VmPointcutDispatch or a shared Util class.

================================================================================

CRMS — AUTOVECTORIZATION CANDIDATES
====================================

The cascade lattice has 3 scalar loops that are the SIMD bottleneck.
All three are "embarrassingly parallel" (independent lane evaluation):

┌─────────────────────────────────────────────────────────────────────────────┐
│ CANDIDATE 1: reduce() — depth/kind/scope histogram accumulation │
│ File: TypedefCascadeTable.java:165-180 │
│                                                                 │
│ Current: 3 sequential scalar loops over n rows                            │
│   for (int i = 0; i < n; i++) depthHistogram[depth[i]]++                  │
│   for (int i = 0; i < n; i++) kindHistogram[kind[i]]++ │
│   for (int i = 0; i < n; i++) scopeHistogram[scope[i]]++                  │
│                                                                             │
│ SIMD target: vpaddd on32-lane AVX2 (or 64-lane AVX512)                  │
│   Each lane increments one bucket. vpaddd is32 increments/cycle.        │
│   3 histograms × 32 lanes = 96 bucket increments/cycle                   │
│                                                                             │
│ Lattice significance: depthHistogram[depth] is the PRIMARY sorting key    │
│   for cascade rollup in Blackboard. The reduce() output                  │
│   IS the dominant query centralization signal.                             │
│                                                                             │
│ Auto-vectorization: JDK C2 SHOULD auto-vectorize this if arrays are       │
│  32-byte aligned. Check with -XX:PrintOptoAssembly after -Xcompile. │
│   If not auto-vectorizing: manually unroll with Arrays.stream().map()      │
│   or use VarHandle.fullFence() before the loop to hint alignment.         │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ CANDIDATE 2: matchRule() — opcode gather │
│ File: TypedefCascadeTable.java:204-211 │
│                                                                             │
│ Current: scalar linear scan over ruleCount entries │
│   for (int i = 0; i < ruleCount; i++) │
│     if (rule_opcodes[i] == opcode) return rule_siteOrd[i];               │
│                                                                             │
│ SIMD target: two-phase │
│   Phase 1 (SIMD): vpcmpeqd on opcode[] → bitmask of matching lanes      │
│   Phase 2 (scalar): countTrailingZeros on bitmask → first match index    │
│                                                                             │
│   _mm256_cmpeq_epi32 lanes: 8 lanes × 4 bytes = 32 bytes/cycle           │
│   With ruleCount=73 (one per TypedefCallsite): 73/8 = 9.1 AVX ops │
│   vs73 scalar comparisons = 73 cycles. 8× speedup.                       │
│                                                                             │
│ Lattice significance: matchRule is the column-router for the dispatch │
│   tier. Every pointcut event must route through this match before the │
│   correct AdjacentRule is found. This is the hot path in the firehose.    │
│                                                                             │
│ Implementation:                                                            │
│   int matchRule(int opcode) {                                              │
│     long mask = 0;                                                         │
│     for (int i = 0; i < ruleCount; i += 8) {                             │
│       var v = _mm256_loadu_si256(&rule_opcodes[i]);                       │
│       var cmp = _mm256_cmpeq_epi32(v, _mm256_set1_epi32(opcode));         │
│       mask |= (_mm256_movemask_epi8(cmp)& 0xFFFFFFFF); │
│     }                                                                      │
│     return mask == 0 ? -1 : countTrailingZeros(mask);                     │
│   } │
│                                                                             │
│   Note: for small ruleCount (<16), scalar is faster due to setup cost.   │
│   Use SIMD only when ruleCount >= 16.                                     │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ CANDIDATE 3: matchRuleCount() — kind-filtered gather                       │
│ File: TypedefCascadeTable.java:217-225 │
│                                                                             │
│ Current: scalar double-predicate scan │
│   for (int i = 0; i < ruleCount; i++)                                     │
│     if (rule_opcodes[i] == opcode && rule_kind[i] == kindFilter)          │
│       count++;                                                             │
│                                                                             │
│ SIMD target: combine opcode match mask AND kind match mask                │
│   via _mm256_and_si256 before counting set bits.                           │
│                                                                             │
│   var opcodeMask = _mm256_cmpeq_epi32(_mm256_loadu_si256(&rule_opcodes[i]),\n
│                                        _mm256_set1_epi32(opcode));\n
│   var kindMask   = _mm256_cmpeq_epi32(_mm256_loadu_si256(&rule_kind[i]),\n
│                                        _mm256_set1_epi32(kindFilter));\n
│   var combined = _mm256_and_si256(opcodeMask, kindMask);\n
│   count += _mm_popcnt_u32(_mm256_movemask_epi8(combined));                  │
│                                                                             │
│ Lattice significance: this is the weight/cofindence metric for the │
│   dominant query centralization. matchRuleCount(opcode, kind) gives the    │
│   number of AdjacentRules that match a given (opcode, kind) pair.          │
│   The kind histogram (KH) is the strength signal for SIMD typedef │
│   prioritization.                                                           │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ CANDIDATE 4: opcodeToScope — branchless computation │
│ File: TypedefCascadeTable.java (inline in routeOpcode, lines 248)         │
│                                                                             │
│ Current: 4 chained if-else branches                                       │
│   byte scopeByte;                                                          │
│   if (opcode >= 0x10 && opcode <= 0x1F) scopeByte = SCOPE_METHOD;          │
│   else if (opcode >= 0x20 && opcode <= 0x2F) scopeByte = SCOPE_METHOD;    │
│   else if (opcode >= 0x34 && opcode <= 0x37) scopeByte = SCOPE_CLASS;    │
│   else if (opcode >= 0x38 && opcode <= 0x3B) scopeByte = SCOPE_CLASS;    │
│   else if (opcode >= 0x4C && opcode <= 0x4F) scopeByte = SCOPE_METHOD;    │
│   else if (opcode >= 0xA5 && opcode <= 0xA8) scopeByte = SCOPE_CLASS;     │
│   else scopeByte = SCOPE_MODULE;                                          │
│                                                                             │
│ SIMD target: none needed (this is a single opcode lookup) │
│ CRMS value: branchless version using bit operations:                       │
│                                                                             │
│   byte scopeFromOpcode(int opcode) {                                       │
│     // Bit patterns from VmPointcutDispatch opcode→scope mapping          │
│     //0x10-0x1F, 0x20-0x2F, 0x4C-0x4F → METHOD (bits 0b0110)            │
│     // 0x34-0x3B, 0xA5-0xA8           → CLASS (bits 0b0101)              │
│     // else → MODULE (bits 0b0000)              │
│     int methodMask = ((opcode - 0x10) & 0xFF) <= 0x0F ? 1 :               │
│                       ((opcode - 0x20) & 0xFF) <= 0x0F ? 1 :              │
│                       ((opcode - 0x4C) & 0xFF) <= 0x03 ? 1 : 0;          │
│     int classMask  = ((opcode - 0x34) & 0xFF) <= 0x03 ? 1 :                │
│                       ((opcode - 0x38) & 0xFF) <= 0x03 ? 1 :              │
│                       ((opcode - 0xA5) & 0xFF) <= 0x03 ? 1 : 0;            │
│     return (byte) (methodMask * SCOPE_METHOD | classMask * SCOPE_CLASS);  │
│   }                                                                        │
│                                                                             │
│ Lattice significance: scope is the SECONDARY cascade rollup key.           │
│   CLASS-scope typedefs dominate MODULE-scope in column priority.          │
│   This computation runs per-row in the cascade table, so branchless       │
│   form eliminates4 branch mispredictions per row.                          │
└─────────────────────────────────────────────────────────────────────────────┘

================================================================================

VERIFICATION
============

To verify auto-vectorization is happening:
  ./gradlew javatools:build
  java -XX:+UnlockDiagnosticVMOptions \
       -XX:CompileCommand=print,*TypedefCascadeTable.reduce* \
       -XX:+PrintOptoAssembly \
       -cp javatools/build/classes/java/main:... \
       org.junit.platform.console.ConsoleLauncher \
 --select-class=org.xvm.runtime.TypedefCascadeParityTest

Look for:
  vpaddd  (vectorized histogram increment)
  vmovdqu (vector load)
  vpmaskmovd or gather hints (matchRule SIMD path)

If NOT vectorizing:
  - Check array alignment: Arrays.requireAligned() or manual padding
  - Add -XX:AlignVector=32
  - Force with VarHandle.writeAcquireFence() before hot loop

================================================================================
