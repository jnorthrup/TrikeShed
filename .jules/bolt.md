## 2024-05-23 - Map toList Allocation

**Learning:** In Kotlin, chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` creates multiple intermediate list and map allocations.
**Action:** Instead, map directly to `Pair`s and chain `.map { ... }.sortedBy { ... }.toMap()` to avoid the intermediate `.associate` and `.toList()` steps.
