## 2024-05-20 - Avoid filterIsInstance for Collection Performance
**Learning:** Chaining `.filterIsInstance<T>()` and `.map { ... }` on iterables in Kotlin creates unnecessary intermediate `ArrayList` allocations.
**Action:** Use a single `for` loop with a type-check `if (item is T)` or `is T` to construct the final list directly, avoiding intermediate allocations.
## 2024-09-12 - Series Iteration Allocations
**Learning:** In TrikeShed, calling .toList() on collections (like maps/collections) or custom Series before operations like map or sortedBy causes unnecessary O(N) intermediate List allocations.
**Action:** For map on standard collections, avoid toList(); for operations like sortedBy on Series, use .view instead of .toList().
## 2024-11-20 - Avoid boxed List allocations from ByteArray
**Learning:** Calling `.toList()` on a primitive array like `ByteArray` creates an unnecessary boxed `List<Byte>` intermediate allocation (creating full `Byte` objects for every element) before passing it to functions like `.addAll()`. This increases memory pressure and garbage collection overhead, especially for large arrays (like PDF bodies or tree structures).
**Action:** When appending primitive arrays to standard collections, iterate directly over the array using a standard `for` loop (e.g., `for (b in bytes) list.add(b)`) instead of chaining `.toList()`.
