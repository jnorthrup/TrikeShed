## 2024-09-24 - Prevent Map allocation overhead
**Learning:** In Kotlin, to prevent intermediate Map and List allocations when sorting mapped collections, avoid chaining `.associate { ... }.toList().sortedBy { ... }.toMap()`.
**Action:** Instead, map directly to `Pair`s using `.map { ... }.sortedBy { ... }.toMap()`.
