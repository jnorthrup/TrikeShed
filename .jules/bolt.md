## 2024-05-24 - Reduced-Allocation Map Sorting in Kotlin
**Learning:** In Kotlin, chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` on standard collections or Maps creates unnecessary intermediate Map and ArrayList allocations.
**Action:** Map directly to pairs using `.map { ... }.sortedBy { ... }.toMap()` to prevent intermediate Map and List allocations.
