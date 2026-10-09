
## 2025-05-24 - Zero-Allocation Series Rendering Optimization
**Learning:** In tight rendering loops (like Spacegraph's `ExtrudedSceneProjection`), chained Kotlin standard library collection operations like `listOf(...).plus(....map { ... }).toSeries()` construct multiple hidden `ArrayList` objects every frame, creating significant memory pressure and GC churn.
**Action:** When mapping and concatenating collections into a `Series` within a hot path (e.g. constructing `DrawItem.Path` segments or flattening sorted items and labels), use the size `j` constructor directly (e.g., `(size1 + size2) j { i: Int -> if (i < size1) list1[i] else list2[i - size1] }`). This yields a zero-allocation `MappedSeries` and entirely eliminates intermediate heap objects.
