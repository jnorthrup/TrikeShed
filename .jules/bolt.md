## 2024-05-24 - Prevent intermediate map and list allocations
**Learning:** `Map.entries.associate { ... }.toList().sortedBy { ... }.toMap()` creates multiple intermediate list and map allocations in Kotlin.
**Action:** When mapping standard collections that implement `Iterable` directly to pairs for building Maps, avoiding chaining `.associate { ... }.toList()`. Map directly to `Pair`s using `.map { ... }` instead, eliminating the intermediate map allocation.
