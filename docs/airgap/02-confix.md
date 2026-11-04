# R2: owned Confix scanning

R2 extends the scalar runtime with the actual JSON, CBOR and YAML scanner from
`jnorthrup/mlir-nix`. The node is `vm.llvm.confix`. A document enters as UTF-8
text, or canonical base64 for CBOR. The node emits copied parse geometry,
explicit scanner faults, the source identity and measured runtime observations.
It uses no model, GPU, remote service or Nix evaluator.

The pinned upstream IR is retained unchanged under
[`src/jvmMain/llvm/confix`](../../src/jvmMain/llvm/confix). Its companion
`bridge.c` supplies bounded allocation and copying; the original source does
not allocate. Provenance includes upstream revision, path and SHA-256.

## Build and configure

Use the R1 toolchain: LLVM/MLIR 22.1.8, GraalVM CE 25.3.4.1 with JDK 25.0.4.1,
Kotlin 2.4.21 and Gradle 9.8.2. This retained profile targets Linux x86-64.

```sh
./gradlew verifyConfixBitcode \
  -PsulongLlvmBin=/absolute/LLVM-22.1.8-Linux-X64/bin
```

The build compiles `bridge.c` to LLVM bitcode, then links the unchanged scanner
IR. The bridge uses compiler builtin integer types and explicit `calloc/free`
declarations; it needs no host SDK headers. `calloc` and `free` are the only
undefined symbols in the linked module. Sulong's native runtime supplies them.
The upstream IR has no data-layout declaration; the linked module uses the
bridge's explicit Linux target layout. The linker reports this difference.
The retained fixture comparison prevents an unnoticed producer change.

Create a trusted operator profile outside request-controlled storage:

```json
{
  "module": "/absolute/TrikeShed/build/sulong/confix.bc",
  "sha256": "25225b0b480635499f091e1c09862aa945720eb204b5b06298a7eb652338d0f3",
  "abi": "confix.flat/1",
  "maxInputBytes": 65536,
  "maxRows": 16384
}
```

Set `TRIKESHED_LLVM_CONFIX_PROFILE` to that profile's absolute path before
starting the daemon. The scalar profile may be enabled alongside it. They
occupy separate serial seats under the same existing LLVM root owner; normal
shutdown drains consumers before that root. Opening a second profile does not
change the ordinary guest native-access policy.

The LCNC parameters are `format` (`JSON`, `CBOR`, `YAML`) and `data`. A `data`
or `data?` wire overrides the authored data parameter; duplicate aliases and
wired null are refused. The graph cannot choose the module, native permission,
exports or allocation limits. UTF-8 encoding rejects invalid UTF-16 input;
CBOR input uses canonical padded base64 and preserves arbitrary bytes.

## Ownership and bounds

Each admitted scan copies its bounded input before queuing, creates one native
allocation, fills it through packed integer lanes, calls exactly one scanner,
copies the result, and frees the allocation in `finally`. Handles and guest
Values never leave the adapter. Cancelled callers wait for admitted work before
the invocation ledger is finalized. Drain rejects new admission and completes
queued work before closing Context. This is not hard native cancellation.

The allocation contains the two native records, input bytes and seven guarded
regions. For input length `N` and capacity `R`, the arrays are:

| Region | Capacity |
|---|---|
| Input | `N` bytes |
| Inclusive spans | `2R` 32-bit integers |
| Compact tags | `R` bytes |
| Depths | `R` 32-bit integers |
| Child starts | `R+1` 32-bit integers |
| Child indices | `R` 32-bit integers |
| Work | `max(9*(N+1), 3R)` 32-bit integers |

The bridge checks `N <= 65536`, `1 <= R <= 16384` and asserts the 64-bit struct
layout at compile time. Smaller operator limits are allowed. Every region has
sentinels; the adapter checks them before copying. Result header, span bounds,
tag range, depth, child counts and child indices are checked before publishing
geometry. Reversed legacy spans are retained as geometry and must not be
blindly dereferenced by consumers.

`allocatedBytes`, `guardsVerified` and `liveBytesAfter` describe the bridge's
actual allocation. The last must be zero after every completed scan. They do
not measure Sulong's whole native heap or prove memory isolation. The native
sanitizer audit separately poisons logical padding and guard regions so it can
detect reads and writes that ordinary sentinel comparison cannot detect.

## Result and fault semantics

`rows` contains records with `open`, `close`, `tag`, `depth` and ordered
`children`. Compact native tags are explicitly mapped to Kotlin names, not
interpreted as current enum ordinals. No host pointer or native struct is
serialized. `sourceCid` hashes the original bytes. The production runner puts
those bytes into the existing CAS and syncs before returning
`sourceRetained=true`.

A scanner fault is an observed result: `ok=false`, empty published `rows`,
`rowCount=0`, and a fault record. `observedRows` is diagnostic partial progress,
not usable geometry. The LCNC invocation can complete successfully while the
scanner reports a data fault; downstream decisions must inspect `ok`.

| Code | Name | Meaning of `at` |
|---|---|---|
| 0 | none | No fault location |
| 1 | truncated | Input/computed position |
| 2 | reserved-cbor-ai | CBOR header position |
| 3 | span-bounds | Row ordinal |
| 4 | nonprogress | Input position for CBOR; unspecified for YAML |
| 5 | row-capacity | Input position for JSON/CBOR; unspecified for YAML |

Invalid profiles, refused authority, ABI mismatches and invalid copied result
shapes fail execution rather than reusing those scanner fault codes. On every
scanner fault the native allocation is still released.

`queueNanos` measures admission and scheduling. `scanNanos` measures the native
scanner call. `callNanos` includes allocation, input transfer, scanning, result
copying and release. `loadNanos` describes one Context opening. Internal
instruction progress remains unobserved; pause is available between scans.
There is no claimed speedup from this deliberately simple copying ABI.

## Compatibility evidence and deliberate limits

JSON scanning is permissive: unfinished containers, malformed numeric tokens
and unmatched delimiters can produce geometry. Fault zero is not strict JSON
validation. CBOR retains legacy length arithmetic and faults on known backward
positions. The native YAML scanner retains line-based and CRLF-normalized
coordinates; these must not be advertised as normalized UTF-8 character spans.

There is a measured YAML disagreement for `v: 1e++5`: the native scanner tags
the value `IoDouble`, while the JVM scanner tags it `IoString`. The result is
retained without silently changing either engine. General JVM/native YAML
parity is not claimed. This is the kind of explicit engine disagreement the
curation panel must expose before downstream inference or production admission.

The [bounded native audit](evidence/confix-native-20261104/reproduction.md)
records 150,026 passing cases, scanner ASan plus wrapper ASan/UBSan coverage,
allocation checks and an intentionally failing instrumentation positive control.
It is separate from actual Sulong, LCNC and offline verification. A bounded
fuzz run is not an exhaustive memory-safety proof.

The release test programme adds full Kotlin geometry comparisons, malformed
and capacity cases, cancellation/drain, immutable admitted input, exact source
CAS retention and a two-engine whole-program receipt. The [exact evidence packet](evidence/r2-20261104/README.md) records the final
combined-source checks, CAS identities, conditional skips and reproduction.
The [D3M record](../d3m/2026-11-04-airgap-r2.md) retains ordered branch inputs,
merge identities and source equivalence.
