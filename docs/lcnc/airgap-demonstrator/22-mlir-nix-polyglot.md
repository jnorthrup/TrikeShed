# MLIR-Nix through GraalVM Polyglot

## Decision and delivery boundary

Adopt JVM-hosted GraalVM LLVM/Sulong as the intended embedding route for compatible `mlir-nix` bitcode. The Kotlin host owns the Polyglot context, admission, observations and retained results. A Native Image C-API isolate is not required for this route. This decision specifies an implementation direction; it does not certify that a complete Nix evaluator bitcode artifact exists.

This chapter revises the supplied “Pivot: Execute mlir-nix LLVM bitcode via GraalVM Polyglot API in TrikeShed” RFC. It retains dynamic module loading and direct host/guest interoperability, corrects the memory and lowering assumptions, and supplies the missing engineering gates. The programme remains documents-first. No Kotlin evaluator, upstream lowering pass or runtime dependency was added by this revision.

This is the canonical decision chapter for P1–P3 deliverable scope, ABI selection and conditional expansion. The [Sulong bitcode companion](22-sulong-bitcode.md) supplies the historical scalar execution baseline and detailed build, transfer, ownership and commonMain lifecycle obligations. The [cost workbook](15-timeline-and-costs.md) owns reconciled arithmetic. No runtime execution is newly claimed by combining these documents.

Inspected on 4 November 2026:

- TrikeShed: `6875a72d633af94a1bcb74e95fc9c62a7ae94874`.
- `jnorthrup/mlir-nix`: `571b57d3ebdcdabe7ac76765b2875de7b98b9dab`.
- The existing SUMO-Nix bridge still pins `mlir-nix` revision `1547e4a37ea1393a7cd3e3cfe7a89aff38a36dba`. Inspection of a newer upstream revision does not update that executable contract.
- GraalVM documentation and public SDK/header sources were inspected. Their rolling documentation versions are not a compatibility certificate for TrikeShed's declared `25.3.4.1` artifacts. No Sulong execution or performance measurement was performed for this document.

## Corrections to the supplied RFC

| RFC proposition | Revised engineering statement |
|---|---|
| Running LLVM bitcode puts all evaluator allocations on the JVM heap | Default native mode retains native allocations and native-library crossings. Oracle's separately configured managed mode has different memory and platform semantics. Choose and record the mode. |
| Kotlin String and C `char *` pass transparently in both directions | Define an explicit interop ABI. A raw returned C pointer is not automatically a Polyglot string. Encoding, bounds, ownership and lifetime are part of that ABI. |
| No FFI/JNI overhead remains | A Kotlin-to-bitcode call need not use a handwritten JNI adapter. Guest-to-native library calls can still leave managed execution; conversion, dispatch and warmup costs remain measurable. |
| `mlir-opt --convert-nix-to-llvm` produces an LLVM `.ll` file | The inspected upstream pass registry has no general pass of that name. MLIR lowering and translation to LLVM IR are separate steps. Changing a filename suffix performs neither. |
| Standard LLVM bitcode is automatically compatible | Pin producer, reader, IR features, target layout, dependencies and execution mode; run the actual artifact through the selected Sulong version. |
| Public `llvm.func` declares a working evaluator | Visibility does not supply a function body, evaluator runtime, correct calling convention or ownership contract. |
| Add Polyglot and LLVM dependencies | Both already exist in TrikeShed. Verify resolution and offline packaging; avoid duplicate or mismatched coordinates. |
| Compare against the previous pure-Kotlin `mlnix` implementation | No complete Kotlin Nix evaluator was established by this inspection. Use named, inspected reference implementations on their common semantic subset. |

The memory distinction and native-library caveat follow [GraalVM's native execution documentation](https://www.graalvm.org/latest/reference-manual/llvm/NativeExecution/). The string distinction is explicit in the upstream [Polyglot C header](https://github.com/oracle/graal/blob/master/sulong/projects/com.oracle.truffle.llvm.libraries.graalvm.llvm/include/graalvm/llvm/polyglot.h). These corrections change the work and acceptance conditions; they do not reject the requested embedding direction.

## Existing repository surfaces

| Surface | Inspected behavior | Consequence for implementation |
|---|---|---|
| TrikeShed `build.gradle.kts` | `graalVersion = "25.3.4.1"`; `org.graalvm.polyglot:polyglot` and `org.graalvm.polyglot:llvm-community` already declared | Keep the release aligned across Polyglot, Truffle and guest languages. The RFC's `org.graalvm:...` coordinates are not the existing dependency coordinates. |
| `graal/subvm/GuestBounds.kt` | `GRAAL_LLVM` uses language ID `llvm`, no statement-limit claim, no observable-root claim | LLVM internal progress needs explicit instrumentation or honest call-boundary observations. |
| `graal/subvm/InProcessIsolate.kt` | Character-source `eval`, native access disabled, calls resolved through language bindings | A `.bc` module needs a byte-source path and retained module exports. Do not globally enable native access for other guests. |
| `graal/subvm/GuestIsolate.kt` | Results cross as `Teleported`; Polyglot `Value` is not the public result type | Keep context-bound module/function values inside the JVM adapter. Preserve common result/provenance types outside it. |
| Sub-VM capability harness | `host.llvm-language` checks language availability | Availability is only the first gate; it does not prove module compatibility or Nix semantics. |
| macOS cinterop / `mlnixC` | Optional linkage to the sibling project's native C surface | Preserve this independent parse/verify/print path until a verified replacement is explicitly selected. |
| Upstream `include/mlnix-c/MlnixC.h` | Session open/close, roundtrip, error and free; no evaluation export | The RFC's `mlnix_eval_string` is a proposed new API, not a callable existing symbol. |
| Upstream `lib/Eval/ScalarJIT.cpp` | Selected graphs become typed MLIR, then LLVM, then an MLIR ExecutionEngine lookup | This is a native guarded JIT path, not a complete portable evaluator module. |
| Upstream `lib/Eval/LazyJIT.cpp` | Demand/resume machinery interacts with persistent evaluator cells | Kernel extraction must preserve cell ownership, forcing order and deoptimization semantics. |
| Upstream `lib/Confix/confix.ll` | Freestanding scanner IR; current build assembles bitcode before producing an object | Useful bounded compatibility candidate. This scanner does not establish Nix evaluation. |
| Upstream `default.nix` | Uses caller-supplied/imported nixpkgs and `llvmPackages_latest`; builds `mlnix` and `mlnix-opt` | Pin the package/toolchain closure for reproducibility. No `flake.nix` was present in the inspected tree. |

Upstream evidence: [C API](https://github.com/jnorthrup/mlir-nix/blob/571b57d3ebdcdabe7ac76765b2875de7b98b9dab/include/mlnix-c/MlnixC.h), [pass registration](https://github.com/jnorthrup/mlir-nix/blob/571b57d3ebdcdabe7ac76765b2875de7b98b9dab/lib/Dialect/NixDialect.cpp), [JIT implementation](https://github.com/jnorthrup/mlir-nix/blob/571b57d3ebdcdabe7ac76765b2875de7b98b9dab/lib/Eval/ScalarJIT.cpp), [library build](https://github.com/jnorthrup/mlir-nix/blob/571b57d3ebdcdabe7ac76765b2875de7b98b9dab/lib/CMakeLists.txt), [Nix derivation](https://github.com/jnorthrup/mlir-nix/blob/571b57d3ebdcdabe7ac76765b2875de7b98b9dab/default.nix).

The existing `InProcessIsolate` name denotes a Polyglot context abstraction. It is not evidence that TrikeShed uses the Native Image `graal_create_isolate` C API for this evaluator. Avoid a global rename or replacement based on the word “isolate.” Extend the appropriate binary-module surface and its callers while preserving the other guest contracts.

## Separate three deliverables

### P1 Compatible kernels

Load a tiny scalar module, then one real bounded upstream kernel through the JVM Polyglot API. Start with primitive arguments/results to establish the reader, ABI and runtime closure. Move to a scanner or selected arithmetic kernel only after explicitly defining its pointer/buffer representation. A no-library scanner is attractive because it reduces dependency uncertainty, not because it makes arbitrary host arrays valid C pointers.

P1 proves the requested embedding mechanism. It can contribute useful CPU work in T1/T2, with normal LCNC receipts and visible timing. It must not be labeled a Nix evaluator. Preserve overflow checks and error status from an extracted arithmetic kernel; replacing a guarded operation with unchecked LLVM arithmetic changes semantics.

### P2 Evaluator compiled to bitcode

Compile the evaluator implementation and its reachable runtime dependencies to compatible bitcode, expose a small evaluation ABI, and execute it through Sulong. This route can preserve the existing interpreter's language semantics without first implementing complete ahead-of-time lowering of every Nix operation.

The inspected evaluator depends on C++ exceptions/RTTI, MLIR/LLVM components, frontend/store facilities and optional native services. Inventory the reachable closure. For the initial port, disable the evaluator's own native JIT path explicitly and report that configuration; Sulong supplies the outer execution engine. Do not let a nested MLIR ExecutionEngine silently emit host machine code and then advertise a bitcode-only runtime. If the nested JIT is later retained, it is a distinct capability with its own dependency, pointer, lifecycle and benchmark evidence.

Separate pure evaluation from store/build/network effects. An expression capable of importing an arbitrary path or invoking a builder needs a declared capability policy, not just a String parameter. A successful `1 + 1` fixture proves neither Nixpkgs compatibility nor the SUMO-Nix solver's lazy recursive workload.

### P3 Nix dialect lowering

Implement a specified subset or complete semantic lowering from Nix IR to LLVM-compatible operations and a defined runtime. Generic `func` conversion does not supply laziness, closures, recursive environments, dynamic types, string contexts, attribute lookup, imports, errors or builtins. Every remaining runtime call needs a resolved implementation and a defined effect boundary.

P3 is a compiler project. It can reuse guarded JIT work where applicable, but its coverage must be explicit. Unsupported operations must refuse compilation or use a declared interpreter continuation; they cannot disappear during conversion. Completing P1 does not imply P2 or P3. Select P2 versus P3 after the bounded compatibility experiment, using the exact workload needed by the demonstrator.

## Tier placement and coordination

The bytecode artifact is a versioned engine asset. The JVM adapter belongs to the existing engine execution boundary; it does not become a new LCNC scheduler. Pure scanner kernels fit T1 extraction and T2 indexing. A validated Nix/Horn evaluator fits T3 analysis. Guarded specialized kernels can later serve T2/T5 production, under the same source, policy and generation checks as other optimizations.

The path is: retained module bytes and manifest → compatibility/readiness checks → owned bounded request channel → Polyglot module invocation → copied result validation → retained semantic receipt → human-rate projection. Source provenance enters separately from executable identity. A module hash says which code ran; it does not authenticate the premises, approve a legal assertion or grant a production effect.

Reuse [execution and control](06-execution-and-control.md), [wire contracts](19-wire-contracts.md) and [NARS/Rete consolidation](10-nars-rete-consolidation.md). Keep query frames, first-proof limitations, source sidecars and conservative invalidation unchanged when changing the execution backend. A backend migration must pass the same semantic reference corpus.

## Runtime mode and target policy

For default Community/native mode, assume explicit guest allocation ownership and host-ABI dependencies. Native library calls may bypass Polyglot filesystem restrictions. For Oracle managed mode, require the actual distribution, configuration and compatible all-bitcode closure; its documented virtual Linux/AMD64 target differs from “compile for the Mac host.” Do not select managed mode merely by changing the Maven artifact name. [Execution modes](https://www.graalvm.org/latest/reference-manual/llvm/NativeExecution/)

The default plan is a trusted, pinned in-process kernel/evaluator profile. “Trusted” describes permission to share process fate, not proof that code has no bugs. Hard containment of a faulty or uncooperative native dependency still requires an OS process boundary. The local UI and primary host remain JVM-based if a separate worker becomes necessary for a particular capability.

| Profile | Target decision | Required evidence |
|---|---|---|
| Linux x86-64, native mode | Match target triple, layout and native dependency ABI to that worker | Actual load, execution, unresolved-symbol and resource tests on Linux |
| macOS arm64, native mode | Build/select an arm64 Darwin-compatible closure; do not reuse Linux binaries by assumption | Actual execution on Jim's machine, architecture and dependency receipts |
| Managed mode | Use the chosen runtime's documented virtual target and supported bitcode closure | Actual managed configuration, toolchain and distribution verification |
| Pure scalar bitcode | Avoid unsupported intrinsics, inline assembly and hidden native dependencies | Reader and semantic fixtures on every claimed target |

Record actual triple strings from the compiler; `x86_64-linux` is a platform shorthand, not the exact triple contract. Position-independent code is relevant to selected object/link formats; `-fPIC` does not turn incompatible bitcode into portable bytecode. Never repair a layout mismatch by editing only the module's target string.

The official [LLVM compatibility page](https://www.graalvm.org/latest/reference-manual/llvm/Compatibility/) still publishes an older LLVM-version range while the inspected `mlir-nix` README reports builds using LLVM 22/23. Treat this as an unresolved producer/reader compatibility question. A current documentation URL is insufficient to prove either acceptance or rejection by the pinned runtime. Retain the exact runtime probe and the first rejected instruction, intrinsic, attribute or bitcode record if it fails.

## Build mechanics

First choose the deliverable. For P1, retain the upstream kernel and its exact supported ABI. For P2, build a bitcode evaluator/runtime closure. For P3, implement and register the missing semantic conversions before claiming a build command exists.

For an already LLVM-translatable MLIR module, the stages are conceptually:

```text
verified source/dialect IR
  -> explicit supported conversion pipeline
  -> lowered LLVM-dialect MLIR
  -> mlir-translate --mlir-to-llvmir
  -> LLVM IR (.ll)
  -> compatible llvm-as / compiler tooling
  -> bitcode module (.bc), with linked or separately declared dependencies
  -> actual Sulong load and ABI probes
```

This is a staged contract, not a runnable recipe for full Nix today. MLIR's [LLVM target documentation](https://mlir.llvm.org/docs/TargetLLVMIR/) distinguishes lowering from translation and explains C-interface wrapper generation. Use the actual dialect-registering tool where custom dialects are still present. Verify that conversion leaves no unsupported dialect operations or unresolved conversion casts, and that translation succeeds before assembling bitcode.

Use modern opaque `!llvm.ptr` where required by the pinned MLIR version. A plain C ABI may be sufficient for primitive arguments. A function lowered from memrefs or multiple results needs inspection of the generated descriptor/wrapper ABI, including any `_mlir_ciface_` entry created through `llvm.emit_c_interface`. The wrapper name and layout must match the symbol the Kotlin adapter invokes. `sym_visibility = "public"` alone establishes none of these details.

For the proposed upstream build output, define a real target only after the selected route works. It should emit the module, ABI manifest, symbol/dependency listing, source/toolchain identities and a minimal retained result. Distinguish a plain `.bc` module from a native container carrying embedded bitcode. GraalVM documents both formats; dependency discovery differs. [Bitcode compilation and formats](https://www.graalvm.org/latest/reference-manual/llvm/Compiling/)

Extend the existing CMake/Nix build. A flake can be introduced if it solves a concrete packaging requirement, but the supplied RFC's “update the devShell” checklist does not match the inspected tree. Adding clang to an interactive shell does not define the reproducible derivation. Pin nixpkgs, producer LLVM/MLIR, runtime artifacts, target libraries and build flags. The sealed deployment bundle must contain the resulting closure; offline execution must not resolve Maven, Nix or native libraries from the network.

## Versioned evaluator boundary

Prefer a small interop-aware wrapper around the chosen evaluator to exposing arbitrary C++ evaluator objects. Select **ABI A**, a bounded request-scoped polyglot-string envelope, for the initial path. This is an intentional UTF-8 conversion boundary whose copy cost must be measured. The [companion ownership contract](22-sulong-bitcode.md) describes **ABI B**, an alternative with explicit session/input/result handles and bounded byte transfer. Select B only if persistent state or bulk transfer has a demonstrated need and its lifetime/state-isolation obligations are accepted. Implement one selected ABI version; both proposed export sets are not mandatory. A later change of ABI is a versioned asset/adapter change, not an unchecked extra calling convention.

The following ABI A names/signatures are proposed, not existing exports:

```c
#include <stdint.h>
#include <graalvm/llvm/polyglot.h>

uint32_t mlnix_polyglot_abi_v1(void);
polyglot_value mlnix_eval_v1(polyglot_value request,
                           uint64_t fuel,
                           uint64_t result_limit);
```

Version 1 accepts a Polyglot string containing a bounded request envelope. It returns a Polyglot string containing a bounded result envelope. Neither parameter is specified as a raw C string pointer. The request identifies source bytes/CID, evaluation mode, permitted import snapshot and resource policy; a JSON decoder does not itself grant those permissions. The module manifest defines the encoding and schema. Both host and guest enforce limits before materializing unbounded structures.

`polyglot_as_string` converts into a caller-supplied buffer; character count is not UTF-8 byte count. Use checked capacity arithmetic, an explicit UTF-8 encoding and tests for astral characters, combining characters, embedded NUL and invalid input. `polyglot_from_string_n` accepts an explicit byte length, avoiding accidental NUL truncation. The adapter must establish an owned host result before freeing its guest storage. These are wrapper obligations, not properties bestowed on `char *` by the Polyglot API. [Interop header](https://github.com/oracle/graal/blob/master/sulong/projects/com.oracle.truffle.llvm.libraries.graalvm.llvm/include/graalvm/llvm/polyglot.h)

For P2, start with request-scoped evaluation state. ABI A's wrapper releases its native allocations after producing an independently owned managed result, including error results. Verify that independence before freeing storage; otherwise choose the explicit read/release contract of ABI B. If session reuse is later required, specify session IDs, context binding, allowed cache state, import-frame identity, serialization, reset and close behavior. Do not let state from one hypothetical legal frame become an unreported premise in the next.

The result envelope separates execution status from semantic status. Successful evaluation can return a value; a solver can additionally return `SUPPORTED` or `UNKNOWN` under its declared fragment. Parse failure, unsupported capability, fuel exhaustion, output limit, interruption, module failure and native fault are not semantic `UNKNOWN`. Diagnostics and partial observations remain attributed to the failed invocation.

Never allow a C++ exception to escape the selected C export contract unintentionally. Convert expected evaluator failures into typed, bounded outcomes and release request-owned resources on those paths. Document faults that cannot be recovered within the process. The adapter's schema must not promise recovery from arbitrary memory corruption.

## Memory ownership

| Resource | Owner and lifetime | Completion obligation |
|---|---|---|
| Retained bitcode bytes | Host CAS / versioned asset owner | Verify exact bytes before load; retain dependency identity |
| Polyglot Engine | Existing JVM runtime owner, possibly shared | Close after all dependent contexts have drained |
| Context and module/function Values | One adapter worker | Serialize entry; Values never escape its lifetime |
| UTF-8 request buffer | Guest wrapper for the invocation | Release on success and every recoverable failure |
| Evaluator arena and thunks | Request or explicit session owner | Destroy/reset only after all dependent guest work ends |
| Native result/error storage | Guest allocator | Convert/copy while valid, then release through that allocator |
| Host String/result record | Host after conversion and validation | Retain according to source/result policy |
| Future foreign-object handle | Explicit registered handle owner | Release handles and prevent use after context/session close |

In native mode, a JVM collection or `Context.close()` must not be treated as the substitute for required guest frees. The original C header already exposes `mlnixFree` for its roundtrip output. A new evaluator ABI needs equally explicit obligations. Its existing diagnostic pointer is borrowed until the next call, which is another reason to serialize access and copy diagnostics before reentry.

Avoid casting JVM references to native addresses. If a future zero-copy path borrows a buffer, specify its accessible extent, alignment, endian order, mutability, backing storage, retention root and release point. The worker must join all admitted users before release. Do not reuse native function addresses from the upstream JIT inside Sulong as if they were portable module exports.

## Kotlin integration contract

Keep loading separate from generic character-source evaluation. The host obtains verified bytes through the existing userspace I/O path, then constructs a binary Source. The illustrative statements below show the SDK boundary only; they are not a complete new evaluator implementation:

```kotlin
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.io.ByteSequence

val source = Source.newBuilder(
    "llvm", ByteSequence.create(verifiedBytes), moduleName
).buildLiteral()
val module = context.eval(source)
val abi = requireNotNull(module.getMember("mlnix_polyglot_abi_v1"))
require(abi.canExecute() && abi.execute().asInt() == 1)
val evaluate = requireNotNull(module.getMember("mlnix_eval_v1"))
require(evaluate.canExecute())
val result = evaluate.execute(request, fuel, resultLimit)
require(result.isString)
val envelope = result.asString()
```

`Source`, `ByteSequence`, `Context` and `Value` are SDK boundary types. The byte-source construction is documented by the [Source API](https://www.graalvm.org/sdk/javadoc/org/graalvm/polyglot/Source.html). The proposed evaluator symbols must first be implemented and independently verified. Inputs here are already admitted, bounded values; this excerpt omits their host policy checks. Module load itself can run initialization, so load participates in ownership, timing, failure and trust accounting.

For this ABI, restrict `fuel` and `resultLimit` to positive values representable by a Kotlin `Long`, with tighter policy caps, even though their C declarations use `uint64_t`. Define any unlimited mode separately; never turn a negative host value into an enormous unsigned budget through conversion.

Keep the library/module Value returned by evaluation; resolve the agreed exports from that object. The existing `call` implementation's language-global lookup is not sufficient evidence for arbitrary LLVM module loading. Verify symbol visibility, executable status, ABI version, numeric range and result kind before accepting an outcome. Strictly decode the envelope, then bind it to the host's invocation/frame identity rather than trusting a guest-supplied success ID.

Use an explicit Context policy. Avoid the supplied `allowAllAccess(true)` default. Native access, host methods, class lookup, I/O, environment, threads and process creation are independent policy decisions. A chosen native LLVM configuration may require native access; grant that only to this admitted profile and test the resulting behavior. Do not claim that `IOAccess.NONE` contains native libc calls. [Context builder](https://www.graalvm.org/sdk/javadoc/org/graalvm/polyglot/Context.Builder.html)

A missing `llvm` language, missing export, ABI mismatch or rejected bitcode makes the engine unavailable with a reason. It must not silently select a native CLI and report Sulong execution. Any fallback is a distinct selected backend in the receipt. Preserve the existing public guest boundary rather than returning context-bound `Value` objects into common LCNC records.

## Ownership, pause and shutdown

One owned worker serializes calls per context. Its bounded input channel is admission; its SupervisorJob and child completion are lifecycle. Batch results are joined through the existing channel composition. Do not add a separate CREATED/RUNNING/CLOSED enum or a second scheduler around the adapter. Backend-specific SDK calls remain JVM code; asynchronous dispatch, I/O and ownership follow PRELOAD's commonMain userspace boundary.

Normal drain closes admission, processes accepted work according to the declared policy, copies/retains terminal results, releases invocation resources, closes the context and joins the owning jobs. Engine shutdown follows all its contexts. Initialization failures must close partially opened resources before returning failure. A submitted request owns its receipt completion even if its caller stops waiting.

Pause has two levels. At the baseline, the host can stop new admissions and acknowledge quiescence after the current call ends. Internal stepping requires real evaluator safe points with explicit fuel/progress and resumable state. Existing LLVM `GuestBounds` does not advertise statement counting or root instrumentation; the UI must reflect that. A timer and an interrupt request are not proof of a stopped native dependency.

Fuel is meaningful only if the evaluator decrements it at specified work points. A parameter named `fuel` without checked loops/recursion is not a bound. Wall deadlines remain independently observed. If a guest does not acknowledge interruption, do not free its borrowed buffers, recycle the context or claim successful cleanup while it may still execute. In-process hard termination is not promised. A capability requiring enforceable kill/reclamation must use a supervised process profile.

Module replacement uses versioned cutover: stop admission to the old owner, finish or explicitly fail its work, retain outcomes, then route new requests to the new module/context. Native state is not migrated by serializing a Polyglot Value. Compatible retained results can be reused only under complete module, input, policy, frame and runtime identities.

## Human-rate observations

Expose observed stages such as queued, module loading, input conversion, execution, result conversion/validation and retention. Record durations in the host monotonic clock domain; record guest-reported work counts as such. Use the existing semantic event contract and bounded UI projection rather than one unbounded event per LLVM instruction.

The flyover shows backend, mode, module CID, runtime version, input frame, active time, budget, retained output size and supported pause granularity. A spark on the input wire means an admitted transfer; an output spark requires a validated result transfer. While an opaque call runs, show elapsed time and an unknown internal frontier. Do not display a fabricated Nix AST traversal, percentage or proof tree.

If a later adapter adds guest progress callbacks, define a bounded payload and callback rate. Progress must not force lazy values merely to display them. A callback cannot reenter the same context or block indefinitely on UI delivery. Durable decision/effect events retain stronger delivery requirements than coalescible diagnostic counters. Benchmark callback overhead separately from computation.

## Acceptance and benchmark gates

| Gate | Required evidence | Failure disposition |
|---|---|---|
| P00 Asset identity | Exact source revisions, toolchain, module hash, runtime and dependency closure | Unavailable; no guessed substitution |
| P01 Basic embedding | Actual binary Source load and primitive export call on each claimed platform | Compatibility failure with retained diagnostics |
| P02 ABI | Missing/wrong symbol, version mismatch, range limits, malformed result and initialization failure | Typed refusal; partial resources reclaimed |
| P03 Strings and ownership | Unicode/NUL cases, repeated success/failure, bounded output, allocation/lifetime checks | No promotion until leaks and stale pointers are resolved |
| P04 Semantic subset | Same answers, errors, laziness/forcing and overflow behavior as independent reference | Retain counterexample; do not mask divergence |
| P05 Lifecycle | Concurrent callers, admission closure, drain, interruption, module replacement and close races | No premature completion or reuse |
| P06 Effects and offline | Missing assets, permitted imports, denied network/process requests, actual native dependency inventory | Unavailable or process profile where containment is required |
| P07 LCNC | Real receipts, truthful flyover, pause boundary, source/proof navigation | Backend cannot be presented as a verified live engine |
| P08 Cost | Cold/warm distributions, memory, conversion/callback cost and CPU/UI contention | Keep experimental if no workload benefit |

For P1 use integer boundary cases and one real upstream kernel fixture. For P2 add errors, short-circuit expressions with unforced failures, recursive bindings, closures, lists/sets, string contexts and allowed/denied imports before the Horn workload. For P3 maintain an operation coverage ledger and refuse unsupported constructs. Test real compiled artifacts; a mocked `Value` or fake `mlnix_eval_string` does not satisfy embedding acceptance.

Benchmark four named conditions where available: the current native `mlnix` CLI; a persistent native evaluator only if an actual comparable API has been implemented; Sulong cold load/first call; and Sulong warmed repeated calls. A Kotlin algorithm can be a reference only for its matching operation or Horn fragment. Do not compare one path's kernel-only time with another path's process startup and call the difference interop speed.

Freeze inputs, result consumption, module flags, evaluator JIT setting, hardware and thermal/power profile. Report parse/lower/link/load/warmup/compute/conversion/retention separately, plus end-to-end latency, throughput, p50/p95/p99, peak RSS and native versus JVM memory evidence. Include failure and cleanup latency. Warm runs must produce verified results; cached answers are a separate condition. Repeat process-level samples and report dispersion and corpus size. The existing upstream JIT's opt-in status is a reason to measure, not a reason to predict a speedup or slowdown here.

## Work, costs and decision sequence

Keep the programme's 25 ownership packages. Assign this work inside WP15 with WP02 contracts, WP09 lifecycle, WP11 observations, WP23 packaging and WP24 measurement. The baseline WP15 **32–64-hour tranche** covers 8–16 hours of feasibility, 8–16 of minimal reproducible build/probes, and 16–32 of existing-adapter repair and focused integration evidence. It retains the current native solver and may establish the new transport seam; it does not fund the complete new Sulong production profile.

After that review gate, a broader bounded P1 profile has the following **conditional incremental** estimate. These are remaining hours beyond the baseline, with baseline probes reused and charged once. The implementation owner must identify the accepted baseline artifacts and remaining work before activating an increment. If the feasibility result changes the scope, revise these assumptions rather than double-counting the same experiment.

| Increment | Accountable owner | Dependency | Additional active hours, assumption |
|---|---|---|---|
| Remaining target/closure compatibility and route work | WP15 Terra; Sol reviews semantic boundary | Accepted baseline probes and frozen runtime/upstream identities | 16–32 |
| Supported binary module and selected ABI implementation | WP15 Terra with WP02 | Accepted minimal build and reader/primitive probe | 32–64 |
| Ownership, observations and cutover | WP09 Sol with WP11 | Agreed module/ABI path | 40–80 |
| Platform, offline and benchmark evidence | WP23 Sol / WP24 Terra | Integrated bounded path | 24–48 |

This is **112–224 additional active hours** for a bounded compatible-kernel path, not full Nix. With the programme's 50% effort reserve: **168–336 hours**. Applying the existing base Fast rate of $4.80/hour and separate 30% cost uncertainty gives **$1,048–$2,097**; the heavy $14.85/hour assumption gives **$3,243–$6,486**. These use the [cost workbook's assumptions](15-timeline-and-costs.md), not newly measured token consumption.

At the initial six-lane capacity this increment with effort reserve consumes about 1.8–3.6 days of aggregate capacity, but serial compatibility, ownership and target-machine validation make **4–10 additional calendar days** a planning range for the bounded expansion. Complete the baseline feasibility gate before selecting it, then verify the remaining 16–32-hour compatibility/closure increment before committing its downstream work. A blocked reader or missing target machine is a reported gate, not an invitation to fabricate a successful smoke test.

P2 and P3 are **unestimated additional scope** until dependency closure or operation coverage is known. The bounded-kernel increment excludes rebuilding/porting MLIR dependencies, a complete Nix runtime, nested JIT support and comprehensive Nixpkgs compatibility. Do not quote the original 18–32-day demonstrator target as a promise of full in-process Nix. The demonstrator can retain the supervised existing solver while this preferred embedding route advances through its gates; every run names the backend actually used.

## Answers to the RFC's discussion points

Pin the target triple and data layout together with the selected runtime mode and dependency ABI. Native-mode Mac and Linux deployments need their own verified compatible closures. Managed mode follows the selected runtime's virtual target contract. There is no universal “forgiving triple” exemption.

Yes, native allocations and raw returned pointers require explicit ownership and release rules. Managed mode is a distinct configuration, not an assumption of the Community dependency. A Polyglot string returned by an interop-aware wrapper is different from a raw `char *`; convert/copy while valid and release guest-owned resources through the correct allocator.

The next implementation result should be a retained compatibility packet plus a real bounded module call through TrikeShed's owned execution path. Once that works, decide whether compiling the existing evaluator or implementing further dialect lowering is the justified route to the exact Nix/Horn workload.
