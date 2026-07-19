# Investigation: WASM Guest Execution on JS/WASM Targets

## Objective
Extend guest language capabilities beyond the JVM-only GraalVM Polyglot surface. Specifically, we want a mechanism where WebAssembly (WASM) modules can act as guest reducers for `Cursor` data across JS and WASM targets.

## Current State
- The `Cursor` structure is Kotlin multiplatform (commonMain).
- Guest execution today is JVM-only, utilizing GraalVM Polyglot (e.g. `GraalVmCursorHost`).
- In JVM, we use `org.graalvm.polyglot.proxy.ProxyArray` and `ProxyObject` to share the memory layout of the `Cursor` with JavaScript zero-copy.

## Future Architecture for WASM Guests
To support JS/WASM targets, we must evaluate the possibility of injecting a WASM module that consumes and returns cursors.

### Options
1. **WASM-in-JS (via `jsMain`)**:
   - WebAssembly APIs (`WebAssembly.instantiate`) are natively available in the browser.
   - The primary challenge is memory sharing. Kotlin/JS (or Kotlin/WasmJS) memory is not inherently directly shareable with another WebAssembly module without a common WASM memory object.
   - **Data Passing**: A `Cursor` is an abstract Kotlin tree (`Series<RowVec>`). To pass this to a WASM guest without deep copying, the guest must be able to call back into imported host functions (e.g., `get_row_count()`, `get_double(row, col)`) provided by the Kotlin/JS host, OR the cursor must be serialized into a flat `ByteBuffer` (Linear Memory) that the WASM module can read directly.

2. **WASM-in-WASM (via `wasmJsMain` / `wasmWasiMain`)**:
   - In a pure Wasm environment, the guest could be instantiated using WASI or similar embeddings.
   - Host functions (imports) can be provided by the Kotlin runtime to the guest.
   - Again, linear memory access is the most performant. If the `Cursor` can be backed by a contiguous `ByteArray` or `MemorySegment`, it could be shared directly with the guest's linear memory.

### Required Shape
The API should mirror `GraalVmCursorHost`:

```kotlin
// In commonMain or jsMain
interface WasmCursorHost {
    /**
     * Instantiates a WASM module from bytes, invokes the 'reduce' exported function,
     * passing a host-provided linear memory segment or imported function bindings,
     * and returns the resulting Cursor.
     */
    suspend fun reduceCursor(cursor: Cursor, wasmBytes: ByteArray): Cursor
}
```

### Recommendation for Next Steps
1. **Memory Layout**: Ensure `Cursor` can be backed by flat binary (e.g. using `IOMemento.IoBytes` or Confix serialization) rather than just heap objects, as WASM modules cannot read JS/Kotlin objects directly.
2. **Host Bindings**: Create a Kotlin/JS wrapper around `WebAssembly.instantiateStreaming` that injects `{ env: { get_cell: ... } }`.
3. **Guest Implementation**: Write a simple C/Rust guest that takes a `row_count` and `col_count` and calls `get_cell(r, c)`.
