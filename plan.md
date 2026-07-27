1. **Add `@Volatile var isRunning = true` to `OroborosDaemon`**
   - This flag will be checked in the `while` loop instead of `while(true)`
2. **Wire up `sun.misc.Signal` handlers**
   - In `main()`, after `driver` is initialized, wire up signal handlers for "TERM" and "INT".
   - The handlers will call `driver.close()` and set `isRunning = false`.
3. **Refactor the `while (true)` loop**
   - Change `while (true)` to `while (isRunning)`.
4. **Create a test file `OroborosDaemonShutdownTest.kt`**
   - This test should verify that a `SIGTERM` handler is registered and triggers a fast shutdown (less than 2s).
5. **Run tests**
   - Specifically run `./gradlew jvmTest --tests "*OroborosDaemonShutdownTest" --no-daemon`.
6. **Pre-commit checks**
   - Complete pre commit steps to make sure proper testing, verifications, reviews and reflections are done.
