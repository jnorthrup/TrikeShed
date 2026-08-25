## 2024-08-25 - CouchDatabase Document ID Iteration Optimization
**Learning:** `CouchDatabase`'s `allDocs` method previously materialized all documents using `store.all()` just to filter tombstones and extract IDs. This is highly inefficient in TrikeShed since `store.all()` forces deserialization of the entire database.
**Action:** Use `store.ids()` to get a zero-allocation `Join` view of the document IDs. Iterate using `ids.a` (size) and `ids.b(i)` (getter). To check deletion status without loading the document, use `store.head.isDeleted(id)` instead of `isTombstone(doc)`.
