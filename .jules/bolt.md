## 2025-02-12 - Prevent intermediate List allocations with filterIsInstance<T>()
**Learning:** Calling Iterable.filterIsInstance<T>() in Kotlin creates an unnecessary intermediate ArrayList allocation.
**Action:** Replace .filterIsInstance<T>().map { ... } with .mapNotNull { (it as? T)?.property } and replace .filterIsInstance<T>().firstOrNull { ... } with .firstOrNull { it is T && ... } as? T to avoid the intermediate List allocation.
