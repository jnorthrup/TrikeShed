## 2024-05-18 - Prevent intermediate List allocations from ArrayDeque
**Learning:** In hot UI event loops, chaining `ArrayDeque.toList().toSeries()` creates an unnecessary O(N) memory allocation that can trigger GC pauses.
**Action:** Always provide direct `.toSeries()` extensions for frequently converted collections like `ArrayDeque` to build `Series` allocations directly from their internal arrays.
