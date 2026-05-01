# libs/cpu-cache

## What It Is

`libs/cpu-cache` is a **cross-platform CPU cache topology interrogator**. It
uses Kotlin `expect/actual` to query L1/L2/L3 cache sizes, cache line size,
and core count on each supported platform, then exposes the result as a
`CpuCacheTopology` data class.

The module has **no dependency on the root TrikeShed algebra** (`rootMode=none`).
It is a standalone utility that produces Confix JSON (JSON-compatible) output
suitable for consumption by TrikeShed's Confix parser.

### Platform Implementations

| Source Set | Strategy | Coverage |
|---|---|---|
| `jvmMain` | `/sys/devices/system/cpu/cpu0/cache/*` on Linux; `sysctl` on macOS; all-null fallback otherwise | L1d, L1i, L2, L3, cache line, cores |
| `nativeMain` | POSIX `sysconf(_SC_LEVEL*_CACHE_SIZE)` + `_SC_NPROCESSORS_ONLN` | L1d, L1i, L2, L3, cores (no cache line) |
| `macosX64Main` | `sysconf(_SC_NPROCESSORS_ONLN)` only | cores only (macOS lacks POSIX cache-size constants) |
| `macosArm64Main` | Same as macosX64 | cores only |
| `linuxX64Main` | Full POSIX `sysconf` with `_SC_LEVEL*` constants | L1d, L1i, L2, L3, cores |
| `linuxArm64Main` | Full POSIX `sysconf` with `_SC_LEVEL*` constants | L1d, L1i, L2, L3, cores |
| `macosMain` | `TODO("Not yet implemented")` | **Stub — will crash** |
| `linuxMain` | `TODO("Not yet implemented")` | **Stub — will crash** |
| `wasmJsMain` | All-null sentinel | No cache info available |
| `jsMain` | All-null sentinel | No cache info available |

The `macosMain` and `linuxMain` source sets are **stubs that throw TODO**.
The actual native implementations live in the arch-specific source sets
(`macosX64Main`, `macosArm64Main`, `linuxX64Main`, `linuxArm64Main`), which
take precedence over the generic `macosMain`/`linuxMain` when both are present.

## Source Layout

```
src/
  commonMain/kotlin/borg/trikeshed/cpucache/
    CpuCacheTopology.kt    -- data class + expect fun interrogateCpuCache() + toConfix()
    Main.kt                -- CLI entry point: println(interrogateCpuCache().toConfix())

  jvmMain/kotlin/borg/trikeshed/cpucache/
    JvmCpuCache.kt         -- actual: Linux /sys + macOS sysctl + fallback

  nativeMain/kotlin/borg/trikeshed/cpucache/
    NativeCpuCache.kt      -- actual: POSIX sysconf (shared Linux code)

  macosX64Main/.../NativeCpuCache.kt   -- actual: cores-only (macOS)
  macosArm64Main/.../NativeCpuCache.kt -- actual: cores-only (macOS)
  linuxX64Main/.../NativeCpuCache.kt   -- actual: full POSIX sysconf
  linuxArm64Main/.../NativeCpuCache.kt -- actual: full POSIX sysconf

  macosMain/.../CpuCacheTopology.macos.kt  -- actual: TODO stub
  linuxMain/.../CpuCacheTopology.linux.kt  -- actual: TODO stub

  wasmJsMain/.../WasmCpuCache.kt    -- actual: all-null
  jsMain/.../JsCpuCache.kt          -- actual: all-null
```

## Key/Element Status

This module has **no Key/Element types** and no dependency on the TrikeShed
kernel algebra. It is a standalone utility.

| Symbol | Classification | Notes |
|---|---|---|
| `CpuCacheTopology` | Pure data class | 6 nullable fields; no lifecycle |
| `interrogateCpuCache()` | `expect fun` → platform actuals | Pure query; returns immediately |
| `CpuCacheTopology.toConfix()` | Extension fun | Stateless serialization |
| `main()` | CLI entry point | Prints Confix JSON to stdout |

Nothing here should become an `AsyncContextKey` or `AsyncContextElement`. The
module is pure query/serialization with no state, no lifecycle, and no async.

## Dependencies

- **No root project dependency** (`rootMode=none` in gradle macro).
- **No kotlinx-coroutines** (`coroutinesMain=false`).
- **JVM only**: `java.io.File` for `/sys` filesystem reads.
- **Native only**: `platform.posix.sysconf` and `_SC_*` constants via
  `kotlinx.cinterop`.

## Build

Full KMP module (`kmpFull(rootMode="none", coroutinesMain=false)`) with extra
targets:
- JVM 21, JS (nodejs), wasmJs (nodejs)
- macOS: macosArm64 (primary), macosX64 (additional)
- Linux: linuxX64 (primary), linuxArm64 (additional)

Includes a Gradle task `interrogateCpu` that runs the JVM `main()` to print
cache topology.
