# Archive Demo: Browser and Node

The harness now imports ZIP entries into the existing TreeDoc/CAS pipeline.
Browser JavaScript owns ZIP decoding and presentation; Kotlin owns archive
validation, storage and verified restore. No Node server or second execution
engine is introduced. `commonMain/resources/web` remains the daemon's asset
packaging location, not a claim that these files are Kotlin/JS output.

## Run

Open Archives in `/harness`, then Import ZIP or Load demo. Select an entry to
inspect its actual bytes with the shared Graal file viewer, including the
compression-based K estimate. Select files and Download to restore one file
or a ZIP containing the selection. The K estimate is not exact Kolmogorov
complexity. Model data in the demo is explicitly an offline fixture.

Landscape mounts directories recursively in the existing concentric renderer.
These are inspection-only containment projections, not executable scopes or
dependency cables. Their inspect buttons reopen the archive by immutable CID.
Publish uses the existing panel persistence workflow to retain the landscape;
import alone already persists the archive bytes and manifest. The archive CID
can reopen the manifest independently of the panel or browser session.

Node uses the very same decoder and demo fixture, without a browser or npm install:

```sh
node bin/archive-demo.cjs create /tmp/archive-demo.zip
node bin/archive-demo.cjs inspect /tmp/archive-demo.zip
node --test src/jvmTest/js/archive-core.test.cjs
./gradlew jvmMainClasses --console=plain
./gradlew jvmTest --tests 'borg.trikeshed.kanban.module.ArchiveServiceTest' --tests 'borg.trikeshed.kanban.ForgeKanbanIngestArchiveTest' --console=plain
```

`create` refuses to overwrite an existing file. Neither target extracts archive
paths onto the daemon filesystem or executes file contents.

## Contracts and Limits

- POST `/api/archives/import`: JSON entries with `path`, `mediaType`, and `base64`.
- GET `/api/archives/manifest?cid=...`: immutable entry identities and ordinals.
- GET `/api/archives/content?cid=...&entry=...`: restored binary bytes, served as
  `application/octet-stream`. Chunks and the complete document are CID-verified.
- ZIP compressed input: 4 MiB; 128 entries; 256 KiB per entry; 512 KiB expanded
  total; paths at most 512 characters and 12 components. Request text at most
  1,048,576 characters. Limits are deliberately small for this demo.
- Worker decoding: hard 10-second termination; Node decoding: cooperative
  10-second deadline between parser callbacks/chunks. Browser operations have
  a 30-second timeout and explicit cancellation. The service admits one archive
  operation at a time; concurrent attempts return 429 instead of queueing.
- Absolute paths, dot components, backslashes, drive/colon paths, controls,
  duplicate paths and file/directory conflicts are rejected. Empty directories
  are retained. Server validation runs before any import CAS writes.

The bounded application checks do not impose a storage-layer streaming read cap
on an arbitrary preexisting CAS blob: the current CasStore API reads a complete
blob before the manifest/chunk limit check. This is a remaining shared-storage
hardening boundary, not a claimed protection of this demo.

## Format and Recovery

TreeDoc now directly encodes its structured `docs`/`frames` map using the existing
canonical CBOR encoder. Previously, filenames were interpolated into JSON and
nested Confix cursor views could be encoded as strings. New manifests preserve
the actual arrays, escaped filenames and stable identity across service instances.
Old manifests that lost their document/frame structure cannot be reconstructed
from those strings; reimport from original input is required. Existing blobs are
not rewritten, and the global Confix content-ID algorithm is unchanged.

Cancelling during import stops the browser operation but does not promise to
undo an already accepted CAS write. Reimport of identical entries in the same
order is idempotent. A failed panel publication does not delete the archive;
retain the CID and retry publication independently.

## ZIP Library

Pinned, vendored [fflate 0.8.2](https://github.com/101arrowz/fflate/tree/v0.8.2)
provides ZIP parsing, streaming inflate and ZIP creation in both targets. Its
MIT license is retained beside the asset. Runtime use needs no CDN.

UMD SHA-256: `c3b34f2e9f5e74d4d7d64e01cac7a0c01954c6c406414d42185c7b53d6875ddf`.

The adapter checks declared sizes before inflation and measures actual streamed
output. ZIP CRC validation, encryption, ownership/permission restoration and
symlink interpretation are not implemented. Entries are inert byte documents;
no link is followed. CID verification proves restored bytes match imported
bytes, not the authenticity of the ZIP's author or its CRC fields.

## Verification

`src/jvmTest/js/archive-browser.check.cjs` drives a real, isolated daemon through
Playwright. Set `ARCHIVE_BASE_URL` to that daemon's URL (default port 8896) and
make Playwright available to Node. It creates and publishes only demo archives
in that daemon's store, so do not target a production instance.

The check covers desktop/mobile inspection, ZIP worker import, single/selected
downloads, multi-frame document CID links, fresh and published concentric
layouts, sibling non-overlap, painted canvas pixels, wheel zoom, reload,
unsafe-input rejection and worker cancellation. Screenshots are retained in
the temporary directory printed by the check.

Validated in this change: the JVM build gate, 76 Node tests (17 archive-specific),
six targeted JVM tests, and the desktop/mobile browser check. After restarting
the isolated daemon, the stored manifest reopened and the 256-byte binary demo
entry matched byte-for-byte.

The isolated daemon released its listener on SIGTERM but remained alive with a
ClosedReceiveChannelException in listener shutdown. Only that preview process
was force-stopped; graceful daemon exit is not claimed by this archive change.
IntelliJ opened ArchiveService.kt with the search completed, but continued to
report file-event processing and context loading; IDE-wide diagnostic clearance
is not claimed either.
