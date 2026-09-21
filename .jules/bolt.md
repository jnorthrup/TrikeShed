
## 2024-03-24 - Avoid intermediate List allocations on varargs
**Learning:** Calling `.toList()` on primitive arrays (like `ByteArray` or `vararg bytes: Byte`) creates an unnecessary boxed `List` (e.g., `List<Byte>`) intermediate allocation.
**Action:** Iterate directly using standard `for` loops or primitive array extensions instead of converting the primitive array to list.
