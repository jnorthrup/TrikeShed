1. **Phase 1: HTX Mutex Decomposition (Tier 1)**
   - Use `replace_with_git_merge_diff` to modify `src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt`.
   - Replace the single `htxMutex` with three separate mutexes: `drainMutex`, `answerMutex`, and `dispatchMutex`.
   - Update `htxMutex.withLock` calls in `startReactiveCycle` (lines 625-632 for drain, 644-650 for answer, 661-665 for approve, 745-758 for dispatch) to use the appropriate new mutexes.
   - Use `run_in_bash_session` to execute `./gradlew :jvmMainClasses --no-daemon` to verify the build.

2. **Phase 2: Parallel Drain Pipeline (Tier 3)**
   - Use `replace_with_git_merge_diff` to modify `src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt`.
   - In `drainThreeWay`, change the patch fetching loop (lines 827-854) to CAS-put all patches concurrently using `kotlinx.coroutines.coroutineScope` and `kotlinx.coroutines.async`.
   - Use `run_in_bash_session` to execute `./gradlew :jvmMainClasses --no-daemon` to verify the build.

3. **Phase 3: Formal Jules FSM (Tier 2)**
   - Use `run_in_bash_session` to create `src/commonMain/kotlin/fsm/FlywheelState.kt` containing `FlywheelState` sealed class and `StateMachine`.
   - Use `run_in_bash_session` to create `src/commonMain/kotlin/metrics/FlywheelMetrics.kt` containing `FlywheelMetrics` object.
   - Use `write_file` to create `src/commonMain/kotlin/borg/trikeshed/jules/JulesSessionFSM.kt` containing `JulesSessionState` sealed class and `toJulesState` extension function.
   - Use `replace_with_git_merge_diff` to modify `src/jvmMain/kotlin/borg/trikeshed/jules/JulesConductor.kt` (lines 101-104) to use `s.state.toJulesState() == JulesSessionState.AwaitingUserFeedback`.
   - Use `replace_with_git_merge_diff` to modify `src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt` (lines 639-640) to check `it.snapshot.state.toJulesState() == JulesSessionState.AwaitingUserFeedback`.
   - Use `run_in_bash_session` to execute `./gradlew :jvmMainClasses --no-daemon` to verify the build.

4. **Phase 4: ModelRegistry (Tier 5)**
   - Use `write_file` to create `src/commonMain/kotlin/borg/trikeshed/userspace/reactor/ModelRegistry.kt` containing `ModelRegistryConfig` and `MuxModelEntryConfig`.
   - Use `replace_with_git_merge_diff` on `src/commonMain/kotlin/borg/trikeshed/userspace/reactor/MuxReactorElement.kt`, `ModelApiCache.kt`, and `KanbanFSM.kt` to replace references to `~/.hermes/model_cache.json` with `~/.local/forge/modelmux/models.json`.
   - Use `run_in_bash_session` to execute `./gradlew :commonMainClasses --no-daemon` to verify the build.

5. **Phase 5: Smart Conflict Resolution (Tier 4)**
   - Use `write_file` to create `src/jvmMain/kotlin/borg/trikeshed/jules/ConflictResolver.kt` containing `ConflictResolver` stub.
   - Use `replace_with_git_merge_diff` to modify `src/commonMain/kotlin/borg/trikeshed/jules/BrainClient.kt` to route through `ModelMux.route("conflict-resolve")` and add `"conflict-resolve"` to caps.
   - Use `run_in_bash_session` to execute `./gradlew :jvmMainClasses --no-daemon` to verify the build.

6. **Phase 6: Settlement Barrier Relaxation (Tier 6)**
   - Use `replace_with_git_merge_diff` to edit `src/jvmMain/kotlin/borg/trikeshed/jules/FlywheelDriver.kt`.
   - Modify `settlementBarrier()` (lines 1513-1525) to relax `undrainedCompleted > 5` and `unclaimedDrains > 3`.
   - Use `run_in_bash_session` to execute `./gradlew :jvmMainClasses --no-daemon` to verify the build.

7. **Pre-commit Verification**
   - Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done. Use `./gradlew jvmMainClasses --console=plain` to ensure no regressions.
