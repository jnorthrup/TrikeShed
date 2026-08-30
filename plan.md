1. **Fix `QaLaguna.kt` Deadlock Risk:**
   - In `src/jvmMain/kotlin/borg/trikeshed/jules/QaLaguna.kt`, `conflictFiles` launches a git process and calls `p.waitFor(10_000)` *before* reading `inputStream`. If git produces a large output, it blocks writing to the OS pipe until the buffer is read, but the caller thread is blocked on `waitFor`.
   - Update it to use `coroutineScope`, an `async` block for reading `readText()`, bounded `waitFor(10_000, TimeUnit.MILLISECONDS)` with `destroyForcibly()` if it timeouts, and then `await()`.
2. **Fix `HeatSoak.kt` Deadlock Risk:**
   - In `src/jvmMain/kotlin/borg/trikeshed/graal/subvm/demo/HeatSoak.kt`, `classHistogram` calls `readLines()` synchronously before `waitFor()`. If the process hangs, `readLines()` blocks indefinitely.
   - Update it to read asynchronously via `CompletableFuture.supplyAsync`, call bounded `waitFor(5, TimeUnit.SECONDS)` with `destroyForcibly()`, and `get()` the result.
3. **Verify Changes:**
   - Compile JVM targets: `./gradlew :jvmMainClasses --no-daemon` to ensure correct syntax.
4. **Complete pre-commit steps:**
   - Pre-commit steps to ensure proper testing, verification, review, and reflection are done.
5. **Submit PR:**
   - Create a PR using `submit` tool to address this DoS / Thread Starvation deadlock security issue.
