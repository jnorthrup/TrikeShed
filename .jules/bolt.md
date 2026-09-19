## 2024-05-18 - Avoid unnecessary Map/List allocations when sorting maps
**Learning:** Chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` creates unnecessary intermediate `LinkedHashMap`, `ArrayList`, and `ArrayList` (from toList) allocations just to sort a map.
**Action:** Instead, map directly to pairs and then sort: `.map { it.key.toString() to ... }.sortedBy { it.first }.toMap()`. This avoids the intermediate map and the subsequent list conversion, operating directly on a single list of pairs before finally converting to a map.
