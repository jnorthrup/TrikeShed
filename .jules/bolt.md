## 2026-09-19 - Prevent Intermediate Map and List Allocations
**Learning:** In Kotlin, chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` creates unnecessary intermediate `Map` and `ArrayList` allocations. Mapping directly to `Pair`s using `.map { ... }.sortedBy { ... }.toMap()` achieves the same result with zero intermediate map overhead.
**Action:** Avoid `.associate { ... }.toList()` when the goal is just to sort by key or value before creating a final map. Use `.map { ... }` to produce a list of Pairs instead.
