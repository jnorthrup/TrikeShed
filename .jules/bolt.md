## 2024-05-24 - Prevent Map/List allocations in Teleportation
**Learning:** When sorting mapped collections, `associate { ... }.toList().sortedBy { ... }.toMap()` causes two intermediate allocations (a LinkedHashMap and an ArrayList).
**Action:** Use `.map { ... }.sortedBy { ... }.toMap()` directly to map entries to Pairs and sort them, avoiding the intermediate Map and List.
