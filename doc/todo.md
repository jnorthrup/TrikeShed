# TrikeShed task intake

The flywheel inducts every unchecked line (`- [ ] ...`) below as a kanban task
and dispatches it to a Jules session, top of file = highest priority. Add new
tasks anywhere; mark done with `- [x]`. Non-checkbox lines are ignored.

## Product

- [ ] Collapse the three AppendWal implementations onto one SPI: delete the expect/actual pairs borg.trikeshed.kanban.AppendWal (jvmMain kanban/AppendWalJvm.kt) and borg.trikeshed.couch.wal.AppendWal (jvmMain couch/wal/AppendWalJvm.kt), route every call site through borg.trikeshed.lib.AppendWal implemented by userspace/nio/file/spi/JvmAppendWal.kt
- [ ] KanbanEventCodec decode parity: unescape JSON string content (\n \t \" \\ \uXXXX) when decoding WAL records — card titles and cause excerpts currently carry literal backslash-n because JsonParser.reify slices raw escaped token chars
- [ ] UnifiedBoard behavioral consumer: feed UnifiedBoard.bottleneck() into flywheel RANK so board saturation shapes dispatch priority (the merged Forge+Jules projection is currently render-only via renderSaturation)

## Wheel machinery

- [ ] Wire JulesRestClient.deleteSession into drain completion so terminal Jules sessions are closed and stop occupying fleet slots
- [ ] Reap flywheel/jules-* annotated tags: FlywheelHistoryReaper exists as manual CLI but is never invoked from a cycle; wire it into SETTLE or delete the class
