## 2024-05-24 - Zero-Allocation Mapped Projection for Kanban Cards
**Learning:** When creating a modified collection in Kotlin (like updating a specific card in a list of Kanban cards), `List.map { ... }.toSeries()` introduces a hidden `ArrayList` allocation because `.map` creates an intermediate list before `.toSeries()` wraps it.
**Action:** Use the `size j { index -> ... }` projection instead. It creates a zero-allocation lazy mapped view of the underlying collection directly, avoiding intermediate list allocations entirely.
