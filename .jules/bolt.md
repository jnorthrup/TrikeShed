## 2025-02-12 - Prevent intermediate ArrayList allocations when projecting List/Array to Series
**Learning:** In Kotlin, when converting `List<T>`, arrays, or `EnumEntries<T>` to a `Series<T>`, calling `.toTypedArray().toSeries()` creates a redundant intermediate array allocation.
**Action:** Always call `.toSeries()` directly on types that support it to eliminate O(N) allocation overhead.
