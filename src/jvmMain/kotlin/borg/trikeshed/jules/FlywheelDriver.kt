/* Copyright (c) 2017 TrikeShed Contributors. AGPLv3 — see LICENSE. */
package borg.trikeshed.jules

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Stateless flywheel as a CCEK reactor: state = `~/.local/forge/jules-board.wal`
 * + `origin/master`. One cycle is concurrent structured concurrency — POLL +
 * DRAIN + DISPATCH run as `async` tasks fan-outed off a single semaphore.
 *
 * Run: `./gradlew jvmRun -PmainClass=borg.trikeshed.jules.FlywheelDriver --args="--watch"`
 */
class FlywheelDriver(
    private val apiKey: String,
    private val repoDir: File = File(System.getProperty("user.dir")),
    private val forgeDir: File = File(System.getProperty("user.home"), ".local/forge"),
    private val intervalMs: Long = 30_000L,
    private val maxSlots: Int = 15,
    private val source: String = "sources/github/jnorthrup/TrikeShed",
) {
    private val client = JulesRestClient(apiKey)
    private val casStore = FileCasStore(
        JvmFileOperations(),
        JvmFileOperations().resolvePath(forgeDir.absolutePath, "cas"),
    )
    // Bounded concurrency for any git/htx work inside the reactor.
    private val ioGate = Semaphore(permits = maxSlots)

    /** One reactor tick. POLL is awaited (cheap h2), then DRAIN + DISPATCH fan out. */
    suspend fun cycle(): String {
        // 1. POLL — single h2 roundtrip on the jules endpoint.
        val sessions = withTimeoutOrNull(20_000) { client.listSessions(source) } ?: emptyList()
        if (sessions.isEmpty()) return "[FLYWHEEL] poll-empty alive=0"

        val completed = sessions.filter { it.state == "COMPLETED" }
        val alive = sessions.count { it.state != "COMPLETED" && it.state != "FINISHED" }
        val available = (maxSlots - alive).coerceAtLeast(0)

        // 2. DRAIN + DISPATCH fan out concurrently under the ioGate semaphore.
        var drained = 0
        var dispatched = 0
        return coroutineScope {
            val drainJobs = completed.take(maxSlots).map { s ->
                async(Dispatchers.IO) {
                    ioGate.withPermit {
                        try {
                            val patch = client.lastPatch(s.id) ?: return@withPermit false
                            if (patch.isBlank()) return@withPermit false
                            if (!isWorkingTreeClean()) return@withPermit false
                            val claim = applyAndCommit(s.id, s.title, patch) ?: return@withPermit false
                            println("[FLYWHEEL] DRAIN session=${s.id.takeLast(6)} sha=${claim.commitSha.take(9)} tag=${claim.receipt.versionTag}")
                            true
                        } catch (t: Throwable) {
                            println("[FLYWHEEL] DRAIN-FAIL ${s.id.takeLast(6)}: ${t.message}")
                            false
                        }
                    }
                }
            }
            val drainDone = drainJobs.awaitAll().count { it }
            drained = drainDone

            // Dispatch: poll doc/todo.md, create sessions concurrently up to available slots.
            val titles = if (available > 0) {
                val todo = File(repoDir, "doc/todo.md")
                if (!todo.exists()) emptyList()
                else todo.readLines()
                    .mapNotNull { Regex("^\\s*- \\[ \\]\\s*\\*\\*?(.+?)\\*\\*?$").find(it)?.groupValues?.get(1)?.trim() }
                    .take(available)
            } else emptyList()

            val dispatchJobs = titles.map { title ->
                async(Dispatchers.IO) {
                    ioGate.withPermit {
                        try {
                            val sid = client.createSession(prompt = "TDD: $title", title = title, source = source)
                            println("[FLYWHEEL] DISPATCH $sid ${title.take(60)}")
                            true
                        } catch (t: Throwable) {
                            println("[FLYWHEEL] DISPATCH-FAIL ${title.take(60)}: ${t.message}")
                            false
                        }
                    }
                }
            }
            dispatched = dispatchJobs.awaitAll().count { it }

            "drained=$drained dispatched=$dispatched alive=$alive available=$available"
        }
    }

    /** Apply+commit+tag+CAS. Cheap; runs inside the ioGate permit. */
    private fun applyAndCommit(sessionId: String, title: String, patch: String): ClaimedPatch? {
        val patchFile = File(repoDir, ".flywheel-patch")
        patchFile.writeText(patch)
        val applyCheck = ProcessBuilder("git", "apply", "--check", ".flywheel-patch")
            .directory(repoDir).redirectErrorStream(true).start()
        applyCheck.waitFor()
        if (applyCheck.exitValue() != 0) { patchFile.delete(); return null }
        ProcessBuilder("git", "apply", ".flywheel-patch").directory(repoDir).start().waitFor()
        patchFile.delete()
        val commitSha = headSha()
        val patchBytes = patch.encodeToByteArray()
        val patchCid = try { casStore.put(patchBytes) } catch (_: Exception) { return null }
        val safe = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val tag = "flywheel/jules-$safe-${commitSha.take(12)}"
        val msg = ("Jules receipt" + "\n" +
            "session=" + sessionId + "\n" +
            "patchCid=" + patchCid.value + "\n" +
            "taskTitle=" + title)
        if (command("git", "tag", "-a", tag, commitSha, "-m", msg).exitCode != 0) return null
        val receipt = MergeReceipt(
            workId = "session:" + sessionId,
            producer = "jules",
            producerRef = sessionId,
            patchCid = patchCid,
            revision = commitSha,
            versionTag = tag,
            lexicalMemory = LexicalMemory(summary = title, title = title, content = title),
            claimedAt = System.currentTimeMillis(),
        )
        return ClaimedPatch(commitSha, receipt)
    }

    private fun isWorkingTreeClean(): Boolean = command("git", "status", "--porcelain").output.isBlank()
    private fun headSha(): String = command("git", "rev-parse", "HEAD").output.trim()
    private fun command(vararg args: String): CommandResult =
        try {
            val p = ProcessBuilder(*args).directory(repoDir).redirectErrorStream(true).start()
            CommandResult(p.waitFor(), p.inputStream.bufferedReader().readText())
        } catch (t: Throwable) { CommandResult(1, t.message.orEmpty()) }

    private data class ClaimedPatch(val commitSha: String, val receipt: MergeReceipt)
    private data class CommandResult(val exitCode: Int, val output: String)

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val apiKey = System.getenv("JULES_API_KEY") ?: error("JULES_API_KEY required")
            val watch = args.any { it == "--watch" }
            val driver = FlywheelDriver(apiKey)
            println("[FLYWHEEL] starting on ${driver.repoDir} ${if (watch) "watching" else "one-shot"}")
            runBlocking {
                if (!watch) {
                    println("[FLYWHEEL] " + driver.cycle())
                    return@runBlocking
                }
                while (true) {
                    val start = System.currentTimeMillis()
                    println("[FLYWHEEL] " + driver.cycle())
                    val elapsed = System.currentTimeMillis() - start
                    delay(elapsed.coerceAtMost(driver.intervalMs))
                }
            }
        }
    }
}
