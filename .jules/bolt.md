## 2024-05-15 - Prevent intermediate collection allocations during map sorting
**Learning:** Chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` on Iterables creates unnecessary intermediate `LinkedHashMap` and `ArrayList` allocations.
**Action:** Map directly to pairs and sort using `.map { ... }.sortedBy { ... }.toMap()` to prevent intermediate allocations.
