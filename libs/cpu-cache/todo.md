# libs/cpu-cache — TODO

## Boundary Audit

### Current State

All types are pure data + pure functions. No Key/Element classification needed.
The module is correctly standalone with zero TrikeShed algebra dependency.

### Key/Element Classification

| Symbol | Classification | Action |
|---|---|---|
| `CpuCacheTopology` | Data class — not a Key/Element candidate | Keep as-is |
| `interrogateCpuCache()` | Pure expect/actual query | Keep as-is |
| `toConfix()` | Extension function | Keep as-is |
| `main()` | CLI entry point | Keep as-is |

### Findings

1. **`macosMain` and `linuxMain` are TODO stubs.** They contain
   `TODO("Not yet implemented")` which will crash at runtime if invoked.
   These source sets are masked by the arch-specific actuals
   (`macosX64Main`, `macosArm64Main`, `linuxX64Main`, `linuxArm64Main`),
   but they represent a latent hazard — if the build system resolves them
   for a target not covered by the arch-specific sets, they will crash.

2. **macOS native targets return only `coreCount`.** The macOS arch-specific
   actuals (`macosX64Main`, `macosArm64Main`) use `sysconf(_SC_NPROCESSORS_ONLN)`
   but return null for all cache sizes. The JVM `interrogateMacOs()` correctly
   uses `sysctl` to get cache sizes. The native macOS targets should do the
   same via `posix` `sysctl` interop.

3. **Duplicate native implementations.** `linuxX64Main` and `linuxArm64Main`
   contain identical code (full POSIX sysconf). `macosX64Main` and
   `macosArm64Main` are also identical. These could be consolidated into
   `linuxMain` and `macosMain` respectively, replacing the TODO stubs.

4. **`nativeMain` actual is unreachable.** The `nativeMain` source set provides
   a full POSIX sysconf implementation, but the arch-specific source sets
   override it for every target. The `nativeMain` actual is dead code.

5. **No tests.** There are no `commonTest` or platform test source sets. This
   is understandable (hardware-dependent), but `toConfix()` serialization and
   the data class are testable.

6. **Unused imports in native files.** `linuxX64Main` and `linuxArm64Main`
   import `kotlinx.cinterop.alloc`, `memScoped`, and `ptr` but never use them.

## Integration Steps

1. **Consolidate native actuals**: Move the full POSIX sysconf implementation
   from `linuxX64Main`/`linuxArm64Main` into `linuxMain` (replacing the TODO
   stub). Similarly for macOS if `sysctl` interop can work from `macosMain`.

2. **Add macOS native sysctl**: Implement cache size queries in the macOS
   native actuals using `platform.posix.sysctl` interop (matching the JVM
   `interrogateMacOs()` logic).

3. **Remove dead `nativeMain` actual**: Once `linuxMain` and `macosMain` have
   real implementations, the `nativeMain` actual becomes truly dead code.
   Consider removing it or converting it to a fallback.

4. **Add `commonTest`**: Test `CpuCacheTopology` data class equality,
   `toConfix()` output format, and null-field handling. These are
   platform-independent.

5. **Integrate with other libs**: `CpuCacheTopology.cacheLineBytes` can drive
   cache-line-aligned allocation in memory-sensitive modules (e.g., `libs/uring`,
   `libs/quic`). Add `libs/cpu-cache` as an optional dependency for those
   modules.

## Path to Stable

- [x] Fix `macosMain` TODO stub — provide real implementation or confirm arch-specific override is always used
- [x] Fix `linuxMain` TODO stub — consolidate from `linuxX64Main`/`linuxArm64Main`
- [x] Remove unused imports in native actuals
- [x] Remove or repurpose dead `nativeMain` actual
- [x] Add macOS native `sysctl` interop for cache sizes (currently JVM-only)
- [x] Add `commonTest` for `CpuCacheTopology` and `toConfix()`
- [x] Document platform coverage matrix in code comments
- [x] Consider adding a `windowsMain` actual (currently no Windows support)
- [x] Add gradle task to run native binary `interrogateCpu` (currently JVM-only task)
- [x] Mark stable once all platform actuals return real data (or documented null fallbacks)
