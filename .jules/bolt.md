## 2024-11-20 - Prevent intermediate allocations in VM boundary crossings
**Learning:** In `Teleported.ofHost()`, mapping and sorting generic objects into `Teleported.Obj` by chaining `.associate { ... }.toList().sortedBy { ... }.toMap()` created unnecessary intermediate Map and List allocations on the critical path for VM context serialization.
**Action:** Map directly to pairs using `.map { ... }.sortedBy { ... }.toMap()` to skip the intermediate associative map and list conversion.
