## 2024-05-20 - Avoid filterIsInstance for Collection Performance
**Learning:** Chaining `.filterIsInstance<T>()` and `.map { ... }` on iterables in Kotlin creates unnecessary intermediate `ArrayList` allocations.
**Action:** Use a single `for` loop with a type-check `if (item is T)` or `is T` to construct the final list directly, avoiding intermediate allocations.
## 2024-09-12 - Series Iteration Allocations
**Learning:** In TrikeShed, calling .toList() on collections (like maps/collections) or custom Series before operations like map or sortedBy causes unnecessary O(N) intermediate List allocations.
**Action:** For map on standard collections, avoid toList(); for operations like sortedBy on Series, use .view instead of .toList().
