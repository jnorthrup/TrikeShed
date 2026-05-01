# libs/tiny-btrfs — TODO

## Intent
B+Tree implementation + userspace btrfs buffer layer. Dense-array-backed nodes with Series views. Disk adapters for JVM (FileDiskAdapter, ChunkedFileDiskAdapter). KMP full.

## Status: ALPHA (B+Tree solid, userspace buffer is stub)

## Pure boundary audit

### Keys — none needed currently
- This is a data structure + I/O module. No async context.

### Elements — potential future
- `UserspaceBtrfsBuffer` / `UserspaceMemoryBuffer` — could become AsyncContextElements if they need lifecycle (open buffer → read/write → close)
  - Currently no async lifecycle, just data methods

### Statics that should stay static
- `BPlusTree` — pure data structure ✓
- `DiskAdapter` interface + JVM implementations — I/O abstraction ✓
- `UserspaceBtrfsBuffer` — buffer with read/write offsets ✓
- `UserspaceMemoryBuffer` — in-memory variant ✓
- `BtrfsTreeRebuilder` — tree reconstruction from disk nodes ✓

## Integration partners
- **couch**: couch imports tiny-btrfs for BtrfsSandboxElement and WAL persistence
- **root project**: root `userspace/btrfs/BtrfsNodeSerialization` provides serialization that tiny-btrfs uses

## Path to stable
1. B+Tree round-trip tests (insert → search → delete)
2. DiskAdapter round-trip tests
3. Consider if UserspaceBtrfsBuffer needs AsyncContextElement lifecycle
4. Integration test: B+Tree → DiskAdapter → read back → verify
