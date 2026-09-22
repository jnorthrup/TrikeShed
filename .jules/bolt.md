## 2024-05-24 - Prevent intermediate Map and List allocations before sortedBy
**Learning:** In Kotlin, chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` on standard collections or properties like `Map.entries` allocates unnecessary intermediate Map and List objects.
**Action:** Map directly to Pairs using `.map { ... }.sortedBy { ... }.toMap()` to avoid the intermediate Map and List allocations, reducing memory overhead, especially in frequently executed serialization/deserialization code like VM boundaries.
