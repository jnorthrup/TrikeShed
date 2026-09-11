## 2024-05-20 - Avoid filterIsInstance for Collection Performance
**Learning:** Chaining `.filterIsInstance<T>()` and `.map { ... }` on iterables in Kotlin creates unnecessary intermediate `ArrayList` allocations.
**Action:** Use a single `for` loop with a type-check `if (item is T)` or `is T` to construct the final list directly, avoiding intermediate allocations.
