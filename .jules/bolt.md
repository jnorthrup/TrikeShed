## 2024-08-14 - Collection processing in commonMain
**Learning:** `CausalKernel.kt` contains performance bottlenecks like O(n^2) nested list map `.toSet()` conversions inside causal graph functions, generating intermediate arrays and sets per event processed in `inPhase()` and `rankByProximity()`.
**Action:** Avoid temporary boxing/List allocations from `.map` by using pre-sized `ArrayList`, and replace repetitive linear collection scans (like `phaseOf(it)` over many distinct workIds) with single-pass `LinkedHashMap` aggregations that preserve causal iteration ordering.

## 2026-08-09 - Handle rollover explicitly in relative eviction formulas
**Learning:** When implementing mathematical cache eviction logic based on monotonic counter thresholds (e.g., `counter % 1000 == 0`), relative loop-based eviction formulas may fail to fire or calculate incorrectly immediately after the counter rolls over.
**Action:** Ensure you explicitly handle the counter rollover state (e.g., `counter == 0L`) by clearing the cache (`map.clear()`).

## 2024-05-18 - Replacing `.toList().map` on Series with `.toArray()` avoids intermediate list allocations
**Learning:** `Series` (`Join<Int, (Int) -> T>`) map transformations that are then converted to typed arrays often use `.toList().map(xform).toTypedArray()`, which allocates both an intermediate list and often redundant objects. Trikeshed's canonical way to fix this and perform zero-allocation intermediate transformations is to use the `(size j { i -> xform(this[i]) }).toArray()` idiom, which uses the `toArray` extension function to generate the target array directly in one shot.
**Action:** Always search for `.toList().map` operations applied to `Series` objects when returning arrays, and replace them with the `.toArray()` idiom directly mapping the Series via `j` operator.

## 2026-08-04 - Eliminate N+1 write calls in Posix I/O
**Learning:** Iteratively calling POSIX `write()` (even via chunked buffering if not sized perfectly to the workload) or relying on O(N) internal iterator loops over tiny payloads introduces measurable system call / context switching overhead in `linuxMain`.
**Action:** When saving a large collection of items (like a list of lines) to disk via POSIX `write()`, batch them entirely in userspace using `joinToString` with appropriate separators/postfixes, and then submit the final UTF-8 payload `ByteArray` in a single `write()` system call, drastically improving throughput (e.g. from 80ms to 35ms in a 100k iteration benchmark). Also, explicitly convert `payload.size.convert()` instead of summing `String.length` for accurate UTF-8 byte bounds.

## 2026-08-04 - Handle partial writes safely in Posix I/O optimizations
**Learning:** When optimizing POSIX `write()` by batching multiple lines into a single `ByteArray` payload in userspace, it is crucial not to blindly assume a single `write()` system call will complete successfully. System limits or signal interruptions can cause partial writes.
**Action:** Always use a `while (writtenTotal < totalSize)` loop to retry/resume the `write()` system call, shifting the memory pointer (`addressOf(writtenTotal.toInt())`) appropriately, to ensure safe and complete data delivery in native file operations.

## 2026-08-07 - Avoid O(N²) memory/eviction bottlenecks
**Learning:** Replacing chunked buffered I/O with `joinToString` causes OOM regressions. Map eviction using `removeAll` inside a loop causes O(N²) freezing.
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.

## 2024-05-18 - Removing redundant list allocation when mapping arrays
**Learning:** When mapping `vararg` parameters or `Array` types in Kotlin, calling `.toList().map { ... }` creates a redundant intermediate `ArrayList` which is immediately discarded. Kotlin standard library has `.map { ... }` extension on Arrays.
**Action:** Avoid calling `.toList()` before `.map { ... }` on varargs or arrays to prevent unnecessary memory allocation and reduce GC overhead.

## 2026-08-07 - Avoid intermediate ArrayList allocation in array mapping
**Learning:** When applying map transformations to `Array` types (such as `vararg` parameters) that will be immediately converted to another collection (e.g., `Series`), calling `.toList().map { ... }` is inefficient. It allocates a redundant, intermediate `ArrayList`. Kotlin arrays support the `.map` function natively.
**Action:** Always map arrays directly (e.g., `hdrs.map { ... }`) instead of chaining `.toList().map { ... }` to avoid unnecessary intermediate allocations and reduce GC pressure.

## 2024-05-18 - Optimize Cache Eviction based on Monotonic Counters
**Learning:** Using `map.keys.removeAll` with lambda predicates on continuously growing maps causes linear scanning and acts as an O(N²) bottleneck over time.
**Action:** Compute/reconstruct the sequence of map keys directly using loop-based math and use `map.remove(key)` for deterministic O(1) removal, and explicitly handle rollover zero-state with `map.clear()`.

## 2024-06-25 - map.keys.removeAll triggers O(N²) bottleneck
**Learning:** In Kotlin, using `map.keys.removeAll { ... }` or `map.entries.removeAll { ... }` triggers a linear scan over the map elements, which can create a severe O(N²) bottleneck during cache eviction loops if called frequently.
**Action:** When removing specific, mathematically predictable entries from a Kotlin Map (like during chronological cache eviction or rollover), reconstruct the map keys directly and use `map.remove(key)` within a loop to ensure O(1) deletion per key.

## 2024-11-25 - Async I/O for Temp File Cleanup
**Learning:** Iterative file deletion (`tempDir.listFiles()?.forEach { it.delete() }`) is a blocking N+1 I/O operation which can stall the main execution thread, especially if there are many files (like video frames).
**Action:** Replaced iterative deletion with `Thread { tempDir.deleteRecursively() }.start()` to offload the I/O work to a background thread, resolving the stall.

## 2024-05-18 - Optimize SctpSackChunk Serialization with ByteBuffer
**Learning:** Using manual offset variables and primitive shifts for byte array serialization leads to less robust code compared to structured approaches. However, `borg.trikeshed.userspace.nio.ByteBuffer` provides a clean, fluent API that performs comparably for encoding and decoding while significantly improving maintainability. When migrating network serialization to `ByteBuffer`, careful attention must be paid to unsigned types. Using `.toInt()` directly on `.getShort()` causes sign-extension bugs for values >= 32768, which can break chunk bounds. Using `and 0xFFFF` correctly preserves the unsigned 16-bit integer representation.
**Action:** Replaced primitive offset-based encoding and decoding in `SctpSackChunk` with `borg.trikeshed.userspace.nio.ByteBuffer`'s fluent read/write methods (`put`, `putShort`, `putInt`, `getShort`, `getInt`), applying `and 0xFFFF` masks to prevent sign extension bugs on unsigned shorts.

## 2024-05-18 - Avoid repeated list and string allocations in hot paths
**Learning:** Instantiating inline lists (e.g. `listOf("a", "b")`) and performing string interpolation inside hot loops or frequently executed coroutine blocks (like in KeyMuxDaemon) causes severe GC pressure and object allocations in Kotlin.
**Action:** Extract static collections and pre-compute interpolated string constants into top-level private properties (e.g., `DEFAULT_PROVIDERS`) so they are allocated only once.

## 2024-08-11 - Replace filterValues().keys.toList() with entries.removeAll() for zero-allocation map eviction
**Learning:** In Kotlin, replacing functional chains like `map.filterValues { ... }.keys.toList()` followed by map removal inside a loop with the in-place iterator method `map.entries.removeAll { ... }` changes the operation to an efficient O(N) zero-allocation removal, avoiding redundant intermediate collection instances.
**Action:** Always refactor iterative functional map evictions to use `.entries.removeAll { ... }` instead of building intermediate Lists and HashMaps.

## 2024-05-18 - Avoid O(N^2) bottlenecks when resolving causal phases
**Learning:** `CausalGraph.inPhase` previously resolved the distinct set of `workId`s and mapped each one individually using a backwards scan `phaseOf(it)`, producing an O(N^2) time complexity.
**Action:** When deriving phase maps over a casual graph or continuous event stream, use a single-pass `LinkedHashMap` to maintain the causal ordinal order while maintaining O(N) linear time execution.
## 2026-07-22 - CouchDB JavaScript view reduce function memory limits
**Learning:** CouchDB JavaScript view reduce functions in `ViewServer.kt` (executed via GraalVM in Trikeshed) can cause excessive allocations and `RangeError: Maximum call stack size exceeded` if intermediate arrays are created via `.map` and grouped into `.add(row)`.
**Action:** Replaced allocating a whole list per key in `.reduceCount()`, `.reduceSum()`, and `.reduceStats()` and iterating over them in `ViewServer.kt`.

## 2024-05-24 - Avoiding intermediate List allocations in `filterIsInstance` followed by `any`, `all`, `none`, `firstOrNull`
**Learning:** Using `.filterIsInstance<T>()` before short-circuiting operations like `.any { ... }`, `.all { ... }`, `.none { ... }`, or `.firstOrNull { ... }` generates an intermediate `ArrayList` containing all elements of type `T`. This creates unnecessary memory overhead, particularly on large sequences or frequently updated collections (like event buses or AST parsing).
**Action:** Replace `.filterIsInstance<T>().any { ... }` with `.any { it is T && ... }`, and apply analogous transformations for `all` (with `it !is T || ...`), `none`, and `firstOrNull`. This preserves the short-circuiting behavior while eliminating the intermediate collection allocation.
