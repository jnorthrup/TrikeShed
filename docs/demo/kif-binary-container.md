# KIF Binary Container

The KIF binary container is the Roaring-shaped bitset that the SUMO classifier is built on. It lives in `src/commonMain/kotlin/borg/trikeshed/collections/bits/` (`RoaringSeries.kt`, `ClosureIndex.kt`) and landed in commit `39599783e` ("SUMO classifier in bitset shape", 2026-09-04).

## What it is

SUO-KIF forms (the pinned Merge + Mid-level corpus) are parsed by `SumoClassifier.of`, which numbers every class and stores the subclass, instance, domain, range and disjoint relations as sets of class ids. Those sets are `RoaringSeries` values. Every question the classifier answers, such as is-A, domain check, disjointness or a literal's place in the Number subtree, is a single membership test against one of those sets.

## The container shape

A `RoaringSeries` follows Roaring's methodology with no library behind it. The key space is cut into chunks of 65,536 by the high 16 bits. Each chunk holds its low 16 bits in the smallest of three `BitContainer` forms:

- **ArrayContainer**: a sorted `IntArray` of low values, 2 bytes per member. The sparse shape, used for ancestor sets.
- **RunContainer**: ascending (start, length) runs, 2 + 4 bytes per run. The shape of a DFS-numbered subtree, used for descendant sets.
- **BitmapContainer**: 1,024 words of 64 bits, a fixed 8 KiB. The dense shape.

The chooser is Roaring's own rule: the form with the smallest serialized size wins, ties go to the array. The `byteSize` accounting exists only to drive that choice. Nothing is serialized to disk; the container is an in-memory shape, and the whole set is immutable with `and`, `or`, `andNot` and `intersects` building new sets.

## The MetaSeries idiom

Each container is itself a `Series<Int>` of its members, and the set is a `Series2<high, container>` over its chunks. That keeps it commonMain-pure and consistent with the rest of TrikeShed's Series types.

## Why the numbering matters

`ClosureIndex` assigns each class a DFS-preorder id from the roots. In a tree, a class's descendants become the one run `[id, id + subtreeSize)`, and a class with several parents adds a few more runs. Ancestor sets stay short arrays. Both closures are computed once, bottom-up, with cycles cut where met. `isA` is then one `contains` call. The same builder also serves `IsALattice` and `KifKnowledgeBase.subclassClosure`, replacing an older O(E²) fixpoint loop.

## Measured over the pinned corpus

Pinned in `SumoClassifierCorpusTest` (counts) and the landing commit (bytes, timing):

| Metric | Value |
|---|---|
| Classes | 2,504 |
| Subclass edges | 2,953 |
| Closure size | 43,578 bytes |
| Array containers | 2,648 |
| Run containers | 607 |
| Bitmap containers | 0 |
| 1M `isA` calls | 308 ms |

The test asserts zero bitmaps and more than 200 runs, which is the proof that the DFS numbering did its job.

## Deliberately excluded

By the owner's ruling on 2026-09-04: ordering predicates, arithmetic functions and `MeasureFn` unit algebra. Those are not hierarchical booleans, so they live in Rete interests and NAL rather than in the bitset.
