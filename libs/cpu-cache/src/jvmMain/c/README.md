# JNI Native Library Build Instructions

The `libs/cpu-cache` module includes JNI bindings for POSIX `sysconf()` calls via `SysconfInterop.kt`. This allows generic POSIX cache interrogation on platforms where `/sys` or `sysctl` are not available.

## Building the Native Library

### Prerequisites
- C compiler (gcc or clang)
- JNI headers (usually included with JDK)
- Make or cmake

### Compilation

Compile the native library:
```bash
cd libs/cpu-cache/src/jvmMain/c
gcc -shared -fPIC -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" \
  -o libtrikeshed_cpucache.dylib SysconfInterop.c

# On Linux:
gcc -shared -fPIC -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -o libtrikeshed_cpucache.so SysconfInterop.c
```

### Library Path

The compiled library must be on the Java library path:
```bash
export LD_LIBRARY_PATH=/path/to/libs/cpu-cache/src/jvmMain/c:$LD_LIBRARY_PATH  # Linux
export DYLD_LIBRARY_PATH=/path/to/libs/cpu-cache/src/jvmMain/c:$DYLD_LIBRARY_PATH  # macOS
```

Or copy to system library path:
```bash
sudo cp libtrikeshed_cpucache.so /usr/local/lib/  # Linux
sudo cp libtrikeshed_cpucache.dylib /usr/local/lib/  # macOS
```

## Graceful Fallback

If the native library is not available, `interrogatePosixFallback()` will catch `UnsatisfiedLinkError` and return a partial topology with only the core count (via `Runtime.availableProcessors()`).

## MLIR LLVM Dialect Output

The `--llvm` flag generates a self-contained MLIR LLVM dialect module that calls `sysconf()` directly, which can be compiled standalone:
```bash
./gradlew :libs:cpu-cache:runJvmCompile --args="--llvm" > cache_probe.mlir
mlir-translate --mlir-to-llvmir cache_probe.mlir | clang -x ir - -o cache_probe
./cache_probe
```

This provides a pure MLIR path without requiring JNI.
