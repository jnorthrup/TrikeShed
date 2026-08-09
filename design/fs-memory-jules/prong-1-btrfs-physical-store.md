# Prong 1: BTRFS-Backed MemoryStore Physical Layer

## CONTEXT

Paper 2607.26637v1 ("Filesystem-Based Memory for LLM Agents") formalizes agent
memory as a rooted path hierarchy of files f = (path, description, content).
The store is the substrate across which the management agent reorganizes
(merge, split, move, rename) as content accumulates. RQ4 finding: "curation
never gets cheaper per episode" and "early memories survive."

TrikeShed already has the BTRFS physical layer built but unwired:

- `src/commonMain/kotlin/borg/trikeshed/btrfs/BtrfsReflinkStore.kt` — extends
  `CasStore`, writes by `ContentId` (sha256), does dedup scan via
  `ReflinkScanner`, can `reflinkCopy` via `cp --reflink=always`.
- `src/commonMain/kotlin/borg/trikeshed/classfile/slab/btrfs/TinyBtrfsContract.kt`
  — ioctl surface: CLONE_RANGE, FILE_EXTENT_SAME, SUBVOL_CREATE as
  Cursor->Cursor projections.
- `src/commonMain/kotlin/borg/trikeshed/job/ContentId.kt` — SHA-256 over
  canonical bytes; `ContentId.of(bytes)` and `ContentId.of(doc)`.
- `src/commonMain/kotlin/borg/trikeshed/job/CasStore.kt` — `open class CasStore`.
- `src/commonMain/kotlin/borg/trikeshed/reflink/ReflinkScanner.kt` and
  `ReferenceCounter.kt` — chunk scan + ref counting.

The paper's reorganization mandate (maintenance is part of the job, Prompt 1
strategy step 4) maps to BTRFS reflinks: reorganized files share extents with
originals until a byte diverges, making the copy storage-free. SUBVOL_CREATE
maps to the paper's top-level folder structure: each memory topic is a
subvolume (independent snapshot/rollback boundary). The paper's "early memories
survive" (RQ4) gets a physical analog via subvolume snapshots.

## TASK

1. Read `BtrfsReflinkStore.kt` and `CasStore.kt` to confirm the current
   `put`/`get`/`reflinkCopy` surface. Note that `put` already does a dedup scan
   but does NOT yet invoke reflink for chunk-level dedup (the comment at line 54
   admits this is deferred).

2. Define a `MemoryFile` value type: `Join<ContentId, Join<String,
  ByteArray>>` (cid j (description j content)), matching the paper's
  f = (p_f, d_f, c_f) triple. The path component is derivable from the
  subvolume + topic prefix; the description is the frontmatter `description:`
  field. Place in `src/commonMain/kotlin/borg/trikeshed/btrfs/MemoryFile.kt`.

3. Extend `BtrfsReflinkStore` with a `putMemory(topic: String, file:
  MemoryFile): ContentId` method that:
   - Resolves the topic to a subvolume path (create-on-first-write via
     `TinyBtrfsContract.SUBVOL_CREATE` projection).
   - Writes the content bytes through the existing `put(bytes)` CAS path.
   - Writes a Couch metadata document (description, revision, agentId) via
     `CouchAttachmentGateway.putAttachment` so the metadata is queryable.
   - Returns the ContentId.

4. Add `snapshotTopic(topic: String, snapshotName: String): Boolean` that calls
   SUBVOL_CREATE with a read-only flag on the topic subvolume. This is the
   "early memories survive" durability primitive.

5. Add `reflinkReorganize(srcCid: ContentId, dstTopic: String, newPath:
  String): Boolean` that reflinks a memory file to a new location within a
   different topic subvolume. This is the reorganization primitive that the
   management agent's maintenance step uses.

6. Wire the Cursor projection from `TinyBtrfsContract` so that SUBVOL_CREATE
   and CLONE_RANGE are observable as blackboard rows. Each memory operation
   emits a `BlackboardSurfaceRow` with lane="memory", facet="btrfs",
   causalKey=<topic>:<cid hex prefix>.

7. Verify: write a `MemoryStoreBtrfsTest` under `src/commonTest/kotlin/` that
   creates a topic subvolume, writes two memory files, snapshots the topic,
   reflinks one file to a new topic, and asserts the refcount on the source
   ContentId is 2.

## BUILD GATE

```
./gradlew jvmMainClasses --console=plain
```

No unit tests in the gate (`jvmTest` is not run). The commonTest in step 7 is
for local verification only; it must not break `jvmMainClasses`.

## TERMINAL SURFACE

Memory operations observable as Cursor projections in the forge blackboard
camera. The RTS camera can zoom to a topic subvolume and show memory file
provenance (ContentId, refcount, revision chain). This satisfies the
trikeshed-forge-product rule: every task terminates on a forge-observable
surface.

## FAN-IN NOTES

This prong owns the physical layer. It does NOT touch ISAM indexing (Prong 2),
ACP tool harness (Prong 3), trajectory distillation (Prong 4), or MCP serving
(Prong 5). Patches commute. Fan-in via PijulChannel.
