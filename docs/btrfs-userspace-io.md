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

RAID and spanning claims need real independent devices or images representing
independent failure domains. Multiple files on one physical device can exercise
layout math, but they do not prove RAID failure isolation.
