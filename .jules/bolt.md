## 2025-01-01 - Avoid intermediate Map/List allocations in sorted Map processing
**Learning:** Chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` on a map entry set creates unnecessary intermediate `LinkedHashMap` and `ArrayList` allocations.
**Action:** Map entries directly to `Pair`s using `.map { ... }` before sorting, e.g. `.map { ... }.sortedBy { ... }.toMap()` to prevent intermediate collection allocations.
