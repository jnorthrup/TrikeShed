1. Modify `FlywheelHistoryReaper.kt`: Remove all current code (as it's related to the manual JSONL CAS ingestion CLI which is never used) and rewrite it to have a simple `reapOldTags` function. This function will run `git tag -l "flywheel/jules-*"` and delete tags, keeping a reasonable number like 50.
2. Update `FlywheelDriver.kt` in the `settlementBarrier` function. Wire in a call to `FlywheelHistoryReaper.reapOldTags(...)` so tags are automatically reaped in the SETTLE phase.
3. Validate compilation with `./gradlew jvmMainClasses --no-daemon`.
4. Run pre commit instructions.
5. Submit changes.
