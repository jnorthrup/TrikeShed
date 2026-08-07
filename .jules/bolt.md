## 2026-08-04 - Eliminate N+1 write calls in Posix I/O
**Learning:** Iteratively calling POSIX `write()` (even via chunked buffering if not sized perfectly to the workload) or relying on O(N) internal iterator loops over tiny payloads introduces measurable system call / context switching overhead in `linuxMain`.
**Action:** When saving a large collection of items (like a list of lines) to disk via POSIX `write()`, batch them entirely in userspace using `joinToString` with appropriate separators/postfixes, and then submit the final UTF-8 payload `ByteArray` in a single `write()` system call, drastically improving throughput (e.g. from 80ms to 35ms in a 100k iteration benchmark). Also, explicitly convert `payload.size.convert()` instead of summing `String.length` for accurate UTF-8 byte bounds.
## 2026-08-07 - Avoid O(N²) memory/eviction bottlenecks
**Learning:** Replacing chunked buffered I/O with  causes OOM regressions. Map eviction using  inside a loop causes O(N²) freezing.
**Action:** Always reconstruct map keys and use  for O(1) cache eviction.
## 2026-08-04 - Avoid O(N²) memory/eviction bottlenecks
**Learning:** Replacing chunked buffered I/O with `joinToString` causes OOM regressions. Map eviction using `removeAll` inside a loop causes O(N²) freezing.
**Action:** Always reconstruct map keys and use `.remove(key)` for O(1) cache eviction.
