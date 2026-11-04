# MLIR Nix bitcode execution through Sulong

## Decision and scope

Adopt the requested JVM Polyglot direction for the next **investigation and bounded implementation slice**: retain a verified LLVM bitcode artifact, load it through GraalVM's LLVM language, and invoke an explicit exported interface from Kotlin. This replaces a proposed Native Image isolate and C API embedding path for that slice. It does not replace the existing working native Horn route before equivalent semantics and production integration are demonstrated.

The initial target is a bounded positive Horn computation or an independently useful pure compiled kernel with an exact reference result. A scalar or scanner pilot establishes the transport and ownership contract; it cannot count as completed SUMO or Nix evaluator integration. General Nix evaluation, a fully compiled ontology, and arbitrary legal inference have separate compiler and semantic obligations.

This chapter refines the supplied pivot RFC into a build, memory, lifecycle and acceptance contract. It is a blueprint. No new runtime adapter, compiler pass or bitcode artifact is delivered by this chapter.

## What the current code actually establishes

| Surface | Observed baseline | Consequence for the pivot |
|---|---|---|
| `docs/sulong-bitcode/proof.txt` and `proof.json` | Historical Linux execution of handwritten `add.ll` assembled with LLVM 22.1.8; exact results 42, −2 and 0 | Real scalar interchange exists; MLIR or C frontend compilation was not exercised |
| `SubgraalPointcutRunner` | LLVM file evaluation with native access enabled and bitcode verification disabled | A useful proof seam; not the production native capability contract |
| `InProcessIsolate` | Native access denied; ordinary LLVM initialization refused in the retained controls | Selecting `GRAAL_LLVM` in a palette does not establish a working execution lane |
| `GuestBounds.LLVM` | LLVM root events marked unobservable | Emit host boundary events first; do not fabricate internal instruction sparks |
| JVM dependencies | Polyglot and `llvm-community` already declared together | Verify resolution and compatible versions rather than adding duplicate dependencies |
| `NativeHornQuery` | Pinned native subprocess, frame binding, certificate checks and current-review rechecks | Preserve these semantics when adding a new execution mechanism |
| Kotlin/Native `MlirMain` and `MlnixC.h` | Parse, verify and print through `mlnixRoundTrip` | That ABI supplies no Nix evaluation or proof-producing function |

The historical Sulong proof belongs to source `26e647e3d` and frozen build source `6246c3085`, not to an arbitrary present checkout. Its full harness and bitcode are referenced under a cloud artifact directory unavailable in Jim's local checkout. Tracked IR and proof metadata support the recorded narrow result; the implementation campaign must recreate a complete local reproduction packet.

The additional upstream inspection used clean tracked files at mlir-nix `571b57d3ebdcdabe7ac76765b2875de7b98b9dab`, independently of dirty local README and benchmark work. Its scalar JIT builds selected MLIR, lowers arithmetic/control flow/functions, translates through MLIR's execution engine and executes native code. Its dumped LLVM IR is a useful export seam. It is not a public general Nix-to-LLVM compiler. [Pinned scalar JIT source](https://github.com/jnorthrup/mlir-nix/blob/571b57d3ebdcdabe7ac76765b2875de7b98b9dab/lib/Eval/ScalarJIT.cpp)

Its lazy kernels refer to evaluator-owned cells, environments and continuation state. Exporting a dumped kernel alone would omit those owners. The first design review must choose either a self-contained supported computation with a stable value ABI, or a real runtime bridge retaining the required evaluator machinery. The existing C surface remains text roundtrip only. [Pinned embedding header](https://github.com/jnorthrup/mlir-nix/blob/571b57d3ebdcdabe7ac76765b2875de7b98b9dab/include/mlnix-c/MlnixC.h)

## Corrections to the RFC assumptions

| RFC assumption | Required contract |
|---|---|
| A Kotlin `String` becomes a native `char*` without copying | A polyglot string is a foreign value. Choose explicit UTF-8 transfer or a tested polyglot string adapter; neither implies a native pointer to JVM string storage |
| One JVM garbage collector owns all evaluator memory | Native-mode allocations retain native ownership. Every input, result, session and retained foreign handle needs a release rule |
| Dynamic bitcode loading removes build and native dependencies | It removes a Native Image rebuild requirement for the chosen artifact; it still requires compatible compiler output, runtime libraries, target layout and exported symbols |
| `mlir-opt --convert-nix-to-llvm --convert-func-to-llvm` emits `.ll` | A custom lowering must actually exist and be registered. Lowered MLIR and LLVM IR are separate formats; translation is an explicit stage |
| An x86_64 target works on Jim's arm64 Mac | Native-mode artifacts must match the executing architecture, operating system and ABI. Build and prove host profiles separately |
| `allowAllAccess(true)` is the wrapper configuration | Use an explicit operator-owned native capability with individually configured permissions. Native calls still share the host process's authority |
| Native interruption supplies a hard deadline | Cooperative budgets and safepoints need evidence. A stuck native call cannot be given a reliable process-kill boundary inside the daemon |

GraalVM's native-mode documentation separates garbage-collected foreign values from native allocations requiring manual release. It also states that native library calls can bypass virtual filesystem controls. Its managed execution mode has a different memory model and is documented as an Oracle GraalVM feature. The current community native profile must not inherit managed-mode promises. [Native execution and memory](https://www.graalvm.org/latest/reference-manual/llvm/NativeExecution/)

The official LLVM polyglot header provides explicit string conversions, including byte counts and encodings. Its `polyglot_as_string` writes into a caller buffer; insufficient capacity can omit termination. `polyglot_from_string_n` preserves embedded zero bytes by taking an explicit length. These APIs establish a conversion path, not automatic zero-copy C string access. [LLVM polyglot header](https://raw.githubusercontent.com/oracle/graal/master/sulong/projects/com.oracle.truffle.llvm.libraries.graalvm.llvm/include/graalvm/llvm/polyglot.h)

## Compiler and artifact production

Separate two possible products. A **compiled kernel** contains the selected supported computation and its explicitly linked support. A **bitcode evaluator** compiles the necessary interpreter/runtime implementation and exposes an evaluate operation. The latter can still interpret Nix internally; loading it through Sulong is not proof that all Nix expressions were lowered into LLVM. It also brings a substantially larger C++ and runtime dependency surface.

For the kernel product, freeze the accepted operation set, value representation, effects, overflow rules, lazy demand order, error positions and recursion limits. Reject unsupported operations during compilation, preserving the original input and exclusion reason. Do not erase lazy or effectful semantics merely to make the final LLVM module verify. Preserve source/frame/quotation sidecars separately from compiler metadata.

MLIR's documented path first converts operations to translatable dialects and then translates them to LLVM IR. A stock conversion of `func` operations does not provide semantics for custom Nix operations. The build must prove that every executable custom operation has been lowered, that remaining casts reconcile, and that the resulting exports have the intended calling convention. [MLIR LLVM target](https://mlir.llvm.org/docs/TargetLLVMIR/)

The following is a **stage contract**, not a claim that the first command or its flags already exist:

```text
reviewed input + pinned compiler
  -> implemented supported-fragment lowering -> lowered LLVM-dialect MLIR
  -> mlir-translate --mlir-to-llvmir lowered.mlir -o module.ll
  -> llvm-as module.ll -o module.bc
  -> verification + symbol/dependency/target inspection -> immutable bundle
```

`llvm-as` assembles LLVM IR into bitcode; it does not compile MLIR. When a C/C++ ABI shim or runtime component is required, compile that component using the pinned compatible clang toolchain, retain its compilation database and flags, and link compatible bitcode with `llvm-link`. Clang `-emit-llvm -c` is a frontend route for C/C++; an already translated `.ll` file does not need a fictitious C compilation step. [LLVM assembler](https://www.llvm.org/docs/CommandGuide/llvm-as.html), [Clang toolchain stages](https://clang.llvm.org/docs/Toolchain.html)

Implement `buildBitcode` as a real task in the existing build structure, with explicit source inputs, source revision, pass pipeline, compiler versions, host profile, dependencies and output hashes. Do not add a shell-script runtime or generate artifacts during daemon requests. A pinned flake or existing toolchain manifest may supply clang/MLIR/LLVM at build time; the acceptance packet must contain the actual lock and derivation identities, rather than asserting that a proposed flake is present.

Retain `.mlir`, `.ll`, `.bc`, exported-symbol inventory, undefined-symbol inventory, compiler diagnostics and the artifact manifest. Reject a build that succeeds only because an unresolved symbol is never reached by a tiny fixture. C++ exceptions, RTTI, allocator, libc/libc++, thread support and file/store imports require an explicit dependency disposition. The kernel pilot should minimize this surface; evaluator embedding must account for it in full.

## Host profiles and offline packaging

Create separate release profiles for native Linux x86_64 and native macOS arm64. Each names JDK distribution/version, Polyglot and language coordinates, observed LLVM language version, compiler tools, target triple, data layout, pointer width, endian, optimization pipeline and native dependency hashes. Do not pin only the Maven facade version and assume that it equals the LLVM language version.

The historical proof observed Polyglot 25.3.4.1 with LLVM language 22.1.8. Some current general compatibility documentation still lists a much older range. Resolve compatibility from the exact selected runtime's inventory, packaged toolchain, release/source information and an executed fixture; do not disable all bitcode verification to reconcile conflicting version labels. [Published compatibility page](https://www.graalvm.org/latest/reference-manual/llvm/Compatibility/)

GraalVM documents plain bitcode and native executables with embedded bitcode, and explains that binary headers can provide library dependency information. Select the packaging form deliberately; a bare `.bc` needs its dependency closure supplied and verified by the bundle. [Compiling and dependency loading](https://www.graalvm.org/reference-manual/llvm/Compiling/)

Keep verification enabled. A genuinely equivalent target spelling may justify a narrowly documented compatibility exception after inspection; architecture, libc or data-layout differences require another build. Never transplant the historical x86_64 `add.ll` target into the arm64 production profile as evidence of portability.

The sealed runtime bundle includes bitcode, native dependency closure, ABI manifest, licences, checksums, compatible JVM/language dependencies and known-answer fixtures. Cold launch on a disconnected host must need no Maven, Nix substituter, compiler download, public gateway or library search outside declared paths. Resolve immutable artifacts through the existing userspace file capability, retain their exact bytes, and verify before load. Loading from an operator path is a trusted bootstrap choice; request bodies cannot select arbitrary bitcode paths or libraries.

## Export and memory ownership contract

Design a small versioned ABI before implementing a Kotlin convenience wrapper. `evaluate(String): String` may be the user-facing shape, but it hides allocation, limits and errors unless backed by an explicit contract. The initial interface should use fixed-width integers, bounded byte transfers and context-local opaque numeric handles. Never expose evaluator object layouts, JVM addresses or host pointers in retained receipts.

| Operation family | Proposed obligation |
|---|---|
| ABI/profile query | Report ABI version, supported fragment, limits and build identity before input admission |
| Session allocation | Allocate one evaluator owner with fixed input/output/work limits; failure returns an explicit allocation error |
| Input allocation and UTF-8 write | Allocate a bounded input handle; copy validated UTF-8 bytes through an explicitly implemented array/buffer adapter |
| Evaluation | Consume an immutable input and budget; return a result handle plus a domain status, with no caller-controlled imports |
| Result length and read | Report exact byte length and transfer the complete bounded output into host-owned bytes/text |
| Input/result release | Release allocations exactly once while their owning session and context are live |
| Session close | Release remaining handles and evaluator state after admitted work finishes |

These are proposed export families, not existing mlir-nix symbols. The implementation owner must freeze actual names, integer widths, error codes and generation checks at the review gate. Zero or another reserved token denotes allocation failure; stale, wrong-session and released handles are rejected. Handles retained beyond one call include a generation so a recycled slot cannot identify a different allocation.

Use strict UTF-8 encoding with explicit byte length. Kotlin source offsets remain UTF-16 coordinates in their existing source maps; an adapter records the conversion instead of treating byte offsets as character offsets. Include non-BMP characters, combining marks and embedded NUL. If the Nix parser forbids NUL, return a parser error at its exact position rather than silently truncating the input. Reject malformed UTF-8 and unpaired UTF-16 surrogates rather than substituting characters unnoticed.

A minimal transfer adapter can read a bounded polyglot byte array element by element into guest-owned memory. A measured bulk-buffer path may replace it when the selected runtime supports the needed access contract. Merely passing a JVM `ByteArray` to a function declared `const char*` does not define either adapter. A string-based shim must explicitly accept a polyglot value and perform the documented conversion. Preserve the exact input bytes used for the frame CID.

For output, read exact result bytes before releasing the result handle; decode strictly and then construct host-owned records. A shim may return a polyglot string using an explicit byte length, but the adapter still owns and releases the native result allocation. Define ownership separately for success, domain error, malformed output, exception and interrupted evaluation. Every path releases input/result handles in `finally`; session cleanup accounts for a result whose handle could not be returned because evaluation failed partway through.

No `Value`, foreign pointer, lazy iterator or callback capturing a context may escape into the journal, UI or ordinary result cache. Cache host-owned canonical bytes plus the artifact/profile/frame identity. A context's close operation is necessary cleanup, but it is not a substitute for the evaluator's explicit allocator contract.

## Trusted capability and CCEK composition

Use the existing engine adapter boundary with a new explicitly selected native-capable profile. Keep the ordinary restricted guest policy unchanged. The trusted profile permits LLVM initialization/native access and denies Java class lookup, broad host access, ambient environment, guest process creation and guest thread creation unless a demonstrated dependency requires a specific grant. `allowAllAccess(true)` grants broad privileges and is not the contract. Configure the individual builder permissions and bounded diagnostic streams. [Context builder permissions](https://www.graalvm.org/25.0/javadoc/sdk/org/graalvm/polyglot/Context.Builder.html)

Native-capable execution still belongs to the daemon's OS process. A network restriction on the whole daemon can enforce the offline profile; Polyglot flags alone cannot establish an OS air gap for native dependencies. Do not accept untrusted bitcode or unrestricted user expressions with store/build/import effects. The source bundle, supported fragment and operator capability determine what may execute.

All asynchronous admission, bounded channels, result fan-in and drain remain in commonMain, composed through singleton typed `CoroutineContext.Key` identities under owning SupervisorJobs. The JVM Polyglot call is a synchronous target implementation invoked through the existing userspace boundary; it must not create a second coroutine runtime. Respect PRELOAD's rule that genuine target SPI differences belong at userspace NIO, rather than adding `expect`/`actual` declarations in an engine package.

One worker owns each context and its foreign handles. Requests enter a bounded channel; channel send is admission. The worker validates frame/profile, transfers input, evaluates, copies and validates output, releases handles, then completes the admitted result. Parent composition joins every admitted branch, including failed ones. Separate contexts permit bounded parallelism only after measuring their shared runtime/native dependencies and memory use.

Drain closes input, processes accepted requests, releases session/context state, joins workers, and completes/joins the supervisor. Do not use a lifecycle enum or a check-then-send admission flag. A context pool's capacity and worker jobs govern ownership; replacement after an engine failure is an explicit failed result and new owner, not silent reuse of potentially corrupted state.

Use compiler/runtime work budgets for rounds, derived facts, recursion, allocations and output. Add cooperative polling at supported evaluator boundaries. The host can request interruption, but GraalVM documents that native/host execution can be non-interruptible. Therefore a hard per-request termination guarantee is unavailable until demonstrated for the admitted computation. Pause reports pending native work and becomes effective only at a real safe point. [Context interruption and closure](https://www.graalvm.org/25.0/javadoc/sdk/org/graalvm/polyglot/Context.html)

If strict killability or per-request OS isolation is required for a workload, use the existing supervised process capability for that workload. This is an explicit execution-profile choice, not a silent fallback after an in-process run has already failed. In-process memory corruption or fatal native failure can terminate the daemon; replay/recovery must make this visible.

## Proof semantics and visible progress

Keep the exact ordered facts/rules, source sidecars, input/frame CID, supported fragment and host-defined rules from the current Horn contract. Verify returned certificates independently and recheck source/review currentness before accepting the result. A success status from `Context.eval` establishes engine completion, not entailment, approval or legal applicability.

`SUPPORTED` requires a checked proof for the requested target. Successful complete no-proof execution yields `UNKNOWN` only for the submitted positive fragment and selected slice. Compilation exclusion, invalid ABI, absent language, allocation failure, budget exhaustion, interruption, stale review and malformed output remain explicit operational outcomes. Neither a compiled kernel nor repeated execution adds independent NARS evidence.

Emit real host events for queue admission, context readiness, input transfer, evaluation start, measured completion, output validation and release. Retain work counters only when the evaluator actually supplies them. A long native evaluation gets an elapsed timer and declared unobserved interior; the renderer cannot invent per-rule progress or a completion percentage. Optional internal events require their own bounded instrumentation contract and must not change proof results or timing claims silently.

Reloading a different bitcode CID creates a new engine generation and definition dependency. Finish admitted work in the old owner, retain its receipts, then close it. Do not hot-swap code beneath a live evaluator handle. Source withdrawal invalidates current results and dependent productions exactly as for the existing solver path.

## Benchmark and acceptance sequence

Freeze the corpus, theory/frame, proof requirements, CPU affinity/thread counts and bundle versions. Compare pure Kotlin, the existing native CLI and Sulong on equivalent supported semantics. Where existing backends expose different fragments, label that comparison as kernel/transfer cost rather than solver equivalence. Do not measure a scalar addition against a complete solver and report an engine speedup.

Measure artifact load, context initialization, first call, warm evaluation, input encoding/copy, output transfer/decoding, proof checking, full request latency, throughput, allocation/GC, process RSS, guest/native allocation counters and shutdown. Report cold and warm distributions separately and retain time series for repeated create/use/close cycles. Record whether the selected engine actually performs JIT compilation; the historical proof reported an interpreted engine.

The acceptance ladder is sequential:

1. Recreate the scalar positive/refusal controls locally with verification enabled and complete artifacts on both selected host profiles.
2. Build one real mlir-nix-supported computation through documented lowering and retained `.mlir`/`.ll`/`.bc`; inspect symbols, target and dependency closure.
3. Exercise ABI ownership with Unicode, empty/maximal inputs, truncated buffers, stale handles, allocation failure, exceptions and repeated session/context closure. Native leak measurements must cover repeated failing calls.
4. Execute the intended Horn fixture with an independently checked target proof and exact source sidecars. Preserve duplicate supports, unknown outcomes and fresh recomputation after withdrawal.
5. Invoke the production LCNC route through commonMain admission and fan-in; show real queue/evaluation events, pause boundaries, drain and process-restart recovery.
6. Launch from the sealed disconnected bundle and benchmark equivalent workload results. Verify that a missing dependency gives `UNAVAILABLE` or a precise startup refusal without a network fetch.

The small transport pilot cannot waive later steps. Runtime-native policy refusal is a valuable control, not a reason to weaken the default guest policy. Keep all failure receipts; repair implementation causes without excluding compiles or tests.

## Ownership and review gate

WP15 retains 32–64 active agent-hours, split into 8–16 hours for compiler/ABI feasibility, 8–16 for a reproducible bitcode build, and 16–32 for the bounded adapter and meaningful production verification. These are planning allocations within the existing package, not a promise that a full Nix compiler/runtime port fits 64 hours.

At the end of feasibility, Sol reviews Terra's exact supported computation, compiler seam, ownership model, host profiles and missing runtime facilities. Approve the bounded path only when the remaining work is concrete. If a generic Nix-to-LLVM compiler, substantial C++ runtime port or new proof backend is required, record a scope/estimate change before consuming the remaining allocation. Keep the existing native route available and mark the Sulong capability experimental until the intended acceptance level is met.

WP23 owns final dependency closure and offline enforcement; WP24 owns cross-engine measurements; WP09 owns truthful pause semantics; WP17/WP19 own evidence and production dependencies. WP15 supplies artifacts and capabilities to those owners without creating another work package, changing the 25-task structure, or asserting a shorter critical path merely because Native Image has been removed.
