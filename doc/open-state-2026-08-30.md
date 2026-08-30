# Open state as of 2026-08-30, tag v0.9.7-subvm-guest-modules

Inert record. Nothing here auto-dispatches — `doc/todo.md` is the live intake, and items are
promoted into it deliberately, not by a session deciding on its own.

Written because reconstructing this from a transcript costs more than writing it down.

## Landed and pushed

| Commit | What |
|---|---|
| `053e79ff1` | Guest module classpaths — `utils/subvm`, `VmSpec.module` → `Context.Builder.hostClassLoader`. CoreNLP off `jvmMain`. `build/staging/lib` 890M → 415M. |
| `1dc7aeb51` | Guest module *shape* hoisted to commonMain (`borg.trikeshed.vm.GuestModule`); jvmMain keeps only bytes + classloader. |
| `4e3b1c5f4` | WikiSkill 1A trainer corpus baked for every target (authored by the wiki lane, verified before commit). |
| `2fad82b31` | `.kotlin` joins `EXCLUDED_SEGMENTS` — a build beside the daemon was firing a full reconcile per compile. |
| `7732cf198` | Attribution correction: the corenlp Groovy→GraalJS conversion is jnorthrup's, from `wip` 49c94c868. |

## Open, with the exact next command

**1. M4 durability gate — SEALED (2026-08-30, at `b2b9ab72c`).** The falsification-by-removal test
was run and is the reason this is sealed rather than asserted. With the ledgers quarantined: all 12
taught impulses returned `results:[]`, the thaw re-admitted **0** eternal rules, and the
byte-identical admit returned **`admitted:12`** instead of 0 — the rete had nothing, so it took all
twelve as new. Operator state came back byte-for-byte against the pre-test sha256 record, and the
restored boot read `29 durable / 13 rules` with 12 of 12 present.

One number did not match the prediction and is recorded because it would mislead anyone checking
only the counter: the ledger-absent thaw read `+ 3 (durable ledger)`, not 0. Those three are lines
that boot **wrote** — the curator tees axioms as it mints them — not lines it restored. The 12
absent impulses in the same boot are the proof. The rules count, 0, is the clean signal.

Superseded, kept for the reasoning: ~~**M4 durability gate is BLOCKED, not failed.**~~ Evidence is strong (12 impulses restore, 12 rules
re-admit with identical cids, a restored rule fires post-restart with the board baseline at 0). The
one decisive test was never run: remove the ledgers and prove the knowledge goes away. Driver is
written and has an EXIT trap plus a separate untouched backup.

```
bash .zenith/missions/mission-002-curation/evidence/m4-falsification-driver.sh
```
Expect, if durability is real: thaw reads `+ 0 (durable ledger)`, the 12 impulses return `results:[]`,
and the byte-identical admit returns `admitted:12` — **not 0**. Anything else falsifies it and the
gate becomes a FAIL. Verdict so far: `.zenith/missions/mission-002-curation/evidence/g-m4-verdict.json`.

**2a. `--once` now exits — fixed at `b2b9ab72c`.** `coroutineContext.cancelChildren()` after
`mainImpl` returns, rather than another name in that `finally`: the defect was that the list had to
be exhaustive, so any future `launch` forgetting to register would reintroduce the hang silently.
`OroborosDaemonCycleTraceTest` now fails deterministically in ~10 min instead of hanging, on
`expected: <5> but was: <0>` — and that assertion **could never have passed**: `traceWriter` is
opened at `OroborosDaemon.kt:1881` and flushed/closed at `:1991`, with nothing in between ever
writing a line. The daemon emits no cycle trace at all. Left failing and named.

**2b. The real cost is in the daemon, not the test.** Every `--once` boot pays a 30s
`delay(intervalMs)` it has no reason to pay, plus a full 8,478-path Hermes home reconcile into CAS
(`OroborosDaemon.kt:1831`). The cycle-trace test does five. Not changed — flagged deliberately as
the next thing worth doing, not started, to avoid widening scope.

**2c. T4 `.kotlin` watcher exclude — still UNVERIFIED live.** Source is correct and parity-tested,
but `build/live/classes/…/WorktreeCouchGateway.class` is stamped 04:05, before the 07:14 fix, so the
daemon running since 10:36 carries pre-fix code. Its zero `.kotlin` events prove nothing: there are
currently zero `.salive` files. Verifying needs a restage plus restart, deliberately not done while
a suite is running.

Superseded: ~~**`--once` cannot exit — the full suite is unrunnable.**~~ `OroborosDaemon.main` (line ~240) wraps
`mainImpl` in `runBlocking`, and the daemon launches watchers into that same scope, so `runBlocking`
waits forever on children that never complete. Measured: test worker parked on
`BlockingCoroutine.joinBlocking`, 17s CPU across 32 minutes wall. **There is still no full-suite
result for this repo, and there cannot be until this is fixed.** Targeted suites are green.

**3. Trademark rename — needs its own branch, do not half-rename.** 141 occurrences in 32 files.
Three files carry it in the *name*: `SubVmLegos.kt`, `CoreNlpLegoExecutionTest.kt`,
`CamelLegoExecutionTest.kt` (the last two are new surface added 2026-08-30). The risk is not source:
`SubVm.LEGO_PREFIX = "vm."` is safe, but `ConcentricSurface` emits `"legos"` as a **map key** and
`class="lego"` in HTML, and `LcncContracts` builds contract types from the prefix — so renaming the
map key changes a wire shape that saved canvases and `ConcentricSurfaceTest` both read.

**4. Deferred, recorded, not started.** Dangling belief `subjectCid` (computed, never `put`);
`CuratorImpulseFeeder` marker vocabulary (3 marker rows in 1,771 messages — this is what produces the
438-byte MEMORY render); `CouchStoreFactory.casBacked` building a fresh in-memory `CouchHeadProjection`
per boot, which is why couch is not durable and why every boot rewrites ~5,900 blobs instead of
deduping.

**5. Camel is dispatch, not yet a controller.** `vm.camel` runs for the first time and carries a body
through a route, but stops its context at end of eval. Drag-and-drop needs a lifecycle
(install → start → route → stop → status) over the module shape that now exists. Tika cannot move
guest-side until `JvmTikaIngestAdapter.kt` does — unlike CoreNLP it has a real host consumer.

## Facts worth not re-deriving

- Commits titled `wip` are jnorthrup's signature: same author and committer, timestamps equal to the
  second (unrebased), no Claude trailer. Deletions inside them are deliberate.
- `git branch --merged` proves a tip is an ancestor. It says **nothing** about whether the content
  survived — files get merged and then deleted later. Diff trees and search master by basename.
- The 12 worktrees under `.claude/worktrees` are from 2026-08-19/20 and hold 15,232 directories,
  which is 92% of this checkout and why the git watcher was walking the whole tree. They are **not**
  to be deleted.
- `-Xmx3g` on the test JVM is load-bearing: the guest classloader is in the same JVM, so CoreNLP's
  models are on the same heap even though it left the classpath.
