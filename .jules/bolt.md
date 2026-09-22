## 2024-05-24 - Avoid associate.toList.toMap
**Learning:** In Kotlin, chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` on map entries creates intermediate Map and List allocations. We can optimize it by mapping directly to Pairs using `.map { ... }.sortedBy { ... }.toMap()`.
**Action:** Replace chaining associate and toList with direct map.
