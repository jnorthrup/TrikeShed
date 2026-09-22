## 2024-09-22 - Avoid chaining .associate { ... }.toList() for collections
**Learning:** Chaining `.associate { ... }.toList()` allocates an intermediate Map and ArrayList.
**Action:** Map directly to `Pair`s using `.map { ... }` when you eventually need a list or to sort it.
