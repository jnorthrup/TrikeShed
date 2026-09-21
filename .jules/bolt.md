## 2024-05-24 - Zero-copy ByteBuf push
**Learning:** Found an O(N) intermediate List allocation when `buffer += bytes.toList()` was used in `ByteBuf.push(vararg bytes: Byte)` because `toList()` creates an intermediate `List<Byte>` object before it gets copied into the `MutableList<Byte>`.
**Action:** When pushing vararg elements into a mutable collection, iterate the vararg or use standard Array copy operations to avoid `toList()` allocation overhead.
