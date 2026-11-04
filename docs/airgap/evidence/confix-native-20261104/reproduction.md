# Bounded native Confix audit

150,026 scanner invocations completed without ASan/UBSan findings, guard changes, outstanding wrapper allocations, or batch timeouts. This is bounded native evidence; it is not exhaustive memory-safety proof or a Sulong/airgap execution test.

## Pinned inputs

Repository snapshot paths:

- `src/jvmMain/llvm/confix/bridge.c`: `c5900490c3c1c4660cf4c8680eafb3b895ac44a60bcc53da4ed42972f9355ad1`
- `src/jvmMain/llvm/confix/confix.ll`: `7ba80066f3a8a1775f4a8390d38a8f347c498e5df560bb51b7c18c2fe4de2bc8`

These hashes were checked against the current repository files after testing. Exact copies are retained as `bridge-snapshot.c` and `scanner-original.ll`. `report.json` includes harness and compressed-log hashes. No repository files were edited by this audit.

For a new checkout, create a separate audit working directory. Copy this packet's
`harness.c`, `bridge-snapshot.c` and `NumericOracle.java` there; copy the pinned
repository `src/jvmMain/llvm/confix/confix.ll` as `scanner-original.ll`. Verify
the hashes above before running the recorded commands. The original machine
paths below identify the actual run; substitute the corresponding staged SDK
and audit directory for a new reproduction. Full random-case logs remain in
the original workspace and are identified by hashes in the report.

## Toolchain and instrumentation

LLVM SDK: `/workspace/toolchains/LLVM-22.1.8-Linux-X64/bin`, version 22.1.8. GCC: Debian 14.2.0-19, with its ASan and UBSan runtimes. Runtime numeric oracle: GraalVM CE 25.3.4.1 (JDK 25.0.4.1) and Kotlin stdlib 2.4.21.

The scanner IR does not originally carry `sanitize_address` attributes. Merely passing `-fsanitize=address` to clang while compiling the original IR adds module initialization without instrumenting scanner loads/stores. The audit adds this attribute to both scanner function attribute groups in an external copy, then runs the ASan LLVM pass. An explicit Linux target is required so instrumentation uses the runtime's shadow mapping.

```sh
cd /workspace/confix-native-audit
SDK=/workspace/toolchains/LLVM-22.1.8-Linux-X64/bin
python3 - <<'PYCODE'
from pathlib import Path
s = Path('scanner-original.ll').read_text()
s = s.replace('attributes #0 = { nounwind',
              'attributes #0 = { sanitize_address nounwind')
s = s.replace('attributes #1 = { alwaysinline nounwind',
              'attributes #1 = { sanitize_address alwaysinline nounwind')
Path('scanner-asan.ll').write_text(s)
PYCODE
"$SDK/opt" -mtriple=x86_64-unknown-linux-gnu -passes=asan \
  -S scanner-asan.ll -o scanner-instrumented.ll
"$SDK/clang" -O1 -c scanner-instrumented.ll -o scanner-asan.o
gcc -std=c11 -O1 -g -fsanitize=address,undefined \
  -fno-omit-frame-pointer -fno-pie -no-pie \
  harness.c scanner-asan.o -o audit
nm scanner-asan.o > scanner-symbols.log
```

The object references `__asan_report_load1`, `load4`, `load8`, `store1`, `store4`, and `store8`. The instrumented IR contains 121 report-call/declaration references. The wrapper and harness have GCC ASan/UBSan coverage; the scanner has ASan coverage, not a claim of general LLVM UB instrumentation.

The bridge uses one calloc allocation. To detect accesses crossing logical array boundaries inside that allocation, the harness poisons each preceding/following guard and all alignment padding after each logical array. It checks guard values after each scan and checks `confix_live_bytes()==0` after every close.

## Executed cases

```sh
export ASAN_OPTIONS=detect_leaks=1:halt_on_error=1
export UBSAN_OPTIONS=halt_on_error=1
timeout 30s ./audit fixtures > fixtures.log 2>&1
timeout 30s ./audit fuzz 30000 2180457747 > fuzz.log 2>&1
timeout 30s ./audit fuzz 90000 947126353 > fuzz-second.log 2>&1
timeout 30s ./audit structured 30000 > structured.log 2>&1
timeout 30s ./audit max > max.log 2>&1
```

- 23 exact fixture/geometry/fault cases: empty inputs in all formats; complete spans/tags/depths/ordered children for JSON, CBOR, and simple YAML; exact capacity and capacity exhaustion; Unicode, escaping, malformed JSON; CRLF/comments/inline YAML; all byte values; and CBOR faults 1 through 4.
- 120,000 deterministic randomized inputs, distributed equally among JSON/CBOR/YAML. Inputs have 0..191 bytes; rows 1..256. The generator uses xorshift32 with the command-line seeds above; CBOR uses arbitrary bytes, text scans use a biased grammar alphabet with periodic arbitrary-byte cases.
- 30,000 structured YAML mutations, xorshift32 seed `0x81f72513` (2180457747). Seeds include nested maps/sequences/flows, quoted delimiters, repeated blank lines, comments, CRLF, and Unicode; each gets 0..5 grammar-byte mutations and row capacity 1..256.
- 3 maximum-size cases: 65,536 opening JSON brackets with 16,384 rows => Room; 65,536 newlines in YAML => success; 65,536 nested CBOR array headers with 16,384 rows => Room.

Known nonprogress cases were run only in native code: CBOR bytes `5b00000000fffffff7` and YAML `- # comment: value`. Both returned fault 4. The potentially nonterminating Kotlin scanner was not invoked on either case.

## Positive control

```sh
timeout 10s ./audit asan-control > asan-positive-control.log 2>&1
```

This command intentionally fails with exit 1. The test creates a valid one-byte allocation containing `1`, poisons its logical end/padding, then deliberately corrupts the test-only size field to 2 before scanning. ASan reports `use-after-poison` inside `confix_scan_json`. This proves scanner read instrumentation catches an intra-allocation overread. The corrupted handle is outside the wrapper's accepted input contract; the report is not a discovered scanner vulnerability. The positive-control exit is expected failure evidence; it is not counted among passing scanner cases.

## Confirmed YAML semantic disagreement

Input bytes: `763a2031652b2b35`, text `v: 1e++5`.

```sh
timeout 10s ./audit inspect 'v: 1e++5' > numeric-native.log 2>&1
JAVA=/workspace/toolchains/graalvm-community-25.3.4.1+1.1/bin/java
KOTLIN=/workspace/gradle-offline-cache/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.21/c343e135bd1e7dd94b7ed76b4159068d888d09db/kotlin-stdlib-2.4.21.jar
"$JAVA" -cp "$KOTLIN" NumericOracle.java \
  '1e++5' NaN Infinity 1f 0x1p0 +NaN -NaN > numeric-jvm.log 2>&1
```

Native scan: fault 0; rows `(open,close,tag,depth)` are `(0,7,5,0)`, `(0,0,4,1)`, `(0,7,3,1)`. Compact tag 3 means Double. Actual Kotlin `String.toDoubleOrNull("1e++5")` returns null; current `Syntax.scanYaml0` therefore classifies this value as String. The numeric oracle directly executed Kotlin's scalar classifier, not the entire Kotlin scanner. This distinction is retained in `report.json`.

Preserve the native rows and report the disagreement. Do not silently retag, normalize, or count this fixture as JVM parity. Other numeric probes returned NaN/Infinity, 1.0 for 1f and 0x1p0, and NaN for signed NaN.

## Wrapper review limits

The checked struct layout and capacity calculations cover the current 64-bit ABI and wrapper maxima (`size<=65536`, `1<=room<=16384`). All tested scanner faults suppress row/tree exports.

The host must still maintain `open -> full write -> one valid-format run -> copy -> close`. Calling an invalid format returns-1 without clearing earlier successful rows; writing after a scan likewise leaves old rows readable. Handle freshness, exclusion, and exactly-once close are host responsibilities. Canary checks alone cannot detect reads or writes confined to alignment padding; this audit's poisoning supplies that additional coverage.

Full case logs remain outside the repository. `report.json` lists their compressed sizes and hashes; retain that summary and selected evidence instead of committing the noisy logs.
