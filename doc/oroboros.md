# Oroboros — Forge state and CouchDB tree record

## Contract

Oroboros owns TrikeShed runtime state under:

```text
~/.local/forge
```

A different root is valid only when explicitly configured. There is no implicit
`forge_home`, project-local state directory, or second default.

For each managed project, Oroboros maintains one local Git repository and one
persistent CouchDB document. The complete managed repository tree is represented
by attachments on that single document, following the model in:

```text
../RelaxFactory/rxf-rsync/src/main/java/rxf/rsync/FileWatcher.java
```

This is an external CouchDB document contract, not one TrikeShed `Document` per
path and not a transient in-memory projection.

## Canonical state layout

```text
~/.local/forge/
├── agents/<agent>/projects/<project>/   # managed Git working tree
├── couch/                               # local CouchDB runtime/config, if locally hosted
├── run/                                 # pid/socket/status files
└── overrides/                           # explicit, named configuration only
```

The project root for the current checkout is:

```text
~/.local/forge/agents/trikeshed/projects/trikeshed
```

All generated runtime state belongs under `~/.local/forge`. Source checkouts may
remain elsewhere and are explicit ingress roots; they are not state roots.

## One project = one CouchDB record

Default logical identity:

```text
database: forge
record:   project:trikeshed
```

Both values may be explicitly overridden. The record has one `_attachments`
map containing the complete managed tree:

```json
{
  "_id": "project:trikeshed",
  "type": "forge-project-tree",
  "project": "trikeshed",
  "sourceRevision": "<git revision>",
  "_attachments": {
    "README.md": {
      "content_type": "text/markdown",
      "data": "<base64 bytes>"
    },
    ".git/HEAD": {
      "content_type": "application/octet-stream",
      "data": "<base64 bytes>"
    },
    "src/commonMain/kotlin/.../File.kt": {
      "content_type": "text/kotlin",
      "data": "<base64 bytes>"
    }
  }
}
```

CouchDB may replace uploaded `data` with its attachment metadata (`digest`,
`length`, `revpos`, `stub`) when the document is fetched without attachment
bodies. Attachment names always use `/`, independent of host path separators.

### Tree boundary

The record covers every regular file under the managed project root, including
`.git/**`, unless an explicit ignore override excludes a path. Defaults do not
silently omit repository state. Broken symlinks and unsupported special files
must be reported; they must not inflate the recorded file count.

### Update semantics

- Provisioning walks the complete managed tree.
- Missing CouchDB attachments are created.
- Changed files replace their attachment bodies.
- Attachments whose files disappeared are removed.
- A burst of filesystem events becomes one document revision update.
- The document revision (`_rev`) is the optimistic-concurrency boundary.
- On a `409 Conflict`, refetch the latest record, replay the coalesced delta, and
  retry with a bounded policy.
- No per-path CouchDB documents are created.

## Reactor-bound file watcher

The JVM binding is `JvmFileWatchReactorElement`:

- extends `AsyncContextElement`;
- follows `CREATED → OPEN → ACTIVE → DRAINING → CLOSED`;
- recursively registers directories with JDK `WatchService`;
- registers newly-created directories;
- emits `CREATE`, `MODIFY`, and `DELETE` through a finite suspending
  `Channel<FileEvent>`;
- blocks only on `Dispatchers.IO`;
- coalesces an event burst before updating CouchDB;
- closes `WatchService`, joins accepted work, then closes outputs during drain.

The watcher is push-driven. A periodic full-tree polling loop is not acceptable.
A full provision pass is still required at startup and after `OVERFLOW`.

The JVM jar is preferred for the smallest working macOS deployment. A native
binding is justified only when it replaces `WatchService` with a real platform
source such as kqueue/FSEvents and preserves the same reactor/channel contract.

## Relationship to the RelaxFactory implementation

Behavior retained from `FileWatcher.java`:

1. recursively provision the directory tree;
2. store one document with `_attachments` keyed by relative path;
3. coalesce file events before persistence;
4. compare attachment digests before replacing bodies;
5. delete attachments for removed files;
6. normalize separators to `/`;
7. persist bounded batches and continue until the delta is empty.

Behavior corrected:

- bounded coroutine channels replace static global delta maps and timers;
- reactor lifecycle replaces detached executors;
- SHA-256 may be retained as local verification metadata, but CouchDB
  `_attachments` remains the authoritative record shape;
- `OVERFLOW` triggers reconciliation instead of `System.exit(99)`;
- CouchDB `_rev` conflicts are handled explicitly;
- state defaults to `~/.local/forge`.

## Current implementation status — 2026-07-21

Working:

- `ForgeHome.defaultHome` is `~/.local/forge`;
- a managed Git mirror exists at
  `~/.local/forge/agents/trikeshed/projects/trikeshed`;
- `JvmFileWatchReactorElement` is push-driven and lifecycle-bound;
- the real filesystem test observes a create event and verifies closed drain;
- a local SHA-256 CAS and TSV manifest currently exist.

Not yet conformant:

- there is no persistent CouchDB project record;
- `CouchAttachmentGateway` currently creates one transient TrikeShed Couch
  document per path;
- attachment metadata disappears when the process exits;
- the current ingest excludes `.git/**` and therefore does not capture the
  complete managed repository;
- the TSV manifest and per-file CAS blobs are implementation artifacts, not a
  substitute for the one-record `_attachments` contract;
- watcher deltas currently trigger a whole ingest rather than one CouchDB
  attachment-delta update.

Therefore the existing contents of `~/.local/forge` are an intermediate mirror,
not proof that the CouchDB contract is satisfied.

## Required implementation cut

1. Add a CouchDB HTTP adapter configured by explicit URL/database/document ID.
2. Fetch or create `project:trikeshed` with `_attachments = {}`.
3. Provision the managed Git root, including `.git/**` by default.
4. Convert each file to one attachment entry on that record.
5. Persist the complete initial record and retain its `_rev`.
6. Feed coalesced `JvmFileWatchReactorElement` events into attachment
   create/replace/delete mutations.
7. Persist one revised document per coalesced batch with bounded `409` replay.
8. Restart, fetch the record, and prove that every attachment survives.
9. Restore into an empty managed root and prove the Git repository passes
   `git fsck` and checks out the recorded revision.

## Acceptance evidence

The cut is complete only when all of the following are observed against a real
CouchDB instance:

```text
GET /forge/project%3Atrikeshed
```

- returns one document;
- returns `_attachments` containing every regular file in the managed tree;
- includes `.git/HEAD`, refs, index, and object/pack files unless explicitly
  overridden;
- attachment count equals the provisioned regular-file count;
- modifying one source file advances `_rev` once and changes only its attachment;
- deleting one source file advances `_rev` once and removes its attachment;
- restart preserves the record and its attachments;
- restore into an empty root produces a valid Git repository;
- no per-path CouchDB documents exist for the project tree.
