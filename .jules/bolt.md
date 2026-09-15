## 2024-05-20 - Avoid filterIsInstance for Collection Performance
**Learning:** Chaining `.filterIsInstance<T>()` and `.map { ... }` on iterables in Kotlin creates unnecessary intermediate `ArrayList` allocations.
**Action:** Use a single `for` loop with a type-check `if (item is T)` or `is T` to construct the final list directly, avoiding intermediate allocations.
## 2024-09-12 - Series Iteration Allocations
**Learning:** In TrikeShed, calling .toList() on collections (like maps/collections) or custom Series before operations like map or sortedBy causes unnecessary O(N) intermediate List allocations.
**Action:** For map on standard collections, avoid toList(); for operations like sortedBy on Series, use .view instead of .toList().
## 2024-10-24 - Avoid map iteration snapshots
**Learning:** Snapshotting a map's values via `.toList()` inside a hot loop or a parser just to avoid `ConcurrentModificationException` creates unnecessary $O(N)$ allocations.
**Action:** Iterate the map directly and defer new insertions into a temporary collection, then merge them back after iteration completes.
