# MLX tensor core for the macOS native target

Status: specification. Nothing below is implemented. "Measured" lines were run on 2026-10-28 on one macOS arm64
machine; everything else is a requirement or an open decision and is marked as such.

Goal: `JevaRuntime.KOTLIN` runs the shipped 4-bit checkpoint (`jeva-4b-mlx-q4g32-dist`) on Kotlin/Native `macosArm64`
through MLX, and `JevaProcess` (Python + torch + transformers + mlx_lm) is retired.

## 1. What exists

Measured:

- Python MLX 0.32.0 in `~/work/Jeva/.venv` reports `Device(gpu, 0)`; the real checkpoint passes
  `JevaOversightMlxIntegrationTest`: `notes.md` p=0.8356 RELEVANT, `cargo.md` p=0.1539 NOT_RELEVANT, REVIEWED.
- Homebrew carries `mlx-c 0.6.0_4` (`/opt/homebrew/lib/libmlxc.dylib`, headers `/opt/homebrew/include/mlx/c`) over
  `mlx 0.32.x` with `mlx.metallib` beside `libmlx.dylib`. A C program linked only to `libmlxc` (no Python) ran on the
  GPU: `mlx_quantize` (affine, 4-bit, group 32) then `mlx_quantized_matmul` agreed with `mlx_dequantize` + `mlx_matmul`
  to 1.9e-6 max abs on a 3x512 by 512x256 case. This proves the C ABI is usable; it does not prove the Kotlin/Native
  link (section 3).
- The checkpoint's `backbone_mlx/model.safetensors` has 828 tensors: 201 quantized matrices stored as triples
  (`weight` U32, `scales` BF16, `biases` BF16), 177 BF16 tensors, 24 BF16 `A_log`, 24 BF16 `dt_bias`. Group 32 / 4-bit
  everywhere except `embed_tokens` (group 64 / 8-bit, `config.json` per-path override). `in_proj_qkv.weight` is
  `[8192, 320]` U32 = 8192 x 2560 at 4 bits. `lm_head` is tied (absent).

Already in `commonMain` (`narsese/`): `JevaInference` (serial owner, per-question deadline, option cache),
`JevaBackbone` (Qwen3.5 text, Float32, scalar loops, linear + full attention), `JevaDecision`/`JevaModel` (head),
`JevaTokenizer`, `JevaWeights` (bounded mmap safetensors), `JevaMatrix` over `MatrixSession`
(`userspace/nio/platform/spi/MatrixOperations.kt`). JVM has an Accelerate `cblas_sgemm` session
(`MatrixOperations.jvm.kt`). macOS native has **no** `MatrixOperations` provider
(`macosMain/.../PlatformProviders.macos.kt`), and no Jeva source in `macosMain`/`posixMain`.

The tensor type is the PRELOAD algebra, `Tensor = Series<Int>` shape and `MetaSeries<Tensor, Float>` values
(`lib/Tensor.kt`). `userspace/tensor/Core.kt` (a `ByteArray` class ported from literbike, with an MLIR placeholder) is a
different `Tensor`; Jeva does not use it and this spec does not build on it.

## 2. Why the existing Kotlin path cannot load this checkpoint today

Each item was read from source against the shipped files.

1. `JevaWeights.Dtype` is F32/BF16/F16; the checkpoint has U32. `JevaWeights.open` requires `backbone_full/` and
   `head.safetensors`; the release has `backbone_mlx/` and `head.pt`. `JevaInference.load` reads
   `tokenizer/tokenizer.json`; the release has `backbone_mlx/tokenizer.json`.
2. `JevaBackboneConfig.parse` requires `rope_parameters.rope_type == "default"`; the release config has
   `"type": "default"` and no `rope_type`, so the config is rejected.
3. Norm weights are pre-shifted. mlx_lm `sanitize` adds 1.0 to `input_layernorm`, `post_attention_layernorm`,
   `model.norm`, `q_norm`, `k_norm` when a checkpoint is unsanitized; this one is sanitized (conv1d is
   `[8192, 4, 1]`). Measured: `input_layernorm` mean 1.14 min 0.98, `model.norm` mean 3.2. `JevaBackbone.rms` applies
   `1 + w` (`zeroCentered = true`); on this checkpoint that doubles the shift. Only `linear_attn.norm` is unshifted by
   design (`zeroCentered = false`).
4. `conv1d.weight` is `[convDim, kernel, 1]` here; `JevaBackbone.linearAttention` reads `[convDim, 1, kernel]`.
5. Weight names are `language_model.model.layers.N…`; `JevaBackbone` defaults to prefix `language_model.` (the
   constructor already takes `prefix`).
6. `head.pt` is a torch zip (uncompressed entries `head/data.pkl`, `head/data/0…`), 15 float32 tensors whose flattened
   names (`context_proj.weight`, `core.blocks.0.q_proj.weight`, `core.tau_dec.raw`, …) already match what `JevaDecision`
   and `JevaModel` look up.

Any parity failure that comes from 3 or 4 would produce plausible-looking probabilities. They are listed first so the
acceptance gate (section 6) is built to catch them.

## 3. Binding

- `mlx-c` through Kotlin/Native cinterop on the `macos` target only: a `.def` under `src/macosMain/resources/META-INF/cinterop/`,
  declared in the `macosArm64("macos")` block of `build.gradle.kts` beside `posixSpawn`. Header and library prefix come
  from a Gradle property (as `uringLiburingBuildDir` does), defaulting to `/opt/homebrew`.
- Measured (2026-10-28, Kotlin/Native 2.4.21 `cinterop` + `kotlinc-native`, target `macos_arm64`, outside the Gradle
  build): a `.def` with `package = mlxc`, `headers = mlx/c/mlx.h`, `compilerOpts = -I/opt/homebrew/include`,
  `linkerOpts = -L/opt/homebrew/lib -lmlxc -rpath /opt/homebrew/lib` produced a klib, and a Kotlin executable calling
  `mlx_default_gpu_stream_new`, `mlx_array_new_data`, `mlx_matmul`, `mlx_array_eval`, `mlx_array_data_float32` printed
  `58,64,139,154` for [[1,2,3],[4,5,6]] x [[7,8],[9,10],[11,12]], which is correct. Findings:
  - A `headerFilter = mlx/c/**` line yields a klib with no usable bindings; omit it.
  - `mlx_array` / `mlx_stream` are by-value structs: they arrive as `CValue<mlx_array_>`; out-parameters are
    `alloc<mlx_array_>()` and `.ptr`. `mlx_array_free` returns `Int`.
  - The executable links `/opt/homebrew/opt/mlx-c/lib/libmlxc.dylib` by absolute install name, so the rpath option was
    not exercised. Moving the triple (`libmlxc`, `libmlx`, `mlx.metallib`) off Homebrew paths is **not verified**.
  - Done in the repo: `mlx.def` under `src/macosMain/resources/META-INF/cinterop/`, `create("mlx")` in the `macos`
    target (`cinteropMlxMacos` builds; Homebrew prefix is in the `.def`, no Gradle property yet), tier 1
    (`MlxMatrixOperations` / `MlxMatrixSession` in `macosMain`, registered in `PlatformProviders.macos.kt`), and
    `MlxMatrixOperationsTest` (3 tests, 0 skipped, passing under `macosTest`: strides/offsets/transposition,
    bounds, and `JevaMatrix.linear` against the Kotlin reference within 2e-5). Hazard: the `.def` links `libmlxc` into
    every macOS binary, so a machine without Homebrew `mlx-c` cannot start `canary`, `ledger` or `mcpStdio`.
  - Done in the repo, tier 2 start: `MlxTensor` (`macosMain`), an MLX array as a `JevaWeight`, i.e. a
    `MetaSeries<Tensor, Float>`: `a` is the shape `Series<Int>`, `b` the coordinate oracle, `read` the bulk span;
    evaluation is lazy, on first read; BF16/F16 widen to Float, packed U32 refuses. The session owns every handle and
    `close()` frees them, after which reads throw. `MlxMatrixSession.tensor`, `.load(path, names)` and
    `.quantizedLinear(input, weight, scales, biases, groupSize, bits)`. `MlxTensorTest`: the algebra cell test passes,
    and with `TRIKESHED_TEST_JEVA_MLX=1` (not skipped) `quantizedLinear` on layer 0 `mlp.gate_proj` of the real q4g32
    checkpoint matches Python MLX 0.32.0 on a fixed 2 x 2560 input: cells within 1e-3, sum within 1e-3 relative.
  - Finding: `mlx_load_safetensors` on a GPU stream fails at evaluation (`[Load::eval_gpu] Not implemented`); the read
    runs on a CPU stream and consumers on the GPU stream. `load` is MLX reading the file itself, outside the NIO
    facade (section 5, open decision).
  - Done in the repo, ops: `embed` (quantized table rows, dequantized to Float32), `rmsNorm` (stored weight as-is, so the
    pre-shifted norm weights of 2.3 are right here), `silu`, `times`. `MlxTensorTest` (opt-in, not skipped): tokens
    1..5 through the 8-bit/group-64 embedding, layer 0 `post_attention_layernorm` and its 4-bit SwiGLU block match
    Python MLX 0.32.0 at each stage (embed, norm, mlp output: head/tail cells within 2e-3, sums within 1e-3).
  - Done in the repo, full attention: primitives `sigmoid`, `reshape`, `swapAxes`, `split`, `rope` (`mlx_fast_rope`, base
    in Float), `attention` (`mlx_fast_scaled_dot_product_attention`, causal), and `MlxQuantized` + `fullAttention`
    (`MlxQwenLayer.kt`: gated query projection, per-head q/k norm, rope on 64 of 256 dims, GQA 16/4, gate, output
    projection). `MlxTensorTest` (opt-in, not skipped): layer 3 on tokens 1..5 matches Python MLX 0.32.0 (head/tail
    within 2e-3, sums within 1e-3). The reference is mlx_lm's plain rope; that this equals the checkpoint's
    interleaved-mRoPE for text positions is mlx_lm's premise, not checked here against Transformers.
  - Done in the repo, linear attention (`linearAttention` in `MlxQwenLayer.kt`): quantized qkv/z/out projections, dense
    BF16 `in_proj_a`/`in_proj_b` (these two are not quantized in the checkpoint: no `scales`/`biases`; the "201
    quantized matrices" count in section 1 excludes them), depthwise `conv1d` over three zero history positions, q/k
    unit-RMS scaling, A_log/dt_bias retention gate, and the delta recurrence stepped on the device with ops
    (`slice`, `times`, `sum`, `add`, `minus`, `repeat`, `concatenate`, `softplus`, ...). `MlxTensorTest` (opt-in, not
    skipped): layer 0 on tokens 1..5 matches Python MLX 0.32.0 using mlx_lm's `gated_delta_ops` (head/tail within
    2e-3, sums within 1e-3). The stepped graph is O(tokens) ops per layer; it is the correctness form, and the metal
    kernel row of section 4 is the speed form, not started.
  - Done in the repo, the whole backbone: `MlxBackbone(session, directory)` (`MlxBackbone.kt`) reads `config.json`
    (per-path `quantization` overrides included), names every tensor once, and `encode(tokens)` runs embedding, 32
    layers (norm, mixer, residual, SwiGLU, residual), final norm, returning the host `MetaSeries<Tensor, Float>`
    [tokens, hidden]; `session.scoped` frees every device intermediate when `encode` returns. Common fix:
    `JevaBackboneConfig.parse` accepts `rope_parameters.type` as well as `rope_type` (2.2). `MlxBackboneTest`
    (opt-in, not skipped): 12 token ids through all 32 layers of the real checkpoint against mlx_lm's own Qwen3.5
    module run in Float32 (Python MLX 0.32.0): last-token cells 29.0411/6.3293/-1.7697 vs 29.0432/6.3308/-1.7688, sum
    -92.29 vs -92.21, abs sum 3236.9 vs 3236.5, all-token abs sum 34677.8 vs 34674.4. For scale, mlx_lm's shipped
    BFloat16 module differs from its own Float32 run by cosine 0.99994 and max abs 0.13 on the last token.
  - Done in the repo, Jeva without Python on macOS native: `JevaEncoder` (token ids to hidden states; `JevaBackbone` and
    `MlxBackbone` implement it) and `JevaTensors` (named tensors an inference owns; `JevaWeights` and the new
    `JevaHeadTensors` implement it) in common code, `JevaInference` takes both; `JevaHead` reads `head.pt` in pure
    Kotlin (stored zip entries, the pickle subset torch.save writes; `JevaHeadTest` 3/3 on a torch 2.9.1 fixture, and the
    existing JVM `JevaBackboneTest` and `JevaInferenceTest` still pass); `loadMlxJeva(config, session)` assembles the
    MLX backbone, the `head.pt` head, `backbone_mlx/tokenizer.json`, and the head's matrices on the same session.
    `MlxJevaTest` (opt-in, not skipped): the oversight pair of `JevaOversightMlxIntegrationTest` asked of that
    `JevaInference`: cargo.md p=0.1465, notes.md p=0.8379, against the Python MLX BFloat16 run's 0.1539 and 0.8356.
    At that measurement the identity covered the runtime and config/head/tokenizer digests and the weight file's
    modification time, not a hash of the backbone weights.
    Rechecked 2026-11-03 with the installed Q4 checkpoint and the same state bytes (SHA-256
    `786c97348665b0d8ac929207dfb9addb5e39252d42e2f58b5e79cd0829e27017`): Python MLX gave 0.1538735330 and
    0.8355575204; native Kotlin gave 0.1464705169 and 0.8378676772. The monitored native run took 89.29 s, with
    3,344,941,056 bytes maximum RSS and 3,630,194,816 bytes peak footprint. Its 180 s per-decision deadline did not
    expire. This comparison covers these two questions and does not establish general numerical or model quality.
    The task was “How is the manifest verified before blocks are cached?”; `span-0` was the cargo note
    “Cargo note: the manifest of the container ship lists forty crates of oranges.” and `span-1` was
    “The manifest must be verified against its sha256 before any block is cached.” Each question asked whether its
    span was relevant, with false/true options describing whether the excerpt helps answer the task question.
    A later run of this same pair through the content-verified reader returned the identical native probabilities.
    Admission, including block hashing and CPU materialization of selected weights, took 16.1 s; total was 96.5 s,
    maximum RSS 3,419,258,880 bytes and peak footprint 3,704,152,256 bytes. The comparison run above took 89.29 s,
    with 3,344,941,056 bytes maximum RSS. These single runs are not a general performance or residency guarantee.
  - Done in the repo, the production lane: `EncoderOperations`/`EncoderSession` (common, next to `JevaEncoder`) are the
    platform's device-backbone provider, resolved by `JevaElement` from the coroutine context or the NIO services exactly
    as `MatrixOperations` is; `JevaInference.loadDevice(config, encoders)` is the device lane (backbone from the provider,
    head from `head.pt`, tokenizer from `backbone_mlx/`); `MlxEncoderOperations` is registered in
    `PlatformProviders.macos.kt`; `JevaElement` takes that lane when `device == "mlx"` and fails with a named error when no
    provider exists. `MlxJevaTest` (opt-in, not skipped, 2 tests, run together): the oversight pair through
    `loadDevice` and through a real `JevaElement.ask` + `drain()` give identical answers, cargo.md p=0.1465 and notes.md
    p=0.8379 (Python MLX BFloat16: 0.1539, 0.8356). This replaces the earlier `loadMlxJeva` helper.
  - Measured, and a defect: each of those two tests took 151 s and 198 s of wall time (load plus one inference of two
    questions), unprofiled. The stepped gated-delta graph is the suspect; the Python MLX run answers the same pair in
    seconds with the kernel. The first time the two tests ran together, the `JevaElement` one failed with an
    unclassified native exception (`Unknown`) after 131 s; in two later runs (alone, and together, with the failure
    printer added) it passed. The cause was not found, so a flaky failure is not ruled out.
  - Not yet done: the gated-delta kernel and profiling, the removals of section 7 (`JevaProcess`, `JevaRuntime.PYTHON`,
    the `device == "mlx"` check in `McpStdio`), relocating the dylibs off
    Homebrew paths, and the other targets (which keep the Python or CPU lanes).
- mlx-c is C with explicit `_free`; the session owns its handles (section 5). Some functions return status codes;
  the default mlx-c error handler can terminate the process before a status is returned (section 5).
- `expect`/`actual` stays inside the userspace-nio SPI (PRELOAD): the new surface is a sibling of `MatrixOperations`,
  common interface, JVM and others `TODO()` where unbacked, macOS native actual over mlx-c. No other `expect`.

## 4. Tensor core

Two tiers; tier 1 is a drop-in and is not the goal.

Tier 1, `MatrixSession` actual over `mlx_matmul`. Closes the missing macOS provider with no common-code change.
Every GEMM copies `FloatArray` in and out, so it is bandwidth-bound and slower than Accelerate for the sizes
`JevaMatrix` tiles (<=128x<=256). Use it only as the first working native run and as the float oracle.

Tier 2, device-resident backbone (the goal). A layer of the Qwen3.5 forward must stay on device between ops; the
scalar loops in `JevaBackbone` (rope, delta recurrence, softmax rows) cannot be left in Kotlin.

- Handle: a value class over `mlx_array` (name proposal: `MlxArray`), the resident form of a `MetaSeries<Tensor, Float>`.
  MLX is lazy like the algebra: ops build a graph; `mlx_array_eval` happens only at a materialization boundary
  (`read(offset, dst, …)` / `.b(index)` / the pooled hidden state). Shape is a `Tensor` (`Series<Int>`) in and out.
- Surface (proposal): `TensorOperations : CoroutineContext.Element` with `Key`, `open(): TensorSession`, parallel to
  `MatrixOperations`. `JevaInference` takes an interface with `encode(tokens): MetaSeries<Tensor, Float>`;
  `JevaBackbone` (CPU, current) and a device backbone both implement it. Today `JevaInference` holds the concrete class,
  so extracting that one interface is the only common-code change.
- Ops the forward needs, and the mlx-c entry for each:

  | need | mlx-c |
  |---|---|
  | embedding row gather from a quantized table | `mlx_take` on `weight`/`scales`/`biases`, then `mlx_dequantize` (8-bit, group 64) ✓ |
  | every projection | `mlx_quantized_matmul`, `transpose=true`, per-tensor group/bits ✓ |
  | rms norm | `mlx_fast_rms_norm` with the stored weight used as-is (see 2.3) |
  | q/k per-head norm, partial rope (64 of 256 dims, base 1e7, text positions) | `mlx_fast_rms_norm`, `mlx_fast_rope` |
  | full attention, GQA 16/4, causal, output gate | `mlx_fast_scaled_dot_product_attention` (causal mask), `mlx_sigmoid`, `mlx_multiply` |
  | depthwise causal conv (width 4) + silu | `mlx_conv1d` with `groups = convDim`, or slice + multiply; then silu |
  | gated delta recurrence (24 of 32 layers) | `mlx_fast_metal_kernel_*` carrying the kernel mlx_lm `gated_delta.py` uses, or an ops loop over positions; a copied kernel keeps mlx_lm's licence notice (licence not checked here) |
  | silu, softplus, decay / learning gates | no mlx-c entry for either: `x * mlx_sigmoid(x)`; `mlx_log1p(mlx_exp(x))` |
  | last-token pool, cast to F32 | slice, `mlx_astype` ✓ |

  Headers checked: `mlx_fast_rms_norm`, `mlx_fast_rope`, `mlx_fast_scaled_dot_product_attention`, `mlx_conv1d`,
  `mlx_sigmoid`, `mlx_log1p`, `mlx_astype`, `mlx_take`, `mlx_quantized_matmul`, `mlx_dequantize`. Their numerics
  against the Qwen3.5 reference are not checked.

  The decision head (768-wide, float32, one block) stays on `JevaDecision` + `JevaMatrix`; it is not worth a device
  round trip and it is already common code.
- One sequence per `encode`, unpadded, no KV cache across calls (matches `MlxTextBackbone.encode`), so recurrent and
  conv state are per call and freed with it.

## 5. Weights and lifecycle

- Loading: a retained userspace `FileChannel` supplies MLX's safetensors reader. A manifest hashes 1 MiB blocks;
  every touched block is read in full and checked against it before bytes are given to MLX. A mismatch or short read
  latches failure and zeros the callback buffer; evaluation and output check that latch. Selected weights are evaluated
  on the CPU stream before GPU work, and the reader remains owned until MLX releases its lazy references. Identity
  includes the ordered block manifest and the parsed backbone config; this is a block-content identity, not a standard
  whole-file SHA-256. The prior zero-copy mapped-tensor approach remains unverified.
- Head: a Kotlin reader for the torch zip subset the checkpoint uses (stored zip entries; pickle opcodes for an ordered
  dict, `_rebuild_tensor_v2`, persistent storage ids; F32 only; anything else is an error). The alternative, a one-time
  conversion to `head.safetensors`, requires torch once and keeps the dependency alive, so it is rejected.
- Lifecycle (PRELOAD rules): the session is opened by the existing serial owner (`JevaElement` already admits work
  through a channel and joins on drain); no state enum or `started`/`running` flag. `close()` frees the device
  arrays, the mmap-backed buffers and the stream, and is idempotent through ordinary resource ownership, not a flag.
  `mlx_array_eval` is synchronous on that owner; it is computation, not an I/O completion, same as `MatrixSession.gemm`.
  Cancellation is the cooperative `JevaDeadline.check` between ops, which is where the current backbone checks.
- Identity: `JevaInference.identity` must carry the MLX and mlx-c versions, device, quantization config and the
  weight content identity in place of the Python/torch library fingerprint; the old `worker_sha256` and
  `jeva_source_sha256` disappear with the worker.

## 5a. Memory and residency (measured 2026-10-28, 36 GiB machine, 5.5 GB of swap already in use before any test)

Requirement: the quantized weights stay resident, and running Jeva must not push other processes' memory out.

Incident. The first monitored run of the oversight test (before the fixes below) drove the machine into a memory
collapse: the compressor grew from 2 GB to 22.6 GB, wired from 2.3 to 8.5 GB, free memory fell to 36 MB, swap-outs
reached about 19 GB (swap in use 5.5 GB to 23.8 GB), and two JVM daemons (5.3 GB and 3.0 GB resident, presumably gradle and
the Kotlin daemon), Brave, Spotlight and WindowServer lost almost all resident pages. The watchdog killed the test only after this had happened (its sample
interval stretched under thrash, and it watched swap in use, which lagged). Cause: `MlxBackbone.encode` built the whole
32-layer graph lazily and `session.scoped` freed nothing until the end, so every intermediate of every step stayed
alive; the delta recurrence alone keeps several `[32, 128, 128]` temporaries per token per layer. The earlier "flaky"
`Unknown` failure of the `JevaElement` test is probably the same pressure; that is a hypothesis, not shown.

Fixes in the repo: `MlxMatrixSession.mark()/settle()` end the graph per layer and every 8 recurrence steps, freeing
what is not kept. Each completed recurrence chunk retains one output block rather than a handle for every token;
the retained output still grows with sequence length. After a layer's new hidden state has been evaluated, its prior
hidden-state handle is released instead of retaining one full activation per completed layer until encode returns.
`MlxLayerRetentionTest` evaluates 32 small synthetic layers, checks that only the current hidden-state handle remains,
verifies the numeric result and verifies that an externally owned tensor survives. A released tensor refuses subsequent
access, and a retained tensor rematerializes its host buffer if settlement freed that buffer. These checks do not
serialize concurrent session use. The test does not measure full-checkpoint
peak memory or establish a 9,000-token bound. `MlxMatrixOperations(memoryLimit = 8 GiB, cacheLimit = 512 MiB, wiredLimit = 4 GiB)` applies
process-wide MLX settings when a session opens; `deviceMemory()` reads MLX active, peak and cache allocations.
The memory setting is a graph-evaluation guideline, not a hard allocation limit: MLX says allocations may exceed it
and fail only after available RAM and swap are exhausted. Cache and wired settings are likewise not host-wide memory
admission. The process can still displace other workloads. See [MLX memory limit](https://ml-explore.github.io/mlx/build/html/python/_autosummary/mlx.core.set_memory_limit.html).

Verified (sampler at 0.5 s: swap in use, vm_stat, per-process RSS; watchdog on swap growth, compressor growth,
swap-outs and reclaimable memory; the sampler is `monitor.py` in the Hermes scratch directory, not in this repo):

| run | result |
|---|---|
| oversight pair, cold file cache | passed in 88.8 s, same answers (0.1465 / 0.8379); MLX active 2778 MiB, peak 2958 MiB, cache 280 MiB; test RSS 3.2 GB, process footprint 3.6 GB; wired +3.2 GB while running; compressor 1.9 to 2.2 GB; swap in use did not rise; no process lost more than 50 MB of RSS; 218 MB swapped out machine-wide, all in the last 2 s as the process exited, cause unexplained |
| `JevaElement`, two asks, warm cache | ask 1 83.6 s (load about 25 s plus inference), ask 2 56.1 s with the weights resident; MLX active, peak and cache identical after both asks; swap-outs 0, swap in use flat at 6795 MB, page-ins 142 MB, wired back to its start value at exit |

Gaps found against the strategy, each open unless stated:

1. Residency is not a policy yet. The weights are wired while a session lives (observed), but `JevaElement.release()`,
   `drain()` and any idle release drop the session, and the next ask reloads 2.9 GB in about 25 s warm, more cold.
   Nothing decides when the quant may be dropped.
2. The weights are held twice during load: the file's page cache (file-backed memory grew 3.3 GB on a cold run) and the
  MLX copy. Page cache is reclaimable, but it competes with other processes. The verified reader uses the NIO facade
   and still copies into MLX; the zero-copy route (section 5) is unverified.
3. The limits are MLX process-global: every `open()` resets them, the last session wins, and the three numbers are
   defaults, not derived. Measured peak is 2.96 GiB for the short fixture; the 8 GiB guideline is not a hard guard.
   A defensible admission budget would need the
   weights plus a bound on activations per token, and `maxStateLength` (9000 tokens) has not been run: activations
   grow with tokens and no 9000-token run has been made.
4. No admission control: `JevaElement` does not look at available memory before loading, and nothing refuses a second
   concurrent device session (two would hold two copies of the weights).
5. Speed: 56 s per ask of two short questions with the weights resident; the cold run used 95.8 s of user CPU in 89 s of
   wall time. The cause is not profiled; many small op dispatches from the host is the suspect, and the gated-delta
   kernel is the intended fix, not started.
6. The Python MLX path was never measured under the same sampler, so "no worse than Python" is unproven.
7. The identity includes the backbone block-content manifest. Device admission uses a separate `JevaDeadline` with
   the configured timeout: bounded file reads, chunked head/tokenizer hashing, parsing, block-manifest construction,
   and each weight materialization check it cooperatively. A single blocked I/O call or MLX evaluation cannot be
   preempted by those checks, so this is not a hard wall-clock guarantee.
8. The sampler's abort is not a safety property of the product, and MLX's settings do not impose a hard host-memory
   ceiling. No process-wide owner prevents another session from changing them during execution.
9. The linked Homebrew mlx-c 0.6.0_4 header exposes `mlx_get_memory_limit` but no cache or wired limit getters. Its
   setters return the previous limit only on success. The mlx-c default error handler exits the process: an isolated
   child calling `mlx_set_wired_limit(SIZE_MAX)` printed the MLX error and exited 255 before returning a status. No
   error handler is installed in this repository, so Kotlin status checks alone cannot recover from that failure.
   Installing a handler is process-global and must be coordinated with every mlx-c caller. Source: [mlx-c v0.6.0 error handler](https://github.com/ml-explore/mlx-c/blob/v0.6.0/mlx/c/error.cpp).
10. The affine Q4/Q8 path now validates integral quantization settings, supported mode, packed weight and scale/bias
    shapes and dtypes before quantized kernels, including embedding token bounds. It checks the selected matrices
    before weight materialization. A closed session also refuses new tensor allocations, and a lazy shape is captured
    once before its storage size is checked and uploaded. These checks do not protect
    every mlx-c entry point: malformed safetensors parsing or a missing tensor can still reach mlx-c's default handler.
    The actual local checkpoint header has 201 quantized triples matching these layout checks; that metadata inspection
    does not execute the checkpoint or prove its values.

## 6. Acceptance

A parity gate before any Python is removed, written first and shown failing against the CPU backbone on this
checkpoint (it should fail for 2.2, 2.3, 2.4):

1. Per-layer: for fixed token ids, hidden state after layers 0 (linear), 3 (full), 31 and after the final norm,
   device backbone vs Python MLX (`Jeva.from_mlx` path), cosine and max abs error recorded. The tolerance is set
   from those measurements; none exists yet.
2. End to end: the `JevaOversightMlxIntegrationTest` pair (relevant > irrelevant) run with `runtime=KOTLIN` on the
   native binary through `mcpStdio`, and the two probabilities recorded next to 0.8356 / 0.1539.
3. Resource: peak resident set during one `infer` and time per question, measured, against the Python run. No target
   is stated because none has been measured.
4. Drain: `JevaElement.drain()` returns with no live `mlx_array`; verified by a handle counter in the session.

## 7. Retirement of the Python path

Only after section 6 passes on the native binary: remove `JevaProcess.kt`, `JevaRuntime.PYTHON`, the `device == "mlx"`
check in `McpStdio.kt`, the `TRIKESHED_JEVA_PYTHON`/`SOURCE` settings in `JevaConfig`, `JevaQ4Runner.kt`, and the
Python gating in `JevaOversightMlxIntegrationTest` / `McpStdioProcessTest`; `config/jeva-mlx.env.sh` lives in another
repository and is not touched here. The JVM runtime can reach the same C ABI through FFM as it does Accelerate; that is
a second consumer of the same surface, not part of this target.

## 8. Not covered

Linux and the other targets (no MLX there; they keep `TODO()` on the new surface), training or LoRA, batching across
sequences, a KV cache across calls, the multimodal parts of Qwen3.5, and any speed claim.
