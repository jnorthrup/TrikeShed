# TrikeShed task intake

The flywheel inducts every unchecked line (`- [ ] ...`) below as a kanban task
and dispatches it to a Jules session, top of file = highest priority. Add new
tasks anywhere; mark done with `- [x]`. Non-checkbox lines are ignored.

## Product

- [ ] Collapse the three AppendWal implementations onto one SPI: delete the expect/actual pairs borg.trikeshed.kanban.AppendWal (jvmMain kanban/AppendWalJvm.kt) and borg.trikeshed.couch.wal.AppendWal (jvmMain couch/wal/AppendWalJvm.kt), route every call site through borg.trikeshed.lib.AppendWal implemented by userspace/nio/file/spi/JvmAppendWal.kt
- [ ] KanbanEventCodec decode parity: unescape JSON string content (\n \t \" \\ \uXXXX) when decoding WAL records — card titles and cause excerpts currently carry literal backslash-n because JsonParser.reify slices raw escaped token chars

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

