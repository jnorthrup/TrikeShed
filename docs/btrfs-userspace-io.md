# Btrfs Userspace IO

This task owns Btrfs image and volume consumers of the common userspace uring
contract. The shared uring facade owns backend selection and native/emulated
execution. Native Linux io_uring availability changes the selected backend, not
the application IO model.

The connected Btrfs path is:

`UringChannels.open(scope, ...)` -> `BtrfsUringFileVolume.open(channel, ...)`
-> `OPENAT` -> `FTRUNCATE` -> `READ` / `WRITE` / `FSYNC` / `CLOSE` ->
selected platform backend -> completion receipt.

`BtrfsUringFileVolume` is a nonvolatile `borg.trikeshed.userspace.nio.Volume`
over an image file descriptor opened through the caller's selected uring
channel. It loops on partial reads and writes, preserves the caller buffer
contract, records completion receipts, and never routes document curation
directly to Java files. The public constructor remains a compatibility path for
older pre-sized image call sites; do not use that constructor as evidence of
native Linux backend selection.

For disposable seed images, call `BtrfsUringFileVolume.open(..., resize = true)`
or `NioBtrfsGraalBlobStore.writeImageViaUring(channel, ...)`. For an existing
filesystem image, pass `resize = false`; `create = false` alone only controls
OPENAT creation flags. The channel-injected factory does not own the channel.
Drain order for consumers is: `DocumentInputElement.drain()`, then
`BtrfsUringFileVolume.drain()`, then the caller's channel drain.
Compatibility instances that create their own channel require the repaired core
`UringChannel.closeNow()` API for backend cleanup. Older saved core revisions
without that method are an integration blocker, not a reason to install a no-op
cleanup shim.

The current seed-image writer only writes and reads back Btrfs superblock slots.
That is not a mountable filesystem claim. Root, chunk, device, extent, checksum,
subvolume, balance, defrag, dedupe, and send/receive disk-format work still
requires canonical Btrfs on-disk structures plus an offline `btrfs-progs` oracle.

The JVM oracle is `BtrfsProgsOracle.checkReadOnly`, which runs
`btrfs check --readonly <image>` when a `btrfs` executable is explicitly
configured or found. Set `TRIKESHED_BTRFS_PROGS` to a pinned upstream
btrfs-progs build when source-pinned evidence is required. A PATH executable is
reported as PATH evidence, not source-pinned evidence. Missing `btrfs-progs` is
an oracle blocker, not proof that an image is valid.

## Storage integration

`BtrfsRaidVolume` composes SINGLE, DUP, SPAN, RAID0, RAID1, RAID10, RAID5,
and RAID6 through bounded request and member channels under an owning supervisor.
`BtrfsRaidImage` owns the member files and scoped uring channels; its public
factories are `NioBtrfsGraalBlobStore.openRaidImage` and `spanMount`.
Existing images open without resizing. New arrays require explicit exclusive
creation; replacement rebuilds an unavailable member before admitting it.

Image members reserve checksummed metadata and redo slots. Intent is synced
before data writes, and data is synced before acknowledgment. Reopen validates
array identity, member geometry, and transaction records before serving IO.
This is a TrikeShed userspace block-volume format, not canonical Btrfs metadata.
Same-host image failure injection can verify software recovery but cannot prove
physical hardware failure isolation. One owner writes an array at a time.

`UserspaceBtrfs` represents file trees with canonical `FileTreeManifest` objects
in CAS. Local subvolume records reference a tree CID; snapshots retain immutable
roots, and mutations publish a new root before changing the live index. Existing
private extent paths and legacy transfer streams remain readable. Independent
mounts observe changes on reopen, not by sharing a coherent mutable cache.

`BtrfsWorldStore` supplies the shared CAS to Graal guest filesystems. The daemon
binds its existing Couch CAS, so guest content and world replication use the same
immutable objects. `VmWorldTeleport` and Couch replication acquire referenced
file objects before publishing their tree. ISAM remains direct fixed-width
derived storage; it is not part of the CAS identity graph.

## Verification

Run from the repository root:

```sh
bin/verify-btrfs-storage
```

This builds current JVM source with Kotlin 2.4.20 and runs `btrfsStorageCheck`.
Node and `ps` enforce a 180-second aggregate deadline including termination of
owned Gradle/test descendants, even when they start separate process groups.
The deadline is a failure, not a reason to remove the last running test.
Results are written to `build/reports/tests/btrfsStorageCheck/index.html` and
`build/test-results/btrfsStorageCheck/`. The held checkpoint's support-JAR
overlay and historical check counts are not substitutes for this current build.

The process check writes through an actual GraalPy guest into a file-backed
shared CAS, snapshots, updates, then exits the writer JVM without shutdown
hooks. A new JVM checks the acknowledged content and the immutable snapshot.
These are process-crash checks, not power-loss or physical-media certification.

The release surface is the userspace storage API on the verified target. Native
Btrfs mountability, multi-process concurrent writers, a new primary log engine,
and Reiser4-derived indexes are not implied. Native Btrfs compression policy is
separate from the userspace extent format; enabling a mount option does not add
a codec to the Kotlin implementation.
