## 2025-02-28 - Avoid intermediate map and list allocations
**Learning:** In Kotlin, chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` on iterables creates unnecessary intermediate Map and List allocations.
**Action:** Map directly to Pairs using `.map { ... }.sortedBy { ... }.toMap()` to prevent intermediate allocations.
