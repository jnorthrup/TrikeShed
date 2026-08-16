# Jules drain, top-up, and settlement — operator contract

This is the durable in-repo home of the flywheel operating contract (the
AGENTS.md pointer target). Everything here was exercised live against the
production board and origin; none of it is aspirational.

## Drain

Drain = every terminal jules session lands as a commit on master, **or** is
explicitly settled through one of the settlement lanes below. Board
"Completed" is NOT "landed" — verify each session via `DrainApplied` in the
WAL (`~/.local/forge/jules-board.wal`) plus the `flywheel/jules-<sid>-*` tag
on origin.

Procedure:

1. Dedupe universe: session ids ∪ merged PRs ∪ `jules-*` remote branches,
   matched by session id or by task name. **No `--limit` caps** — surface
   everything, pull every waiting diff.
2. Answer pending jules questions with the documented exact language
   ("Please proceed with the implementation") or by landing the fix.
3. Pull every Completed-but-unharvested session: `jules remote pull --session
   <sid>` (empty pull = the session produced nothing).
4. Filter patches to repo production paths before applying. Scratch/slop
   inside jules patches must NEVER be committed. Test-only patches are
   excluded by policy: the gate is `jvmMainClasses`, tests are not run.
5. Merge oldest-first, resolve redeclarations in favor of the richer in-tree
   declaration, commit, push.
6. Superseded patches are dropped, not reconciled — record why in the commit
   message or the reject reason.

## Top-up

- Manual: `jules new --repo jnorthrup/TrikeShed "<task>" < /dev/null`
  (the stdin redirect is mandatory).
- Automatic: the daemon self-dispatches when its dispatch gate
  (FlywheelDriver) is satisfiable. If no new sessions appear, check for a
  leaked gradle jvmTest worker running a second OroborosDaemon (100 ms poll,
  ~95% CPU) before suspecting quota; kill the leak AND its GradleWorkerMain
  parent.
- After an api 400 FAILED_PRECONDITION wall, first-try dispatch works again
  once the leak is gone.

## Settlement lanes

All lanes: terminal session + reviewed cause in the WAL + anchor to verified
origin/master; each mints a git tag on origin and appends `WorkDrained`
(MergeReceipt) + `DrainApplied` to the WAL.

| Lane | stdin | Prerequisite | Use when |
|------|-------|--------------|----------|
| `settle` | exact cumulative patch bytes | reviewed snapshot | patch landed in the anchor commit |
| `settle-report` | exact final agent report bytes | `JulesPatchReviewCli report` | report-only no-op; the report's question is answered by landing |
| `settle-reject` | none | `JulesPatchReviewCli reject` | every observed patch is superseded/inferior to what landed; CAS bytes retained, never applied |

CLI shape (classpath `build/classes/kotlin/jvm/main:build/staging/lib/*`):

    java -cp "$CP" borg.trikeshed.jules.JulesPatchReviewCli report <sid> <report-cid> <causal-ordinal> "<disposition>" <reviewer> "<receipt-ref>"
    java -cp "$CP" borg.trikeshed.jules.JulesPatchReviewCli reject <sid> <patch-cid> <causal-ordinal> "<reason>" <reviewer> "<receipt-ref>"
    java -cp "$CP" borg.trikeshed.jules.JulesSettlementCli settle-report <sid> <origin/master-sha> <STATE> "<disposition>" "<exact title>"
    java -cp "$CP" borg.trikeshed.jules.JulesSettlementCli settle-reject  <sid> <origin/master-sha> <STATE> "<disposition>" "<exact title>"

## WAL title extraction (embedded newlines)

WAL session titles can contain embedded newlines and unicode escapes; shell
argv cannot carry them and the settlement CLIs validate title equality
byte-exact. Extract via the store's own codec:

    jshell --class-path "$CP" - <<'EOF' | grep TITLE-B64
    import borg.trikeshed.utils.kanban.JulesBoardStore
    import borg.trikeshed.utils.kanban.JulesBoardStoreJvmKt
    import java.io.File
    import java.util.Base64
    val store = JulesBoardStoreJvmKt.forForgeDir(File(System.getProperty("user.home"), ".local/forge"))
    val card = store.load()["<sid>"] ?: error("no card")
    println("TITLE-B64=" + Base64.getEncoder().encodeToString(card.snapshot.title.toByteArray(Charsets.UTF_8)))
    EOF

then pass `$(echo <b64> | base64 -d)` as the title argument.

## Known traps

- DrainFailed with "bad revision": the session's patch chain references a
  revision absent from the repo — re-pull the session, or settle-report /
  settle-reject.
- COMPLETED sessions whose final report is a question need the operator path
  (`report` → `settle-report`); the daemon's conductor.answer only fires for
  AWAITING sessions.
- There is no `jules kill/answer` CLI verb. Stalled IN_PROGRESS sessions are
  uncancelable; harvest or settle when they go terminal.
- CAS objects are 0600 — read via `cat`/pipe, not editors.
- `refs/remotes/gh/master` is a stale tracking ref from a removed remote;
  ignore it (audit `git ls-remote origin refs/heads/master`, not bare
  `ls-remote` output).
