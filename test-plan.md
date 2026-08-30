1. **Identify Vulnerability:** Review `JvmVitals.kt`, `QaLaguna.kt`, `JulesSettlementCli.kt`, and `JulesDrainDedupeCli.kt` where `Process.waitFor()` is called. These instances synchronously block on `waitFor()` before completely reading the process streams or read streams synchronously before calling `waitFor()`, which can lead to Denial of Service via pipe buffer deadlock (a known issue recorded in `.jules/sentinel.md`).

2. **Fix `JvmVitals.kt`:** Modify the timeout and thread logic.
3. **Fix `QaLaguna.kt`:** Read `inputStream` using `async` or an asynchronous thread while using `withTimeout` for `waitFor()`.
4. **Fix `JulesSettlementCli.kt` and `JulesDrainDedupeCli.kt`**: ensure streams are consumed asynchronously before `waitFor()`. Wait, in `JulesDrainDedupeCli.kt` and `JulesSettlementCli.kt`, they are using `this.async` from a coroutine scope. It is already reading asynchronously. In `JvmVitals.kt`, they are reading it in a thread. In `QaLaguna.kt`:
```kotlin
        suspend fun git(vararg args: String): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val p = ProcessBuilder("git", *args)
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()
            if (!p.waitFor(10_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroy()
            }
            p.inputStream.bufferedReader().readText().trim().lines()
                .filter { it.isNotBlank() }
        }
```
In `QaLaguna.kt`, `readText()` is called *after* `waitFor()`. If the process writes more than the pipe buffer size to stdout/stderr, it blocks trying to write to the pipe. But `QaLaguna.kt` is blocked on `waitFor()`, waiting for the process to exit. Deadlock.

Let's check `HeatSoak.kt`:
```kotlin
    fun classHistogram(n: Int): String = runCatching {
        val jcmd = java.io.File(System.getProperty("java.home"), "bin/jcmd").path
        val p = ProcessBuilder(jcmd, ProcessHandle.current().pid().toString(), "GC.class_histogram").redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().readLines(); p.waitFor()
        "\n── class histogram (live, top $n) ──\n" + lines.take(n + 3).joinToString("\n") { it.take(140) }
    }.getOrElse { "\n── class histogram unavailable: $it" }
```
Here it synchronously reads all lines *before* calling `waitFor()`. This doesn't deadlock because `readLines()` consumes the pipe, allowing the process to finish and exit, then `waitFor()` returns. But there is no timeout on `waitFor()` here, which is another issue ("Denial of Service via Unbounded waitFor"). Wait, no, `readLines()` will block if the process hangs and keeps the stream open without outputting.

Let's check `JvmVitals.kt` line 258:
```kotlin
            try {
                val pid = ProcessHandle.current().pid()
                val javaExe = ProcessHandle.current().info().command().orElse("java")
                val dir = javaExe.substringBeforeLast('/', "")
                val jcmd = if (dir.isNotEmpty()) "$dir/jcmd" else "jcmd"
                val p = ProcessBuilder(jcmd, pid.toString(), "GC.class_histogram")
                    .redirectErrorStream(true).start()
                val out = java.util.concurrent.atomic.AtomicReference("")
                val reader = Thread { runCatching { out.set(p.inputStream.bufferedReader().readText()) } }
                reader.isDaemon = true
                reader.name = "jvmvitals-class-histogram-reader"
                reader.start()
                reader.join(JCMD_TIMEOUT_MS)
                // The worker kills the child on timeout — the main thread never calls waitFor/destroy.
```
This looks ok because it joins the reader thread with a timeout.

Let's fix `QaLaguna.kt`.
And `HeatSoak.kt`.

Wait, the memory prompt says:
* When enforcing process timeouts in Kotlin coroutines (e.g., `process.waitFor(1, TimeUnit.HOURS)`) to prevent DoS, never synchronously read the process's `InputStream` (via `readText()` or `readLines()`) beforehand, as it blocks indefinitely if the child process hangs without closing its output. Instead, use a `coroutineScope`, wrap the stream read in an `async` block, execute the bounded `waitFor`, and then `await()` the result.
* When enforcing process timeouts in non-coroutine Java/Kotlin contexts to prevent DoS via pipe buffer deadlocks, never synchronously read the process's `InputStream`. Instead, use `java.util.concurrent.CompletableFuture.supplyAsync` (or `runAsync`) to read the stream asynchronously, execute a bounded `waitFor`, and then `get()` the result.

So for `QaLaguna.kt`:
```kotlin
        suspend fun git(vararg args: String): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.coroutineScope {
                val p = ProcessBuilder("git", *args)
                    .directory(repoDir)
                    .redirectErrorStream(true)
                    .start()
                val outDeferred = this.async { p.inputStream.bufferedReader().readText() }
                if (!p.waitFor(10_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
                outDeferred.await().trim().lines().filter { it.isNotBlank() }
            }
        }
```

For `HeatSoak.kt`:
```kotlin
    fun classHistogram(n: Int): String = runCatching {
        val jcmd = java.io.File(System.getProperty("java.home"), "bin/jcmd").path
        val p = ProcessBuilder(jcmd, ProcessHandle.current().pid().toString(), "GC.class_histogram").redirectErrorStream(true).start()
        val future = java.util.concurrent.CompletableFuture.supplyAsync {
            p.inputStream.bufferedReader().readLines()
        }
        if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly()
        }
        val lines = runCatching { future.get(1, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(emptyList())
        "\n── class histogram (live, top $n) ──\n" + lines.take(n + 3).joinToString("\n") { it.take(140) }
    }.getOrElse { "\n── class histogram unavailable: $it" }
```
