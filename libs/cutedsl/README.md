# libs/cutedsl — CuTe DSL / ThunderKittens-Inspired Kernel Programming

> **Centroid Alignment**: Direct response to Vlad Feinberg's (Google DeepMind Distinguished Engineer L9) statement of "voracious demand for kernel / low-level engineering" at frontier labs. This module provides the CuTe-style tensor layout DSL and hardware atom abstractions needed to build, optimize, and demonstrate LLM kernels — the #1 hiring signal for 2026.

## Vision

Frontier labs (DeepMind, OpenAI, Anthropic) are hiring for:
- **Kernel engineering**: Throughput, latency, efficiency at scale
- **Hybrid research + engineering**: Mathematical maturity, scaling-law intuition, ability to implement novel techniques across the stack
- **Concrete demonstrations**: JAX Scaling Book exercises, transformer implementations from scratch, recorded as hiring signals

`libs/cutedsl` is TrikeShed's **kernel development substrate** — the "picks and shovels" for the kernel gold rush.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     libs/cutedsl                                │
├─────────────────┬─────────────────┬─────────────────────────────┤
│  Layout/Tile    │  Hardware Atoms │  CursorTensor Bridge        │
│  (CuTe core)    │  (MMA/TMA/CPAsync)│  (Cursor ↔ Kernel)        │
├─────────────────┼─────────────────┼─────────────────────────────┤
│  - Shape/Stride │  - MMA atoms    │  - fromCursor/toCursor      │
│  - divideTile   │  - TMA async    │  - provenance tracking      │
│  - transposed   │  - CPAsync      │  - algebraic composition    │
│  - mmaTiled     │  - Pipeline     │  - blackboard emission      │
└─────────────────┴─────────────────┴─────────────────────────────┘
```

### Core Abstractions

| Abstraction | CuTe/ThunderKittens Origin | Purpose |
|-------------|---------------------------|---------|
| `Layout` | `cute::Layout` | Shape + Stride mapping logical→physical |
| `Tile` | `cute::Tensor` | Layout + data pointer |
| `MmaAtom` | `cute::MMA_Traits` | Tensor core descriptor (16×16×16, etc.) |
| `TmaDescriptor` | `cute::TMA` | Async global↔shared copy (Hopper+) |
| `Pipeline` | K-stage pipeline | Overlap copy/compute |
| `CursorTensor` | **Novel** | Cursor ↔ Kernel bridge with provenance |

## Quick Start

### Build
```bash
./gradlew :libs:cutedsl:build
```

### Run Tests (RED phase — should fail initially)
```bash
./gradlew :libs:cutedsl:test
```

### Run a specific test class
```bash
./gradlew :libs:cutedsl:test --tests "borg.trikeshed.cutedsl.LayoutTileRedTest"
```

## TDD Red Tests (Current State)

All tests are written **RED first** following TrikeShed's Cursor TDD patterns (see `BUILD_GUIDE.md`):

| Test Class | Focus | Status |
|------------|-------|--------|
| `LayoutTileRedTest` | Layout construction, indexing, slice, transpose, divideTile | 🔴 RED |
| `GemmAttentionRedTest` | GEMM, MMA GEMM, Flash Attention QK^T, SV, pipelined | 🔴 RED |
| `HardwareAtomsPipelineRedTest` | MMA atoms, TMA, CPAsync, Pipeline, Cluster, Barrier | 🔴 RED |
| `CursorTensorIntegrationRedTest` | Cursor↔Tensor conversion, slice, broadcast, reduce, provenance | 🔴 RED |

**Next**: Implement GREEN — make tests pass with reference CPU implementations, then REFACTOR for GPU launch.

## Integration Points

### 1. Cursor Algebra (PRELOAD.md)
```kotlin
// Cursor → CursorTensor → Kernel → Cursor
val cursor = cursorQuery("SELECT * FROM embeddings")
val tensor = CursorTensor.fromCursor(cursor, Layout.fromCursorShape(intArrayOf(128, 768)))
val result = tensor.gemm(weightTensor)
val outputCursor = result.toCursor()  // Back to cursor pipeline
```

### 2. Blackboard Provenance
```kotlin
val observation = resultTensor.toBlackboardEntry(
    role = "kernel_output",
    metadata = mapOf("kernel" to "gemm", "mfu" to "0.85")
)
blackboard.emit(observation)  // Observable experiment tracking
```

### 3. Confix Config
```json
{
  "kernel": "gemm",
  "mma_atom": "MMA_16_16_16_F16",
  "tile_sizes": [64, 64, 32],
  "stages": 4,
  "provenance": { "source": "scaling_book_ch3" }
}
```

## ThunderKittens / CuTe Inspiration

This DSL is directly inspired by:
- **CuTE** (CUTLASS Templates) — NVIDIA's layout/tile algebra for CUTLASS
- **ThunderKittens** — Andrej Karpathy's teaching DSL for GPU kernels
- **CUTLASS 3.x** — Hierarchical GEMM with MMA/TMA/Pipeline

Key design principles borrowed:
1. **Layout = Shape + Stride** — decouples logical shape from memory layout
2. **Hierarchical tiling** — divideTile for block/warp/MMA partitioning
3. **Hardware atoms as first-class** — MMA, TMA, CPAsync, Pipeline, Cluster
4. **Composition over inheritance** — Layout/Tile compose, don't inherit
5. **Lazy views** — slice/partition are zero-copy views

## Roadmap (Centroid-Aligned)

| Phase | Deliverable | Vlad Demand Mapping |
|-------|-------------|---------------------|
| **0.1 (NOW)** | Layout/Tile core + RED tests | Kernel demand response |
| **0.2** | GREEN: CPU reference impl passing all RED tests | Demonstrable kernels |
| **0.3** | GPU launch via JNI/CUDA (PTX) | Real MFU measurements |
| **0.4** | Autotuning + MFU harness | Efficiency measurement |
| **0.5** | Scaling Book exercise kernels (Ch3 GEMM, Ch4 Attention) | Hiring signal artifacts |
| **1.0** | Diffusion head for kernel generation | Automated kernel authoring |

## Contributing

1. **Write RED test first** — define expected behavior in `*RedTest.kt`
2. **Implement GREEN** — minimal implementation in `commonMain`/`jvmMain`
3. **REFACTOR** — optimize, add GPU launch, integrate with Cursor/Blackboard
4. **Provenance** — every kernel op emits blackboard observation

## References

- **Vlad Feinberg Interview** (June 2026, Ryan Peterman): "Voracious demand for kernel/low-level engineering"
- **JAX Scaling Book**: https://jax-scaling-book.org — Exercises we target
- **CuTE**: https://github.com/NVIDIA/cutlass/tree/main/media/docs/cute
- **ThunderKittens**: https://github.com/karpathy/thunderkittens
- **CUTLASS 3.x**: https://github.com/NVIDIA/cutlass
- **TrikeShed PRELOAD.md**: Kernel algebra (Join/Series/Cursor) this builds on

---

*Part of TrikeShed's centroid-aligned architecture — the substrate for frontier lab hiring signals.*