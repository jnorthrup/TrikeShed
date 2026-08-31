1. Modify `src/commonMain/kotlin/borg/trikeshed/ccek/CCEK.kt` using `replace_with_git_merge_diff`
   - Between lines 301-309, remove the intermediate `.asSequence()` allocation before `.filter` and `.forEach`. Since `doc.blocks.values` is a collection, creating a Sequence wrapper and its lazy iterators introduces object allocation overhead. Add a comment explaining the zero-allocation iteration optimization.
2. Modify `src/commonMain/kotlin/borg/trikeshed/kanban/KanbanGraph.kt` using `replace_with_git_merge_diff`
   - Between lines 126-175, remove the `toList()` allocations on `Series` objects and add a comment explaining the zero-allocation optimization:
     - Line 128: Change `lanes.toList().map { it.id }` to `lanes.map { it.id }`
     - Line 130: Change `lanes.toList().forEach` to `lanes.forEach`
     - Line 133: Change `edges.toList().forEach` to `edges.forEach`
     - Line 155: Change `edges.toList().groupBy` to `edges.view.groupBy`
     - Line 160: Change `cards.toList().forEach` to `cards.forEach`
     - Line 171: Change `edges.toList().any` to `edges.view.any` (line 171 verified from earlier `cat` command output)
3. Verify changes using `run_in_bash_session`
   - Run `./gradlew :jvmMainClasses --no-daemon` to ensure it compiles.
   - Run tests related to `KanbanGraph` and `CCEK` using `./gradlew :jvmTest --tests 'borg.trikeshed.ccek.*' --tests 'borg.trikeshed.graph.CausalGraphKanbanTest' --no-daemon` to ensure there are no regressions.
4. Complete pre commit steps to ensure proper testing, verification, review, and reflection are done.
5. Create PR using `create_pr` tool.
