## 2024-09-14 - Sequence distinct allocation overhead
**Learning:** Using `.distinct().toList()` on a Kotlin `Sequence` (like Regex `.findAll()`) buffers intermediate elements in an internal map/set that isn't optimally sized and causes noticeable allocation overhead compared to manually collecting into a sizing-aware set.
**Action:** Replace `.distinct().toList()` on sequences with `.mapTo(LinkedHashSet()) { ... }.toList()` when iterating large collections to skip the intermediate sequence processing state and eliminate the extra internal ArrayLists/Maps created by distinct().
