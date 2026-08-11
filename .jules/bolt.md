<<<<<<< HEAD
**Action:** Ensure you explicitly handle the counter rollover state (e.g., `counter == 0L`) by clearing the cache (`map.clear()`).**Learning:** When implementing mathematical cache eviction logic based on monotonic counter thresholds (e.g., `counter % 1000 == 0`), relative loop-based eviction formulas may fail to fire or calculate incorrectly immediately after the counter rolls over.## 2026-08-09 - Handle rollover explicitly in relative eviction formulas
=======
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
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.## 2024-05-18 - Removing redundant list allocation when mapping arrays
**Learning:** When mapping `vararg` parameters or `Array` types in Kotlin, calling `.toList().map { ... }` creates a redundant intermediate `ArrayList` which is immediately discarded. Kotlin standard library has `.map { ... }` extension on Arrays.
**Action:** Avoid calling `.toList()` before `.map { ... }` on varargs or arrays to prevent unnecessary memory allocation and reduce GC overhead.
>>>>>>> origin/bolt/remove-redundant-tolist-map-7116920748480217338
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
