# Userspace Btrfs images and mounted volumes

`BtrfsFileSystemElement` provides a common-Kotlin filesystem over file-backed
storage. It creates native Btrfs images, reads supported native images, and owns
file mutations, metadata compression, member addition and storage-profile
conversion. `BtrfsMacFuseMount` connects the same element to macFUSE on macOS
arm64 with JDK 25. The storage implementation does not require a Linux Btrfs
kernel module.

The native image writer emits checksummed superblocks and root, chunk, device,
extent, checksum and filesystem trees. It supports nested directories, regular
files, modes and modification times, including trees with internal nodes.
Untouched imported uid/gid, access/change/creation times and root-directory
metadata are preserved through rewriting and export. Mutations update change
time, and content writes also update modification time. Unsupported inode flags and pending
recovery logs are rejected before importing a writable snapshot.
Native `btrfs check --readonly --check-data-csum` and `btrfs restore` provide the
independent compatibility checks. The reader also accepts supported images
created by native `mkfs.btrfs --rootdir`.

## Commands

Use the repository's JDK 25 toolchain. `-PkotlinJsApps=` selects the storage CLI
without packaging the browser applications.

```bash
./gradlew -PkotlinJsApps= btrfs --args='create /tmp/btrfs-state 128 SPAN 1 ZSTD'
./gradlew -PkotlinJsApps= btrfs --args='mkdir /tmp/btrfs-state /documents'
./gradlew -PkotlinJsApps= btrfs --args='put /tmp/btrfs-state /path/to/input /documents/input'
./gradlew -PkotlinJsApps= btrfs --args='ls /tmp/btrfs-state /documents'
./gradlew -PkotlinJsApps= btrfs --args='get /tmp/btrfs-state /documents/input /tmp/recovered'
./gradlew -PkotlinJsApps= btrfs --args='add /tmp/btrfs-state 128'
./gradlew -PkotlinJsApps= btrfs --args='balance /tmp/btrfs-state RAID1 2 256'
./gradlew -PkotlinJsApps= btrfs --args='compression /tmp/btrfs-state LZ4'
./gradlew -PkotlinJsApps= btrfs --args='export /tmp/btrfs-state /tmp/native.btrfs'
btrfs check --readonly --check-data-csum /tmp/native.btrfs
btrfs restore /tmp/native.btrfs /tmp/restored
```

Sizes are per-member MiB. The logical image needs at least 128 MiB. The example
starts at 128 MiB, grows to 256 MiB, then preserves that capacity with two
256 MiB mirrored members. `SINGLE`, `SPAN`, `DUP`, `RAID0`, `RAID1`, `RAID10`,
`RAID5` and `RAID6` use the existing userspace RAID geometry and redundancy
rules. Shrinking is refused. Files on one physical disk do not provide physical
failure isolation. `help` lists the remaining file commands.

The selected RAID profile protects member data. The generation manifest and
compressed metadata sidecar are separate files outside that member redundancy;
they require their own durable storage and backup. Losing either can prevent
opening the generation even when its RAID members remain readable.

`export` creates an exclusive new file. Native tools consume this exported
image. The backing member files have TrikeShed RAID headers and a separate
metadata descriptor; passing a member file directly to `btrfs check` is invalid.

## Compression and native compatibility

`RAW`, `ZSTD` and `LZ4` select the userspace metadata codec. LZ4 supplies the
non-Zstd option for low codec overhead; no relative throughput claim is made
without a workload benchmark. The common codecs need no native compression
library. Incompressible payloads fall back to a tagged raw representation.

Btrfs's native metadata format requires uncompressed tree blocks. The writer
therefore sends actual tree pages to `BtrfsMetadataSink`, which stores them in
a checked compressed sidecar. Raw member storage does not receive those tree
pages. `BtrfsMetadataVolume` reconstructs the canonical bytes for logical reads;
export writes ordinary Btrfs tree blocks. File data and superblocks retain
their native representation. This extends userspace storage, without inventing
an incompatible Btrfs on-disk compression flag or changing the Linux kernel.

Each sidecar is bound to its generation manifest by content identity, sizes,
extent count and codec envelope. Decoding checks integrity, geometry and
allocation bounds. Changing compression relocates and recompresses existing
metadata before publishing the new generation. It does not only set a preference
for future writes. The older directory/CAS `UserspaceBtrfs` also accepts the
codec for its own manifests; those manifests remain a separate storage format.

## Ownership, growth and durability

Both `BtrfsFileSystemElement` and `BtrfsRaidRebalance` are singleton-keyed
coroutine context elements with bounded request channels and owning
`SupervisorJob`s. Add the filesystem element to a caller's context and use
its suspend operations. A mount takes ownership of the backing volume;
`drain()` stops admission, settles in-flight work and closes owned resources.

`fs.migrate(destinationConfig)` preserves the live element while adding members
or changing the storage profile. Destination members must be fresh files.
Requests queue during relocation. The implementation stages and syncs the
replacement, then atomically publishes a checksummed generation manifest.
Reopening follows the published manifest. Ambiguous publication failure stops
further access until reopen; it cannot silently continue on a stale snapshot.
The exclusive writer lease prevents two owners from mutating the same state.

This implementation serializes mutations by writing a complete new filesystem
generation. It retains decoded files in memory and retains old/interrupted
generation files on disk. Consequently it is suitable for bounded file trees,
not yet a general-purpose replacement for an incremental Btrfs filesystem.
There is no automatic generation garbage collector. Keep the manifest, sidecar
and referenced members together; reclaim older files only after establishing
that no recovery root references them. Paths in the manifest are canonical
absolute paths, so moving a state directory requires explicit migration.
Durability depends on the filesystem provider's fsync and atomic-rename
guarantees; failure injection is not a physical power-loss test.

Snapshots are bounded to 256 MiB of aggregate decoded file data and 64 MiB of
metadata reads. Paths are limited to 4096 UTF-8 bytes, names to 255 bytes and
directory nesting to 256 levels. These limits are checked before large decoded
allocations. Reads do not update access times.

## macOS mount

Install macFUSE and approve its system extension through the normal macOS
installation flow. Use an existing empty mountpoint:

```bash
./gradlew -PkotlinJsApps= btrfs --args='create /tmp/btrfs-live 128 SPAN 1 ZSTD'
mkdir /tmp/btrfs-mount
./gradlew -PkotlinJsApps= btrfs --args='mount /tmp/btrfs-live /tmp/btrfs-mount'
```

An optional third operand supplies the macFUSE 2 ABI library path. The default
locations are `/usr/local/lib/libfuse.2.dylib` and
`/Library/Filesystems/macfuse.fs/Contents/Frameworks/libfuse.2.dylib`.
The bridge uses JDK FFM, Darwin arm64 layouts and the macFUSE 2 operation table.
It supports directory listing, file reads/writes, create, mkdir, rename,
unlink/rmdir, truncate, permissions, access/modification times and synchronization.
Free-space queries report the managed writer's reserved regions and rounded
file extents; available data space also observes the 256 MiB snapshot limit.
Inode availability is reported as unknown. These are managed-image capacity
figures, not a general native Btrfs free-space-tree scan.
The mount presents the serving process's uid/gid; stored native ownership is
preserved on export. Unsupported operations return an explicit error. Unmount through macOS to
finish the serving session and drain the filesystem.

Close open handles before unmounting or stopping the process. The macFUSE 2
unmount function cannot return a busy-volume error to this adapter; shutdown
waits for native cleanup and reports a timeout if it does not settle.

The mount command prints a `control 127.0.0.1:PORT TOKEN info` prefix.
Use its address and token in another terminal to change the existing mount:

```bash
./gradlew -PkotlinJsApps= btrfs --args='control 127.0.0.1:PORT TOKEN add 128'
./gradlew -PkotlinJsApps= btrfs --args='control 127.0.0.1:PORT TOKEN balance RAID1 2 256'
./gradlew -PkotlinJsApps= btrfs --args='control 127.0.0.1:PORT TOKEN compression ZSTD'
```

Replace `PORT` and `TOKEN` with the values from that session. The authenticated
loopback control uses the already mounted element, so it does not contend for
the writer lease. It admits only `info`, `add`, `balance` and `compression`.
The mount remains attached while filesystem requests queue behind relocation.
The ordinary commands with a `STATE` operand require the state to be unmounted.
Keep the session token private; it authorizes these local storage changes.

The bridge's native callback ABI is testable on Linux. An actual Finder/POSIX
mount has **not been runtime-verified on macOS in this Linux cloud environment**.
Intel macOS and FUSE 3 are not supported by this adapter.

## Supported boundary

| Surface | Current support |
| --- | --- |
| Native image creation | Single-device, 128 MiB–256 GiB images, 4096-byte sectors, CRC32C, regular files and directories |
| Native image reading | Single-device SINGLE/DUP chunks, supported flags, uncompressed file extents, bounded decoded snapshot |
| Filesystem mutations | Create/read/write/list/rename/remove/truncate, modes and explicit atime/mtime, atomic generation publication |
| Mounted storage changes | Stable element with queued I/O during member addition and profile conversion |
| Metadata compression | Common-Kotlin Zstd/LZ4/raw sidecars with canonical native image reconstruction |
| Native tools | Offline inspection/check/restore of the exported image; independently checked with btrfs-progs 6.14 |
| Linux online Btrfs ioctls | Not provided |
| Native subvolumes, snapshots, send/receive, reflink, dedupe, quotas | Not implemented by this filesystem element |
| Symlinks, special files, xattrs, ownership changes | Not implemented by this filesystem element |

Storage-profile conversion is userspace backing relocation. It is not native
Btrfs block-group balance. The project does not yet implement the complete
`btrfs-progs` command surface. Existing directory-backed subvolume/snapshot and
private send-stream APIs do not establish native filesystem compatibility.

## Validation

Install `btrfs-progs` to run the native oracle tests. Set
`TRIKESHED_BTRFS_PROGS` to the executable if it is not on `PATH`; keep
`mkfs.btrfs` on `PATH` for the reverse-compatibility fixture.

```bash
./gradlew -PkotlinJsApps= btrfsStorageCheck
```

The checks cover native-tool interoperability, large trees, corrupt checksums,
codec interoperability, metadata reconstruction, generation publication and
reopen, file operations across migration, cancellation/drain, overwrite refusal
and FFM callbacks. Missing native tools are reported as unavailable test
prerequisites, not as evidence of native compatibility.

The Linux validation run executed all 189 Btrfs tests successfully, with no
skips. The broader storage check ran 292 tests and retained one pre-existing
failure: `TrikeShedGraalVfsTest.graalPythonExplicitUtimeRetainsNanoseconds`.
Pinned GraalPy 25.3.4.1 discards nanoseconds in its `SetUTimeNode.toFileTime`
before calling the VFS. Direct VFS timestamp tests pass; the failing guest
contract remains enabled. The native check/restore smoke test also passed after
Zstd-to-LZ4 conversion, volume addition and RAID1 conversion.
