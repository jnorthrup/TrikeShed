# POSIX sysconf MLIR Implementation for cpu-cache

## Overview

Implemented POSIX `sysconf()` MLIR patterns and JNI bindings for generic cache interrogation across POSIX-compliant systems. This implementation follows the LLVM dialect MLIR pattern from `cache_geometry.mlir`.

## What Was Added

### 1. JNI Bindings (`SysconfInterop.kt` + `SysconfInterop.c`)
- Direct mapping to POSIX `sysconf(2)` constants from `<unistd.h>`
- Constants: `_SC_LEVEL1_DCACHE_SIZE` (188), `_SC_LEVEL1_ICACHE_SIZE` (189), etc.
- Graceful fallback on `UnsatisfiedLinkError` if native library unavailable
- Returns partial topology with `Runtime.availableProcessors()` as fallback

### 2. LLVM Dialect MLIR Generation (`MlirEncoding.kt`)
- `toLlvmDialrectModule()`: Generates self-contained MLIR module
- Follows exact pattern from `cache_geometry.mlir`:
  - `llvm.func @sysconf(i32) -> i64` - external declaration
  - `llvm.func @printf(!llvm.ptr, ...) -> i32` - variadic printf
  - `llvm.mlir.global` - string constants as `!llvm.array<N x i8>`
  - `llvm.call` - direct sysconf invocations
  - `llvm.mlir.addressof` - opaque pointers (LLVM 15+ style)
  - `vararg(!llvm.func<i32 (!llvm.ptr, ...)>)` - variadic function signature

### 3. Platform Interrogation (`JvmCpuCache.kt`)
- `interrogatePosixFallback()`: Generic POSIX sysconf() path
- Called when OS is not Linux or macOS
- Graceful error handling with partial topology

### 4. CLI Support (`Main.kt`)
- `--llvm` flag: Outputs LLVM dialect MLIR module
- Independent of actual topology values (self-contained probe)

### 5. Tests (`MlirEncodingTest.kt`)
- `testSysconfConstants()`: Verify POSIX constant values
- `testToLlvmDialectModule()`: Verify complete LLVM MLIR structure
- `testToLlvmDialectModuleWithNulls()`: Verify module generation with null values

## MLIR Dialect Shapes

### A. Integer Scalars
```
i32, i64 (Standard MLIR types)
→ LLVM IR: i32, i64
→ C/POSIX: int, long
```

### B. Global CharSequence Arrays
```
!llvm.array<N x i8> (Fixed-width char arrays)
→ LLVM IR: [N x i8]
→ C/POSIX: char[N]
```

### C. Opaque Pointers
```
!llvm.ptr (LLVM 15+ style)
→ LLVM IR: ptr
→ C/POSIX: char*, void*
```

### D. Variadic Functions
```
(!llvm.ptr, ...) -> i32
→ LLVM IR: i32 (ptr, ...)
→ C/POSIX: int printf(const char* format, ...)
```

## Usage Examples

### 1. Generate LLVM Dialect MLIR
```kotlin
val topology = interrogateCpuCache()
val llvmMlir = CpuCacheMlir.toLlvmDialrectModule(topology)
println(llvmMlir)
```

Output:
```mlir
module {
  llvm.mlir.global internal constant @fmt_l1_size("L1 D-Cache Size: %ld bytes\0A\00") : !llvm.array<28 x i8>
  llvm.func @sysconf(i32) -> i64
  llvm.func @printf(!llvm.ptr, ...) -> i32
  llvm.func @main() -> i32 {
    %c_l1_size = llvm.mlir.constant(188 : i32) : i32
    %l1_size = llvm.call @sysconf(%c_l1_size) : (i32) -> i64
    %ptr_l1_size = llvm.mlir.addressof @fmt_l1_size : !llvm.ptr
    llvm.call @printf(%ptr_l1_size, %l1_size) vararg(!llvm.func<i32 (!llvm.ptr, ...)>) : (!llvm.ptr, i64) -> i32
    %ret = llvm.mlir.constant(0 : i32) : i32
    llvm.return %ret : i32
  }
}
```

### 2. Compile and Run MLIR Module
```bash
# Generate MLIR
java -cp libs/cpu-cache/build/libs/*.jar borg.trikeshed.cpucache.Main --llvm > cache_probe.mlir

# Compile to native binary
mlir-translate --mlir-to-llvmir cache_probe.mlir | clang -x ir - -o cache_probe

# Run
./cache_probe
```

### 3. Use JNI from Kotlin
```kotlin
val topology = SysconfInterop.interrogateSysconf()
// Falls back gracefully if library not loaded
```

## Building the JNI Library

```bash
cd libs/cpu-cache/src/jvmMain/c

# macOS
gcc -shared -fPIC -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
  -o libtrikeshed_cpucache.dylib SysconfInterop.c

# Linux
gcc -shared -fPIC -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -o libtrikeshed_cpucache.so SysconfInterop.c
```

See `libs/cpu-cache/src/jvmMain/c/README.md` for details.

## Test Coverage

All tests passing:
- POSIX sysconf constants verification
- LLVM dialect module structure
- Opaque pointers and variadic functions
- Global string constants
- Graceful fallback on JNI load failure

```bash
./gradlew :libs:cpu-cache:jvmTest
```

## Platform Support

- **Linux**: `/sys/devices/system/cpu/cpu0/cache/*` (existing)
- **macOS**: `sysctl hw.l1dcachesize` etc. (existing)
- **Generic POSIX**: `sysconf(_SC_LEVEL1_DCACHE_SIZE)` via JNI (new)

## Confix Integration

Confix is NOT used for output from cpu-cache. It may be used as a parsing utility for input processing if needed. Default output remains Confix JSON for compatibility, but MLIR formats are available via `--mlir` and `--llvm` flags.

## References

- POSIX `sysconf(3)`: https://man7.org/linux/man-pages/man3/sysconf.3.html
- LLVM Dialect MLIR: https://mlir.llvm.org/docs/Dialects/LLVM/
- cache_geometry.mlir: Original reference implementation
