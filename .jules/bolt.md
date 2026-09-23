## 2026-09-23 - Prevent intermediate Map and List allocations when sorting mapped collections
**Learning:** In Kotlin, chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` creates unnecessary intermediate `ArrayList` and `LinkedHashMap` allocations. Map directly to `Pair`s using `.map { ... }.sortedBy { ... }.toMap()`.
**Action:** Use `.map` instead of `.associate.toList` when the goal is to sort and rebuild a Map.
