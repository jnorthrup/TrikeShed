# TrikeShed task intake

The flywheel inducts every unchecked line (`- [ ] ...`) below as a kanban task
and dispatches it to a Jules session, top of file = highest priority. Add new
tasks anywhere; mark done with `- [x]`. Non-checkbox lines are ignored.

## Product

(no open tasks)

<!--
Struck 2026-08-30, same reason as the block below: both lines were standing orders
against a world that had already changed, and one of them was a standing order to
REINTRODUCE a bug.

  - Collapse the three AppendWal implementations onto one SPI …
    Already done. There is exactly one declaration — `borg.trikeshed.lib.AppendWal`
    (commonMain/lib/appendWal.kt) — implemented by `JvmAppendWal`. Both files the
    task named for deletion are absent (`jvmMain/kanban/AppendWalJvm.kt`,
    `jvmMain/couch/wal/AppendWalJvm.kt`), no `couch/wal` package exists, and every
    call site already imports `borg.trikeshed.lib.AppendWal` (JulesBoardStore,
    JiraQueueAdapter, ModelCallLeaf, QueueGraphWork) or constructs `JvmAppendWal`
    directly (JulesBoardStoreJvm, ReapAppend, and the tests). Nothing left to collapse.

  - KanbanEventCodec decode parity: unescape JSON string content …
    DANGEROUS AS WRITTEN — following it would put the bug back. The premise held once:
    JsonParser used to hand back raw escaped chars, and an `unescape()` pass was added
    to compensate. JsonParser was then fixed (`Json.kt` unescapeJson, lines 177/203),
    which turned that compensating pass into a DOUBLE decode: a title containing `\"`
    came back as `"`, and `\\` collapsed to `\`. Only the escapes people check (\n, \t)
    looked right, which is why it survived. Fixed by DELETING the second pass in
    KanbanEventCodec, and in ForgeBoardPersistence.decode where the same double-decode
    also broke the contentId re-hash — a markdown description with a code fence or a
    Windows path failed `require` and the envelope would not load at all.
    Guarded now by KanbanEventCodecEscapeTest and ForgeBoardPersistenceEscapeTest.

Kept as a comment, not deleted outright: non-checkbox lines are ignored by the inducter,
so this records what was removed and why without re-arming any of it.
-->

<!--
Struck 2026-08-30. Three lines here described work over classes that no longer exist, and
because every unchecked line above is inducted and dispatched, they were a standing order to
rebuild what had just been deleted — which is why the flywheel kept coming back:

  - UnifiedBoard behavioral consumer: feed UnifiedBoard.bottleneck() into flywheel RANK
    (UnifiedBoard deleted in 66ed9c29d)
  - Wire JulesRestClient.deleteSession into drain completion
    (JulesRestClient deleted in 3544a3968; Jules is externalized)
  - Reap flywheel/jules-* annotated tags via FlywheelHistoryReaper
    (FlywheelHistoryReaper went with the flywheel in 4790932e5, "flywheel deleted 100%")

Kept as a comment, not deleted outright: non-checkbox lines are ignored by the inducter, so
this records what was removed and why without re-arming any of it.
-->

