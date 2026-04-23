# PRELOAD → bbcursive conversion checklist

This document maps the repository-level PRELOAD.md algebra and design biases to the existing `libs/bbcursive` Java sources. It is intentionally a planning / scaffolding artifact so we can apply conversions incrementally and safely.

Goals
- Align bbcursive IO/parsing primitives with TrikeShed Kernel Algebra conventions (Join/Twin/Series, `j`, `α`, `↺`).
- Prefer lazy projections and minimal materialization.
- Provide Kotlin adapters in `commonMain` so the rest of the multiplatform code can consume idiomatic Series/Cursor shapes while leaving Java code working during the transition.

High-level strategy
1. Add thin Kotlin adapter layer that exposes the Java primitives as Series/Join-friendly APIs.
2. Add tests (commonTest/jvmTest) that assert parity between current Java behavior and new adapter behavior.
3. Gradually port or reimplement hot-path utilities in Kotlin using Series and Cursor semantics.
4. When parity is established, replace Java callers with Kotlin APIs and consider porting Java sources to Kotlin (surgical, per-file).

Per-file notes (initial sweep)

- Cursive.java
  - Contains UnaryOperator<ByteBuffer> atoms (pre/post enums). Create a Kotlin `bbcursive.adapters` file exposing these atoms as `UnaryOperator<ByteBuffer>` adapters and also as `Series<Byte>`/`Series<Char>` friendly lambdas.
  - Provide `fun bb(series: Series<Byte>, vararg ops: (ByteBuffer)->ByteBuffer): ByteBuffer` wrapper and `fun bb(bytes: Series<Char>, ...)` delegations.

- std.java
  - Heavy with ByteBuffer helpers (bb, cat, alloc, str, consumeNumber, etc.). Add Kotlin wrapper functions in `libs/bbcursive/src/commonMain/kotlin/bbcursive/StdAdapters.kt` that accept Series types and offer `α`-style projections.
  - Keep the Java methods as the implementation backing the adapters to minimize risk.

- Allocator.java
  - Adapter surface: expose `allocate(int)` via a Kotlin interface and a small factory in `commonMain` that can be switched to a Series-backed allocator later.

- lib/* and vtables/*
  - These are lower-level primitives. Create a `libs/bbcursive/src/commonMain/kotlin/bbcursive/vtables/README.md` summarizing required API shape changes (Join-based types, remove object mutation where possible).

Incremental work items (first PR)
- Add `libs/bbcursive/PRELOAD.md` (this file).
- Add `libs/bbcursive/src/commonMain/kotlin/bbcursive/Adapters.kt` (initial skeleton delegating to existing Java functions).
- Add tests under `libs/bbcursive/src/commonTest` verifying adapter parity for a small set of atoms (rewind, skipWs, toEol, bb concatenation).
- Run `./gradlew :libs:common:build :libs:htx-client:openApiGenerateHtxGeneralClient` and unit tests.

Verification & CI
- Run the project build and relevant jvm tests after adding adapters. The change is additive and should not break existing Java consumers.

Notes / future port
- Full port to Kotlin (removing Java) is non-trivial; do it file-by-file after tests and adapters are green.
- Consider exposing a `Series<Byte>` facade in adapters so higher-level code can use `α` and other kernel primitives directly.

If this plan is acceptable, next step: create the adapter skeleton (Adapters.kt) and a small smoke test for `bb` and `str` behaviour.```