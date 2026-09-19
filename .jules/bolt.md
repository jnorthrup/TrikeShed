## 2026-09-19 - Prevent intermediate Map and List allocations when sorting mapped collections
**Learning:** Chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` creates unnecessary intermediate `LinkedHashMap` and `ArrayList` allocations.
**Action:** Use `.map { ... }.sortedBy { ... }.toMap()` directly.
