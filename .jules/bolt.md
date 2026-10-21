## $(date +%Y-%m-%d) - Zero-allocation KanbanGraph State Transitions
**Learning:** In heavily orchestrated state machines like KanbanGraph, intermediate collection manipulations (like `.plus(...).toSeries()`) cause O(N) intermediate allocations per transition. We can utilize the `j` constructor (e.g. `(size) j { ... }`) to yield a mapped lazy view and achieve zero-allocation transitions.
**Action:** When mapping or appending to `Series` instances during high-throughput operations, prefer using the `j` Series constructor directly. Do NOT map using list conversion or iterate with generic list builders. Ensure proper parens around size math `(size + 1) j { ... }` to avoid ambiguous tuple resolution.

## 2026-03-31 - Zero-Allocation Series Construction in Loom Registry
**Learning:** In Registry defaults resolution, using `repositories.entries.flatMap { ... map { ... } }.toSeries()` creates multiple intermediate list allocations for each repository entry. Replacing flatMap with `buildList` and `list.size j { i: Int -> list[i] }` avoids intermediate list allocations and provides a lazy Series view.
**Action:** Always prefer direct collection accumulation and `list.size j { i: Int -> list[i] }` over chaining `flatMap`, `map`, and `.toSeries()` on nested collections.

## 2026-03-31 - Zero-allocation Series Header Lookup in HTX
**Learning:** Calling `headers.toList().firstOrNull { ... }` on `Series<Join<String, String>>` allocates an intermediate `List` and an `Iterator` for every HTTP header lookup.
**Action:** Always use zero-allocation index iteration `for (i in 0 until size)` over `Series` for lookups instead of converting to Kotlin `List`.
