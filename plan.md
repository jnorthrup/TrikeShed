1. **Optimize `JulesPatchContinuity.kt`**
   - In `selectJulesPatchForDrain`, lines 71: `val observations = causalList.filterIsInstance<JulesCause.PatchSnapshotObserved>()` followed by `observations.isEmpty()` and `observations.maxWith(snapshotCausalOrder)`. This can be replaced by a zero-allocation `for` loop to find the max observation, and we don't need the intermediate list.
   - In `selectJulesReportForSettlement`, lines 183: `val reports = causalList.filterIsInstance<JulesCause.AgentReportObserved>()` followed by `reports.isEmpty()` and `reports.maxWith(reportCausalOrder)`. This can also be replaced with a single pass loop.
2. **Optimize `PropertyEditor.kt`**
   - Lines 52, 53, 71, 73, 84, 98: `(value as? List<*>)?.filterIsInstance<String>() ?: emptyList()` allocates lists.
3. **Verify the change**
   - Use `./gradlew :jvmMainClasses --no-daemon` and `./gradlew :jvmTest --tests 'borg.trikeshed.jules.*' --no-daemon` to ensure it still compiles and works.
4. **Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done.**
5. **Submit the PR**
   - The commit message should be in the format: `⚡ Bolt: [performance improvement]` and the description should follow Bolt's guidelines.
