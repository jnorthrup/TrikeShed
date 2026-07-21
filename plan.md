1. **Create `StateMachineTest.kt`**
   - Location: `src/commonTest/kotlin/borg/trikeshed/fsm/StateMachineTest.kt`
   - Content: A simple test using `kotlin.test` verifying initial state and basic transition, containing `fail("not implemented")`.
2. **Create `MemvidStoragePipelineTest.kt`**
   - Location: `src/commonTest/kotlin/borg/trikeshed/memvid/MemvidStoragePipelineTest.kt`
   - Content: A test verifying frame splitting and restoration, containing `fail("not implemented")`.
3. **Create `ModelMuxTest.kt`**
   - Location: `src/commonTest/kotlin/borg/trikeshed/modelmux/ModelMuxTest.kt`
   - Content: A test verifying rule selection and model invocation, containing `fail("not implemented")`.
4. **Create `ProfilerTest.kt`**
   - Location: `src/commonTest/kotlin/borg/trikeshed/profile/ProfilerTest.kt`
   - Content: A test verifying snapshot timing or creation, containing `fail("not implemented")`.
5. **Create `ForgeKanbanDaemonFanoutTest.kt`**
   - Location: `src/commonTest/kotlin/borg/trikeshed/kanban/ForgeKanbanDaemonFanoutTest.kt`
   - Content: A test verifying reactive fanout behavior, containing `fail("not implemented")`.
6. **Pre-commit and verify**
   - Verify that all files compile and run `./gradlew jvmTest`. We expect them to FAIL according to the prompt (RED phase).
   - Use `pre_commit_instructions` tool.
