## 2024-11-20 - Prevent intermediate Map and List allocations when mapping and sorting
**Learning:** In Kotlin, chaining operations like `.associate { ... }.toList().sortedBy { ... }.toMap()` on standard iterables like `Map.entries` creates unnecessary intermediate `Map` and `List` allocations.
**Action:** To prevent these intermediate allocations, map directly to `Pair`s using `.map { ... }.sortedBy { ... }.toMap()`. This avoids creating the initial associative map and its subsequent list conversion.
