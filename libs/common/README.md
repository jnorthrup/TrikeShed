# libs/common

## What It Is

`libs/common` is the integration scaffold module for the TrikeShed kernel algebra.
It provides:

1. **A now-empty re-export shim** (`TrikeShedLib.kt`) — historically re-exported
   root kernel symbols; currently kept only to prevent stale JS/source-map
   artifacts from shadowing the real definitions in the root project.
2. **JVM SPI loader functions** (`NioSpiLoader.kt`) — `java.util.ServiceLoader`
   lookups for `UserspaceNioSpi` and `LiburingFacadeSpi`, which are interfaces
   defined in the root project's `borg.trikeshed.context` package.
3. **Common-test invariants** — three test suites that exercise the root
   project's `AsyncContextKey` / `AsyncContextElement` / `ElementState` types
   from within this module's classpath.

The module does **not** define any new Key/Element types itself. It tests the
root algebra's types to prove they are reachable from downstream modules.

## Source Layout

```
src/
  commonMain/kotlin/borg/trikeshed/lib/
    TrikeShedLib.kt          -- empty shim (prevents stale artifact shadowing)

  jvmMain/kotlin/borg/trikeshed/context/
    NioSpiLoader.kt          -- loadUserspaceNioSpi(), loadLiburingFacadeSpi()

  commonTest/kotlin/borg/trikeshed/context/
    AsyncContextSupervisorTest.kt   -- Key identity, context lookup, lifecycle
    ElementStateBitMaskedTest.kt    -- ElementState ordinal/mask/invariants
    AsyncContextInvariantsTest.kt   -- cross-element isolation, lifecycle flow
```

## Key/Element Status

| Artifact | Status | Notes |
|---|---|---|
| `TrikeShedLib.kt` | **Empty** | No symbols emitted; placeholder only |
| `NioSpiLoader.kt` functions | **Pure** (no Key needed) | Stateless `ServiceLoader` lookups |
| `UserspaceNioSpi` | Defined in root | `AsyncContextKey`-routable SPI interface |
| `LiburingFacadeSpi` | Defined in root | `AsyncContextKey`-routable SPI interface |
| Test helpers (`ElementA`, `ElementB`, `TestElementX/Y/Z`) | Test-only Keys/Elements | Not production code |

This module has **no production Key/Element types** of its own. It is a
consumer/validator of root-project types.

## Dependencies

- **Root project** (`org.bereft:TrikeShed`) — `api` dependency via
  `trikeshed-lib.gradle` (`kmpFull()` with default `rootMode="api"`).
  Brings in the full kernel algebra (`Series`, `TwInt`, `Join`, `Cursor`,
  `AsyncContextKey`, `AsyncContextElement`, `ElementState`, `BitMasked`, etc.).
- **kotlinx-coroutines-core** — via shared gradle macro.
- **kotlinx-coroutines-test** — test dependency.
- **JVM only**: `java.util.ServiceLoader` (JDK standard).

## Build

Configured via `../../gradle/macros/trikeshed-lib.gradle` as a full KMP module
(`kmpFull()`) with JVM, JS, WASM, and native targets. Targets: JVM 21, JS
(nodejs), wasmJs (nodejs), plus one native target (macosArm64 or linuxX64
depending on host).
