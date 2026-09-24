## 2024-09-24 - Prevent intermediate Map and List allocations when sorting mapped collections
**Learning:** In Kotlin, chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` to sort mapped collections creates an unnecessary intermediate `LinkedHashMap` and an intermediate `ArrayList`.
**Action:** When sorting mapped collections, map directly to `Pair`s using `.map { ... }.sortedBy { ... }.toMap()` instead of `.associate`.
