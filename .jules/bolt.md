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
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.
## 2024-05-18 - Avoid repeated list and string allocations in hot paths
**Learning:** Instantiating inline lists (e.g. `listOf("a", "b")`) and performing string interpolation inside hot loops or frequently executed coroutine blocks (like in KeyMuxDaemon) causes severe GC pressure and object allocations in Kotlin.
**Action:** Extract static collections and pre-compute interpolated string constants into top-level private properties (e.g., `DEFAULT_PROVIDERS`) so they are allocated only once.
