## 2024-05-18 - Avoid associate().toList() for sorted maps
**Learning:** In Kotlin, chaining `.associate {}.toList().sortedBy {}` creates a `LinkedHashMap`, then copies it to an `ArrayList`, then copies it again to sort it. This causes 3x allocations compared to `.map {}.sortedBy {}`.
**Action:** When sorting mapped collections, always map to `Pair`s directly via `.map {}` before sorting, avoiding intermediate `Map` construction.
