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
## 2024-05-18 - Optimize SctpSackChunk Serialization with ByteBuffer
**Learning:** Using manual offset variables and primitive shifts for byte array serialization leads to less robust code compared to structured approaches. However, `borg.trikeshed.userspace.nio.ByteBuffer` provides a clean, fluent API that performs comparably for encoding and decoding while significantly improving maintainability. When migrating network serialization to `ByteBuffer`, careful attention must be paid to unsigned types. Using `.toInt()` directly on `.getShort()` causes sign-extension bugs for values >= 32768, which can break chunk bounds. Using `and 0xFFFF` correctly preserves the unsigned 16-bit integer representation.
**Action:** Replaced primitive offset-based encoding and decoding in `SctpSackChunk` with `borg.trikeshed.userspace.nio.ByteBuffer`'s fluent read/write methods (`put`, `putShort`, `putInt`, `getShort`, `getInt`), applying `and 0xFFFF` masks to prevent sign extension bugs on unsigned shorts.
