**Action:** Ensure you explicitly handle the counter rollover state (e.g., `counter == 0L`) by clearing the cache (`map.clear()`).**Learning:** When implementing mathematical cache eviction logic based on monotonic counter thresholds (e.g., `counter % 1000 == 0`), relative loop-based eviction formulas may fail to fire or calculate incorrectly immediately after the counter rolls over.## 2026-08-09 - Handle rollover explicitly in relative eviction formulas
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
**Learning:** Replacing chunked buffered I/O with  causes OOM regressions. Map eviction using  inside a loop causes O(N²) freezing.
**Action:** Always reconstruct map keys and use  for O(1) cache eviction.

## 2026-08-04 - Avoid O(N²) memory/eviction bottlenecks
**Learning:** Replacing chunked buffered I/O with `joinToString` causes OOM regressions. Map eviction using `removeAll` inside a loop causes O(N²) freezing.
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
<<<<<<< HEAD
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.## 2024-05-18 - Removing redundant list allocation when mapping arrays
**Learning:** When mapping `vararg` parameters or `Array` types in Kotlin, calling `.toList().map { ... }` creates a redundant intermediate `ArrayList` which is immediately discarded. Kotlin standard library has `.map { ... }` extension on Arrays.
**Action:** Avoid calling `.toList()` before `.map { ... }` on varargs or arrays to prevent unnecessary memory allocation and reduce GC overhead.
=======
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.
## 2026-08-07 - Avoid intermediate ArrayList allocation in array mapping
**Learning:** When applying map transformations to `Array` types (such as `vararg` parameters) that will be immediately converted to another collection (e.g., `Series`), calling `.toList().map { ... }` is inefficient. It allocates a redundant, intermediate `ArrayList`. Kotlin arrays support the `.map` function natively.
**Action:** Always map arrays directly (e.g., `hdrs.map { ... }`) instead of chaining `.toList().map { ... }` to avoid unnecessary intermediate allocations and reduce GC pressure.
>>>>>>> origin/bolt/remove-tolist-map-array-17707417310673242503
=======
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.
## 2024-05-18 - Optimize Cache Eviction based on Monotonic Counters
**Learning:** Using `map.keys.removeAll` with lambda predicates on continuously growing maps causes linear scanning and acts as an O(N²) bottleneck over time.
**Action:** Compute/reconstruct the sequence of map keys directly using loop-based math and use `map.remove(key)` for deterministic O(1) removal, and explicitly handle rollover zero-state with `map.clear()`.
>>>>>>> origin/bolt/optimize-cache-eviction-9081893584752006795
=======
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.
## 2026-08-09 - Handle rollover explicitly in relative eviction formulas
**Learning:** When implementing mathematical cache eviction logic based on monotonic counter thresholds (e.g., `counter % 1000 == 0`), relative loop-based eviction formulas may fail to fire or calculate incorrectly immediately after the counter rolls over.
**Action:** Ensure you explicitly handle the counter rollover state (e.g., `counter == 0L`) by clearing the cache (`map.clear()`).
>>>>>>> origin/bolt/nuid-fanout-eviction-optimization-1126253336503657440
=======
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.

## 2024-06-25 - map.keys.removeAll triggers O(N²) bottleneck
**Learning:** In Kotlin, using `map.keys.removeAll { ... }` or `map.entries.removeAll { ... }` triggers a linear scan over the map elements, which can create a severe O(N²) bottleneck during cache eviction loops if called frequently.
**Action:** When removing specific, mathematically predictable entries from a Kotlin Map (like during chronological cache eviction or rollover), reconstruct the map keys directly and use `map.remove(key)` within a loop to ensure O(1) deletion per key.
>>>>>>> origin/bolt/optimize-claim-eviction-16750401730855570029
=======
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.
## 2024-11-25 - Async I/O for Temp File Cleanup
**Learning:** Iterative file deletion (`tempDir.listFiles()?.forEach { it.delete() }`) is a blocking N+1 I/O operation which can stall the main execution thread, especially if there are many files (like video frames).
**Action:** Replaced iterative deletion with `Thread { tempDir.deleteRecursively() }.start()` to offload the I/O work to a background thread, resolving the stall.
>>>>>>> origin/async-file-delete-4598793832052409559
=======
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.
## 2024-05-18 - Optimize SctpSackChunk Serialization with ByteBuffer
**Learning:** Using manual offset variables and primitive shifts for byte array serialization leads to less robust code compared to structured approaches. However, `borg.trikeshed.userspace.nio.ByteBuffer` provides a clean, fluent API that performs comparably for encoding and decoding while significantly improving maintainability. When migrating network serialization to `ByteBuffer`, careful attention must be paid to unsigned types. Using `.toInt()` directly on `.getShort()` causes sign-extension bugs for values >= 32768, which can break chunk bounds. Using `and 0xFFFF` correctly preserves the unsigned 16-bit integer representation.
**Action:** Replaced primitive offset-based encoding and decoding in `SctpSackChunk` with `borg.trikeshed.userspace.nio.ByteBuffer`'s fluent read/write methods (`put`, `putShort`, `putInt`, `getShort`, `getInt`), applying `and 0xFFFF` masks to prevent sign extension bugs on unsigned shorts.
>>>>>>> origin/jules-performance-sctpsackchunk-4402318700950035386
=======
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.
## 2024-05-18 - Avoid repeated list and string allocations in hot paths
**Learning:** Instantiating inline lists (e.g. `listOf("a", "b")`) and performing string interpolation inside hot loops or frequently executed coroutine blocks (like in KeyMuxDaemon) causes severe GC pressure and object allocations in Kotlin.
**Action:** Extract static collections and pre-compute interpolated string constants into top-level private properties (e.g., `DEFAULT_PROVIDERS`) so they are allocated only once.
>>>>>>> origin/perf/keymux-daemon-lookup-7382083278518723629
=======
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.
## 2024-08-11 - Replace filterValues().keys.toList() with entries.removeAll() for zero-allocation map eviction
**Learning:** In Kotlin, replacing functional chains like `map.filterValues { ... }.keys.toList()` followed by map removal inside a loop with the in-place iterator method `map.entries.removeAll { ... }` changes the operation to an efficient O(N) zero-allocation removal, avoiding redundant intermediate collection instances.
**Action:** Always refactor iterative functional map evictions to use `.entries.removeAll { ... }` instead of building intermediate Lists and HashMaps.
>>>>>>> origin/bolt-perf-optimization-6134959520020765618
