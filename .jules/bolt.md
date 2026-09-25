## 2024-05-24 - Prevent intermediate array allocations on collections
**Learning:** Found multiple places using `collection.toTypedArray().toSeries()` or `Enum.entries.toTypedArray().toSeries()`. This allocates a temporary array unnecessarily, since `Series` can wrap a `List` directly using `.toSeries()`.
**Action:** Use `.toSeries()` directly on `List` or `Collection` types without passing through `toTypedArray()` to avoid O(N) intermediate array allocation.
