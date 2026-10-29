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
    The identity covers the runtime and the config/head/tokenizer digests and the weight file's modification time, not a
    hash of the backbone weights.
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
    the `device == "mlx"` check in `McpStdio`), backbone-weight hashing in the identity, relocating the dylibs off
    Homebrew paths, and the other targets (which keep the Python or CPU lanes).
- mlx-c is C with explicit `_free`; every handle is owned by exactly one session (section 5). Every call returns an int
  status; a non-zero status throws, with the text from the mlx-c error handler.
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

- Loading: extend `JevaWeights` (not a second reader) with U32 and the quant triple, reading `config.json`
  `quantization` (default plus per-path overrides) and accepting `backbone_mlx/`, `tokenizer.json` beside it, and a
  head read. Weights reach MLX as `mlx_array_new_data_managed` over the bounded `MemoryMapping`, with the mapping's
  close as the destructor, so file ownership stays at the NIO boundary (PRELOAD ISAM/mmap rule). **Open:** whether
  Metal can use those pages without a copy (page alignment) and whether a 635 MB `embed_tokens` mapping fits the
  64 MiB window policy in `JevaWeights`. If not, the window policy gets a per-tensor exception; an MLX-side
  `mlx_load_safetensors` is the fallback and would bypass the NIO facade, which needs the owner's decision.
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
