package borg.trikeshed.agent

import borg.trikeshed.couch.*
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.*
import borg.trikeshed.lcnc.*
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolPipes
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import borg.trikeshed.userspace.nio.process.*
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Real local Git and shell processes; no model, network, or replacement AgentRuns implementation. */
class JvmAgentRunnerProcessTest {
    @TempDir lateinit var directory: Path

    private suspend fun command(directory: File, vararg arguments: String): ProcessCapture {
        val pipes = JvmProcessProtocolPipes.open(command = { _, _ -> arguments.toList() },
            environment = AgentEnvironment.build(System.getenv(), AgentEnvironment.SYSTEM_PATH),
            workingDirectory = directory.absolutePath, mergeError = true)
        return ProcessCaptureChannel(currentCoroutineContext(), pipes, maxBytes = 1_048_576, timeoutMillis = 10_000).await()
    }

    private suspend fun git(directory: File, vararg arguments: String): String {
        val result = command(directory, "/usr/bin/git", *arguments)
        check(result.exit == 0 && !result.timedOut) { result.text() }
        return result.text().trim()
    }

    private suspend fun source(committed: Boolean = true): File {
        val source = Files.createDirectories(directory.resolve("source")).toFile()
        git(source, "init", "-q", "-b", "main")
        git(source, "config", "user.name", "process fixture")
        git(source, "config", "user.email", "fixture@localhost")
        if (committed) {
            Files.writeString(source.toPath().resolve("README.md"), "Committed source\n")
            git(source, "add", "README.md")
            git(source, "commit", "-q", "-m", "source")
            Files.writeString(source.toPath().resolve("uncommitted.txt"), "source only\n")
        }
        return source
    }

    private fun runner(source: File, cas: CasStore, board: ConfixBlackboard, code: String,
                       arguments: List<String> = emptyList(), attachments: CouchAttachmentGateway? = null): JvmAgentRunner =
        JvmAgentRunner(
            listOf(AgentCliInfo("local", "/bin/sh", "local shell", true)), source,
            directory.resolve("forge").toFile(), cas, attachments, board,
            environment = mapOf("HOME" to directory.toString(), "LANG" to "C", "PRIVATE_API_KEY" to "must-not-pass", "SAFE_VAR" to "must-not-pass"),
            extraEnvironment = mapOf("DECLARED_FIXTURE" to "visible"),
            definitions = { AgentCli(it.id, it.path, true) { _, _, last, _ ->
                listOf("/bin/sh", "-c", code, "agent-process-fixture", last.absolutePath) + arguments
            } },
            toolDirs = listOf("/usr/bin", "/bin"),
        )

    private suspend fun pids(path: Path): List<String> = withTimeout(5_000) {
        while (true) {
            val text = if (Files.isRegularFile(path)) Files.readString(path) else ""
            if (text.endsWith('\n')) return@withTimeout text.trim().split(' ')
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE") emptyList()
    }

    private suspend fun assertReaped(source: File, pids: List<String>) {
        for (pid in pids) assertNotEquals(0, command(source, "/bin/kill", "-0", pid).exit, "Process $pid remains alive")
    }

    @Test fun realAgentDrainsBeyondPipeCapacityAndRetainsExactScratchEvidence() = runBlocking(JvmFileOperations()) {
        val source = source()
        val head = git(source, "rev-parse", "HEAD")
        val status = git(source, "status", "--porcelain")
        val cas = CasStore.inMemory()
        val board = ConfixBlackboard.empty()
        val attachments = CouchAttachmentGateway(CouchStoreFactory.casBacked(cas), cas)
        val code = """
            printf 'home=%s\nsecret=%s\nsafe=%s\nextra=%s\n' "${'$'}HOME" "${'$'}{PRIVATE_API_KEY-unset}" "${'$'}{SAFE_VAR-unset}" "${'$'}DECLARED_FIXTURE"
            printf 'merged stderr\n' >&2
            awk 'BEGIN { for (i=0; i<131072; i++) printf "x" }'
            cat > captured.txt
            [ ! -e uncommitted.txt ] || exit 91
            printf 'scratch effect\n' > result.txt
            printf 'Completed by the actual local subprocess\n' > "${'$'}1"
        """.trimIndent()
        val brief = "A substantial stdin brief\n".repeat(16_384)
        val result = withTimeout(15_000) { runner(source, cas, board, code, attachments = attachments)
            .run(AgentRunRequest("local", brief, budgetSeconds = 10, maxBytes = 4096, retain = true, runId = "actual", jobId = "task")) }
        assertTrue(result.ok, result.error)
        assertEquals("actual", result.runId)
        assertEquals("task", result.jobId)
        assertEquals("local", result.agent)
        assertEquals(head, result.base)
        assertEquals(4096L, result.kept)
        assertTrue(result.bytes > 131072)
        assertTrue(result.truncated)
        assertEquals("Completed by the actual local subprocess", result.summary)
        val scratch = Path.of(result.scratch).resolve("repo")
        assertContentEquals((brief + "\n").encodeToByteArray(), Files.readAllBytes(scratch.resolve("captured.txt")))
        val transcript = assertNotNull(cas.get(ContentId(result.transcriptCid))).decodeToString()
        assertContains(transcript, "home=$directory\nsecret=unset\nsafe=unset\nextra=visible\nmerged stderr\n")
        assertContains(transcript, "[truncated: ${result.bytes - result.kept} bytes over the 4096 byte cap were dropped]")
        val patch = assertNotNull(cas.get(ContentId(result.patchCid))).decodeToString()
        assertContains(patch, "diff --git a/captured.txt b/captured.txt")
        assertContains(patch, "+scratch effect")
        assertEquals(listOf("captured.txt", "result.txt"), result.files)
        assertEquals(3, attachments.listAttachments("agents/actual/").size)
        assertEquals(true, (board.get("agent/run/actual") as Map<*, *>)["ok"])
        assertEquals(status, git(source, "status", "--porcelain"))
        assertEquals(head, git(source, "rev-parse", "HEAD"))
        assertFalse(Files.exists(source.toPath().resolve("result.txt")))
        assertFalse(Files.exists(source.toPath().resolve(".git/worktrees")))
    }

    @Test fun aScratchRemovalFailureCannotLeaveASuccessfulAgentReceipt() = runBlocking {
        val source = source()
        val head = git(source, "rev-parse", "HEAD")
        val status = git(source, "status", "--porcelain")
        val cas = CasStore.inMemory()
        val board = ConfixBlackboard.empty()
        val scratch = directory.resolve("forge/agents/cleanup").toFile()
        val backing = JvmFileOperations()
        val deletions = mutableListOf<String>()
        val cleanupFailure = "injected scratch cleanup failure: ${scratch.path}"
        val files = object : FileOperations by backing {
            override fun deleteRecursively(path: String) {
                if (path == scratch.path) {
                    deletions.add(path)
                    throw java.io.IOException(cleanupFailure)
                }
                backing.deleteRecursively(path)
            }
        }
        // Registration returns this wrapper directly; delegated Element.get/fold cannot select the backing adapter.
        val nio = NioSupervisor().apply { register(files) }
        val brief = "Capture this real process input\n"
        val code = """
            cat > captured.txt
            [ ! -e uncommitted.txt ] || exit 91
            printf 'scratch effect\n' > result.txt
            printf 'actual cleanup process\n'
            printf 'Process completed before cleanup\n' > "${'$'}1"
        """.trimIndent()
        try {
            val result = withTimeout(15_000) {
                withContext(nio) {
                    assertNull(currentCoroutineContext()[FileOperations])
                    assertSame(files, currentCoroutineContext()[NioSupervisor]?.service<FileOperations>())
                    runner(source, cas, board, code).run(AgentRunRequest("local", brief,
                        budgetSeconds = 10, retain = false, runId = "cleanup", jobId = "cleanup-task"))
                }
            }
            assertEquals(listOf(scratch.path), deletions, "The actual non-retained scratch deletion must reach the fault")
            assertTrue(scratch.isDirectory, "The refused deletion must leave real scratch to report")
            assertEquals(0, result.exit)
            assertFalse(result.killed)
            assertEquals("cleanup", result.runId)
            assertEquals("cleanup-task", result.jobId)
            assertEquals("local", result.agent)
            assertEquals(head, result.base)
            assertEquals("Process completed before cleanup", result.summary)
            assertEquals(scratch.absolutePath, result.scratch)
            assertFalse(result.retained, "The caller did not request retention")
            assertContentEquals((brief + "\n").encodeToByteArray(), Files.readAllBytes(scratch.toPath().resolve("repo/captured.txt")))
            assertEquals("scratch effect\n", Files.readString(scratch.toPath().resolve("repo/result.txt")))
            assertFalse(Files.exists(scratch.toPath().resolve("repo/uncommitted.txt")))
            val transcript = assertNotNull(cas.get(ContentId(result.transcriptCid)))
            assertEquals(ContentId(result.transcriptCid), ContentId.of(transcript))
            assertEquals("actual cleanup process\n", transcript.decodeToString())
            val patch = assertNotNull(cas.get(ContentId(result.patchCid)))
            assertEquals(ContentId(result.patchCid), ContentId.of(patch))
            assertContains(patch.decodeToString(), "diff --git a/result.txt b/result.txt")
            assertContains(patch.decodeToString(), "+scratch effect")
            assertEquals(listOf("captured.txt", "result.txt"), result.files)
            assertEquals(status, git(source, "status", "--porcelain"))
            assertEquals(head, git(source, "rev-parse", "HEAD"))
            assertFalse(Files.exists(source.toPath().resolve("result.txt")))
            assertFalse(Files.exists(source.toPath().resolve(".git/worktrees")))
            val receipt = assertIs<Map<*, *>>(board.get("agent/run/cleanup"))
            assertEquals(result.transcriptCid, receipt["transcriptCid"])
            assertEquals(result.patchCid, receipt["patchCid"])
            assertEquals(false, receipt["retained"])
            assertFalse(result.ok, "A reached scratch cleanup failure cannot return success: ${result.receipt()}")
            assertContains(result.error, cleanupFailure)
            assertEquals(false, receipt["ok"])
            assertEquals(result.error, receipt["error"])
            assertEquals(result.receipt(), receipt)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { nio.drain() } finally { backing.deleteRecursively(scratch.path) }
            }
        }
    }

    @Test fun cancellationDuringScratchCleanupKeepsItsCauseAndFailedEvidence() = runBlocking<Unit> {
        val source = source()
        val head = git(source, "rev-parse", "HEAD")
        val status = git(source, "status", "--porcelain")
        val cas = CasStore.inMemory()
        val board = ConfixBlackboard.empty()
        val scratch = directory.resolve("forge/agents/cleanup-cancel").toFile()
        val pidFile = directory.resolve("cleanup-pid")
        val backing = JvmFileOperations()
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val deletions = mutableListOf<String>()
        val cleanupFailure = java.io.IOException("injected held cleanup failure: ${scratch.path}")
        val files = object : FileOperations by backing {
            override fun deleteRecursively(path: String) {
                if (path == scratch.path) {
                    deletions.add(path)
                    entered.complete(Unit)
                    check(release.await(30, java.util.concurrent.TimeUnit.SECONDS)) { "Cleanup gate was not released" }
                    throw cleanupFailure
                }
                backing.deleteRecursively(path)
            }
        }
        val nio = NioSupervisor().apply { register(files) }
        val brief = "Retain actual evidence across cancellation\n"
        val code = """
            cat > captured.txt
            [ ! -e uncommitted.txt ] || exit 91
            printf '%s\n' "${'$'}${'$'}" > "${'$'}2"
            printf 'scratch effect before cancellation\n' > result.txt
            printf 'actual completed cleanup-cancel process\n'
            printf 'Process completed before held cleanup\n' > "${'$'}1"
        """.trimIndent()
        val completed = CompletableDeferred<Throwable?>()
        val running = async(nio) {
            assertNull(currentCoroutineContext()[FileOperations])
            assertSame(files, currentCoroutineContext()[NioSupervisor]?.service<FileOperations>())
            runner(source, cas, board, code, listOf(pidFile.toString()))
                .run(AgentRunRequest("local", brief, budgetSeconds = 10, retain = false,
                    runId = "cleanup-cancel", jobId = "cleanup-cancel-task"))
        }
        running.invokeOnCompletion { completed.complete(it) }
        val cancellation = CancellationException("cancel while actual scratch cleanup is held")
        try {
            withTimeout(15_000) { entered.await() }
            assertEquals(listOf(scratch.path), deletions)
            assertTrue(scratch.isDirectory)
            assertFalse(running.isCompleted)
            assertNull(board.get("agent/run/cleanup-cancel"), "No receipt can precede the cleanup outcome")
            assertContentEquals((brief + "\n").encodeToByteArray(), Files.readAllBytes(scratch.toPath().resolve("repo/captured.txt")))
            assertEquals("scratch effect before cancellation\n", Files.readString(scratch.toPath().resolve("repo/result.txt")))
            assertEquals("Process completed before held cleanup\n", Files.readString(scratch.toPath().resolve("last.md")))
            val transcriptBytes = "actual completed cleanup-cancel process\n".encodeToByteArray()
            assertContentEquals(transcriptBytes, cas.get(ContentId.of(transcriptBytes)))
            assertReaped(source, pids(pidFile))

            running.cancel(cancellation)
            val finishedWhileHeld = withTimeoutOrNull(250) { running.join(); true } ?: false
            assertFalse(finishedWhileHeld, "Cancellation cannot finish the owning run while its admitted cleanup is held")
            assertFalse(running.isCompleted)
            assertNull(board.get("agent/run/cleanup-cancel"), "Held cleanup cannot publish a premature success or failure")
            release.countDown()
            withTimeout(5_000) { running.join() }
            assertTrue(running.isCancelled)
            assertSame(cancellation, completed.await(), "The owning job must retain the original cancellation cause")
            assertTrue(cancellation.suppressedExceptions.any { it === cleanupFailure },
                "The exact reached cleanup failure must be suppressed on the original cancellation")
            assertEquals(listOf(scratch.path), deletions, "The production cleanup attempt must happen once")

            val receipt = assertIs<Map<*, *>>(board.get("agent/run/cleanup-cancel"))
            assertEquals(false, receipt["ok"])
            assertEquals(0, receipt["exit"])
            assertEquals(false, receipt["killed"])
            assertEquals(false, receipt["retained"])
            assertEquals("cleanup-cancel", receipt["runId"])
            assertEquals("cleanup-cancel-task", receipt["jobId"])
            assertEquals("local", receipt["agent"])
            assertEquals(head, receipt["base"])
            assertEquals(scratch.absolutePath, receipt["scratch"])
            assertEquals("Process completed before held cleanup", receipt["summary"])
            val error = assertIs<String>(receipt["error"])
            assertContains(error, "cancelled")
            assertContains(error, assertNotNull(cancellation.message))
            assertContains(error, assertNotNull(cleanupFailure.message))
            assertEquals(listOf("captured.txt", "result.txt"), receipt["files"])
            val transcriptCid = ContentId(assertIs<String>(receipt["transcriptCid"]))
            assertEquals(ContentId.of(transcriptBytes), transcriptCid)
            assertContentEquals(transcriptBytes, cas.get(transcriptCid))
            val patchCid = ContentId(assertIs<String>(receipt["patchCid"]))
            val patch = assertNotNull(cas.get(patchCid))
            assertEquals(patchCid, ContentId.of(patch))
            assertContains(patch.decodeToString(), "diff --git a/result.txt b/result.txt")
            assertContains(patch.decodeToString(), "+scratch effect before cancellation")
            assertFalse(Files.exists(scratch.toPath().resolve("repo/uncommitted.txt")))
            assertTrue(scratch.isDirectory, "The reached IOException leaves actual scratch for its failure receipt")
            assertEquals(status, git(source, "status", "--porcelain"))
            assertEquals(head, git(source, "rev-parse", "HEAD"))
            assertFalse(Files.exists(source.toPath().resolve("result.txt")))
            assertFalse(Files.exists(source.toPath().resolve(".git/worktrees")))
        } finally {
            release.countDown()
            withContext(NonCancellable) {
                try { running.cancelAndJoin() }
                finally {
                    withContext(Dispatchers.IO) {
                        try { nio.drain() } finally { backing.deleteRecursively(scratch.path) }
                    }
                }
            }
        }
        assertFalse(scratch.exists(), "Fixture cleanup must remove the deliberately retained failure scratch")
    }

    @Test fun cancellationReapsTheRealChildTreeBeforeReturningAndRemovesScratch() = runBlocking {
        val source = source()
        val cas = CasStore.inMemory()
        val board = ConfixBlackboard.empty()
        val pidFile = directory.resolve("pids")
        val code = """
            cat > /dev/null
            /bin/sleep 60 &
            child=${'$'}!
            printf '%s %s\n' "${'$'}${'$'}" "${'$'}child" > "${'$'}2"
            wait "${'$'}child"
        """.trimIndent()
        val run = async { runner(source, cas, board, code, listOf(pidFile.toString()))
            .run(AgentRunRequest("local", "wait until explicitly cancelled", budgetSeconds = 60, runId = "cancel")) }
        val owned = pids(pidFile)
        withTimeout(5_000) { run.cancelAndJoin() }
        assertTrue(run.isCancelled)
        assertReaped(source, owned)
        assertFalse(Files.exists(directory.resolve("forge/agents/cancel")))
        val receipt = assertIs<Map<*, *>>(board.get("agent/run/cancel"))
        assertEquals(false, receipt["ok"])
        assertContains(receipt["error"].toString(), "cancelled")
    }

    @Test fun budgetRevocationReapsTheRealTreeAndRetainsFailureWithoutAFalsePatch() = runBlocking {
        val source = source()
        val cas = CasStore.inMemory()
        val board = ConfixBlackboard.empty()
        val pidFile = directory.resolve("pids")
        val code = "cat > /dev/null; /bin/sleep 60 & child=${'$'}!; printf '%s %s\\n' \"${'$'}${'$'}\" \"${'$'}child\" > \"${'$'}2\"; wait \"${'$'}child\""
        val run = async { runner(source, cas, board, code, listOf(pidFile.toString()))
            .run(AgentRunRequest("local", "bounded local process", budgetSeconds = 1, runId = "budget")) }
        val owned = pids(pidFile)
        val result = withTimeout(8_000) { run.await() }
        assertTrue(result.killed)
        assertEquals(-1, result.exit)
        assertFalse(result.ok)
        assertContains(result.error, "budget")
        assertEquals("", result.patchCid)
        assertNotNull(cas.get(ContentId(result.transcriptCid)))
        assertReaped(source, owned)
        assertFalse(Files.exists(directory.resolve("forge/agents/budget")))
    }

    @Test fun aSuccessfulCliCannotTurnFailedGitExtractionIntoPatchEvidence() = runBlocking {
        val source = source()
        val cas = CasStore.inMemory()
        val board = ConfixBlackboard.empty()
        val result = withTimeout(10_000) { runner(source, cas, board,
            "cat > /dev/null; printf 'CLI completed\\n'; rm -rf .git; exit 0")
            .run(AgentRunRequest("local", "corrupt only the disposable clone", runId = "broken")) }
        assertEquals(0, result.exit)
        assertFalse(result.ok)
        assertContains(result.error, "git add failed")
        assertEquals("", result.patchCid)
        assertEquals(0L, result.patchBytes)
        assertEquals(emptyList(), result.files)
        assertContains(assertNotNull(cas.get(ContentId(result.transcriptCid))).decodeToString(), "CLI completed")
        assertFalse(Files.exists(directory.resolve("forge/agents/broken")))
        assertTrue(git(source, "rev-parse", "HEAD").isNotEmpty())
    }

    @Test fun aRefusedSourceLayoutCannotDeleteAnUnadmittedScratchPath() = runBlocking<Unit> {
        val source = source()
        val home = File(source, "forge")
        val scratch = File(home, "agents/refused-layout")
        val owned = Files.createDirectories(scratch.toPath()).resolve("source-owned.txt")
        val bytes = "This tracked file belongs to the source, not an agent run\n".encodeToByteArray()
        Files.write(owned, bytes)
        git(source, "add", "--", "forge/agents/refused-layout/source-owned.txt")
        git(source, "commit", "-q", "-m", "source-owned path")
        val head = git(source, "rev-parse", "HEAD")
        val status = git(source, "status", "--porcelain")
        assertContentEquals(bytes, Files.readAllBytes(owned))
        val cas = CasStore.inMemory()
        val board = ConfixBlackboard.empty()
        val backing = JvmFileOperations()
        val created = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val files = object : FileOperations by backing {
            override fun mkdirs(path: String) { created.add(path); backing.mkdirs(path) }
            override fun deleteRecursively(path: String) { deleted.add(path); backing.deleteRecursively(path) }
        }
        val nio = NioSupervisor().apply { register(files) }
        val effect = directory.resolve("must-not-start")
        var commands = 0
        val agent = JvmAgentRunner(
            listOf(AgentCliInfo("local", "/bin/sh", "local shell", true)), source, home, cas, blackboard = board,
            environment = mapOf("HOME" to directory.toString(), "LANG" to "C"),
            definitions = { info -> AgentCli(info.id, info.path, true) { _, _, _, _ ->
                commands++
                listOf("/bin/sh", "-c", "cat > /dev/null; printf 'started' > \"${'$'}1\"", "refusal-control", effect.toString())
            } },
            toolDirs = listOf("/usr/bin", "/bin"),
        )
        try {
            val result = withContext(nio) {
                assertNull(currentCoroutineContext()[FileOperations])
                assertSame(files, currentCoroutineContext()[NioSupervisor]?.service<FileOperations>())
                agent.run(AgentRunRequest("local", "Refuse this layout before scratch admission",
                    budgetSeconds = 10, retain = false, runId = "refused-layout", jobId = "refusal-task"))
            }
            assertFalse(result.ok)
            assertContains(result.error, "the forge home ${home.absolutePath} sits inside the repo ${source.absolutePath}")
            assertEquals(-1, result.exit)
            assertEquals(0, commands, "No CLI command can be constructed after the layout refusal")
            assertFalse(Files.exists(effect))
            assertTrue(created.isEmpty(), "No scratch directory was admitted: $created")
            assertEquals("", result.transcriptCid)
            assertEquals("", result.patchCid)
            assertEquals(result.receipt(), assertIs<Map<*, *>>(board.get("agent/run/refused-layout")))
            assertTrue(Files.isRegularFile(owned),
                "A pre-scratch refusal cannot delete a tracked source file; actual recursive deletions: $deleted")
            assertContentEquals(bytes, Files.readAllBytes(owned))
            assertTrue(deleted.isEmpty(), "A run that admitted no scratch owns no recursive deletion: $deleted")
            assertEquals(head, git(source, "rev-parse", "HEAD"))
            assertEquals(status, git(source, "status", "--porcelain"))
        } finally { withContext(NonCancellable) { nio.drain() } }
    }

    @Test fun anUnbornHeadRefusesBeforeTheActualAgentStarts() = runBlocking {
        val source = source(committed = false)
        val effect = directory.resolve("must-not-exist")
        val result = runner(source, CasStore.inMemory(), ConfixBlackboard.empty(), "printf 'started' > \"${'$'}2\"", listOf(effect.toString()))
            .run(AgentRunRequest("local", "HEAD is required", runId = "unborn"))
        assertFalse(result.ok)
        assertContains(result.error, "git rev-parse failed")
        assertFalse(Files.exists(effect))
        assertFalse(Files.exists(directory.resolve("forge/agents/unborn")))
    }

    @Test fun versionProbeUsesARealBoundedProcess() = runBlocking {
        val version = withTimeout(5_000) { AgentCli.version(File("/usr/bin/git"), 4_000) }
        assertTrue(version.startsWith("git version "), version)
    }

    @Test fun ordinaryDrainWaitsForARealProcessToFinishItsAdmittedEffect() = runBlocking {
        val ready = directory.resolve("ready")
        val release = directory.resolve("release")
        val effect = directory.resolve("effect")
        val code = "printf '%s\\n' \"${'$'}${'$'}\" > \"${'$'}1\"; while [ ! -e \"${'$'}2\" ]; do /bin/sleep 0.02; done; printf 'completed' > \"${'$'}3\"; printf 'completed'"
        val pipes = JvmProcessProtocolPipes.open(command = { _, _ ->
            listOf("/bin/sh", "-c", code, "process-drain", ready.toString(), release.toString(), effect.toString())
        }, environment = AgentEnvironment.build(System.getenv(), AgentEnvironment.SYSTEM_PATH), mergeError = true)
        val channel = ProcessCaptureChannel(currentCoroutineContext(), pipes, timeoutMillis = 5_000)
        val started = CompletableDeferred<Unit>()
        val draining = async(start = CoroutineStart.UNDISPATCHED) { started.complete(Unit); channel.drain() }
        started.await()
        val owned = pids(ready)
        assertFalse(draining.isCompleted)
        assertFalse(Files.exists(effect))
        Files.writeString(release, "release")
        withTimeout(5_000) { draining.await() }
        val result = channel.await()
        assertEquals(0, result.exit)
        assertFalse(result.timedOut)
        assertEquals("completed", result.text())
        assertEquals("completed", Files.readString(effect))
        assertReaped(directory.toFile(), owned)
    }

    @Test fun reusingAnExplicitRunIdCannotDeleteAnEarlierRetainedScratch() = runBlocking<Unit> {
        val source = source()
        val head = git(source, "rev-parse", "HEAD")
        val status = git(source, "status", "--porcelain")
        val cas = CasStore.inMemory()
        val board = ConfixBlackboard.empty()
        val scratch = directory.resolve("forge/agents/retained-id").toFile()
        val starts = directory.resolve("actual-starts")
        val backing = JvmFileOperations()
        val deletions = mutableListOf<String>()
        val files = object : FileOperations by backing {
            override fun deleteRecursively(path: String) {
                deletions.add(path)
                backing.deleteRecursively(path)
            }
        }
        val nio = NioSupervisor().apply { register(files) }
        var commands = 0
        val code = """
            cat > captured.txt
            [ ! -e uncommitted.txt ] || exit 91
            printf 'retained effect\n' > result.txt
            printf 'actual retained process\n'
            printf 'Retained by the first actual process\n' > "${'$'}1"
            printf 'started\n' >> "${'$'}2"
        """.trimIndent()
        val agent = JvmAgentRunner(
            listOf(AgentCliInfo("local", "/bin/sh", "local shell", true)), source,
            directory.resolve("forge").toFile(), cas, blackboard = board,
            environment = mapOf("HOME" to directory.toString(), "LANG" to "C"),
            definitions = { info -> AgentCli(info.id, info.path, true) { _, _, last, _ ->
                commands++
                listOf("/bin/sh", "-c", code, "retained-id-control", last.absolutePath, starts.toString())
            } },
            toolDirs = listOf("/usr/bin", "/bin"),
        )
        suspend fun run(brief: String, retain: Boolean) = withTimeout(15_000) {
            withContext(nio) {
                assertNull(currentCoroutineContext()[FileOperations])
                assertSame(files, currentCoroutineContext()[NioSupervisor]?.service<FileOperations>())
                agent.run(AgentRunRequest("local", brief, budgetSeconds = 10, retain = retain,
                    runId = "retained-id", jobId = "retained-task"))
            }
        }
        fun scratchBytes(): Map<String, ByteArray> = Files.walk(scratch.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().associate {
                scratch.toPath().relativize(it).toString() to Files.readAllBytes(it)
            }
        }
        try {
            val brief = "Keep the first run's actual scratch\n"
            val first = run(brief, retain = true)
            assertTrue(first.ok, first.error)
            assertEquals(0, first.exit)
            assertFalse(first.killed)
            assertTrue(first.retained)
            assertEquals(head, first.base)
            assertEquals(scratch.absolutePath, first.scratch)
            assertEquals("Retained by the first actual process", first.summary)
            assertEquals(listOf("captured.txt", "result.txt"), first.files)
            val transcript = assertNotNull(cas.get(ContentId(first.transcriptCid)))
            assertEquals(ContentId(first.transcriptCid), ContentId.of(transcript))
            assertEquals("actual retained process\n", transcript.decodeToString())
            val patch = assertNotNull(cas.get(ContentId(first.patchCid)))
            assertEquals(ContentId(first.patchCid), ContentId.of(patch))
            assertContains(patch.decodeToString(), "diff --git a/captured.txt b/captured.txt")
            assertContains(patch.decodeToString(), "+retained effect")
            assertEquals(first.receipt(), assertIs<Map<*, *>>(board.get("agent/run/retained-id")))
            assertContentEquals((brief + "\n").encodeToByteArray(), Files.readAllBytes(scratch.toPath().resolve("repo/captured.txt")))
            assertEquals("retained effect\n", Files.readString(scratch.toPath().resolve("repo/result.txt")))
            assertFalse(Files.exists(scratch.toPath().resolve("repo/uncommitted.txt")))
            assertEquals(1, commands)
            assertEquals("started\n", Files.readString(starts))
            assertTrue(deletions.isEmpty(), "The first retained run must skip deletion")
            val retainedBytes = scratchBytes()
            assertTrue(retainedBytes.keys.containsAll(listOf("brief.md", "last.md", "repo/captured.txt", "repo/result.txt", "repo/.git/HEAD")))
            assertEquals(head, git(source, "rev-parse", "HEAD"))
            assertEquals(status, git(source, "status", "--porcelain"))

            val refused = run("A second request cannot own the first run's scratch", retain = false)
            assertFalse(refused.ok, "Reusing retained scratch must refuse: ${refused.receipt()}")
            assertTrue(refused.error.isNotBlank(), "The refusal must explain the failure")
            assertEquals(1, commands, "The second request cannot construct another agent command")
            assertEquals("started\n", Files.readString(starts), "Only the first real process may start")
            assertContentEquals(transcript, assertNotNull(cas.get(ContentId(first.transcriptCid))))
            assertContentEquals(patch, assertNotNull(cas.get(ContentId(first.patchCid))))
            assertTrue(scratch.isDirectory,
                "A rejected reuse cannot delete the retained first scratch; actual deletions=$deletions; refusal=${refused.error}")
            val after = scratchBytes()
            assertEquals(retainedBytes.keys, after.keys, "Rejected reuse must preserve every retained scratch file")
            for ((path, bytes) in retainedBytes) assertContentEquals(bytes, after.getValue(path), path)
            assertTrue(deletions.isEmpty(), "The rejected request owns no recursive deletion: $deletions")
            assertEquals(head, git(source, "rev-parse", "HEAD"))
            assertEquals(status, git(source, "status", "--porcelain"))
            assertFalse(Files.exists(source.toPath().resolve("result.txt")))
            assertFalse(Files.exists(source.toPath().resolve(".git/worktrees")))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { nio.drain() } finally { backing.deleteRecursively(scratch.path) }
            }
        }
    }


    @Test fun parentSegmentsInAnExplicitRunIdCannotAdmitSourceScratch() = runBlocking<Unit> {
        scratchCannotEnterSource("../../source/escaped", linkedAgents = false)
    }

    @Test fun aLinkedAgentsParentCannotAdmitSourceScratch() = runBlocking<Unit> {
        scratchCannotEnterSource("linked-run", linkedAgents = true)
    }

    private suspend fun scratchCannotEnterSource(runId: String, linkedAgents: Boolean) {
        val source = source()
        val tracked = Files.createDirectories(source.toPath().resolve("tracked"))
        val marker = tracked.resolve("source-owned.txt")
        val bytes = "Tracked source data is not agent scratch\n".encodeToByteArray()
        Files.write(marker, bytes)
        git(source, "add", "--", "tracked/source-owned.txt")
        git(source, "commit", "-q", "-m", "tracked source owner")
        val head = git(source, "rev-parse", "HEAD")
        val status = git(source, "status", "--porcelain")
        val home = Files.createDirectories(directory.resolve("forge"))
        val agents = home.resolve("agents")
        if (linkedAgents) {
            Files.createSymbolicLink(agents, tracked)
            assertTrue(Files.isSymbolicLink(agents))
            assertEquals(tracked.toRealPath(), agents.toRealPath())
        } else Files.createDirectories(agents)
        val scratch = File(home.toFile(), "agents/$runId")
        val expected = if (linkedAgents) tracked.resolve(runId) else source.toPath().resolve("escaped")
        assertEquals(expected.toFile().canonicalPath, scratch.canonicalPath)
        assertTrue(scratch.canonicalPath == source.canonicalPath ||
            scratch.canonicalPath.startsWith(source.canonicalPath + File.separator))
        assertContentEquals(bytes, Files.readAllBytes(marker))
        assertFalse(Files.exists(source.toPath().resolve("repo")))
        assertFalse(Files.exists(expected))
        val starts = directory.resolve("actual-starts")
        val cas = CasStore.inMemory()
        val board = ConfixBlackboard.empty()
        val backing = JvmFileOperations()
        val created = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val files = object : FileOperations by backing {
            override fun mkdirs(path: String) {
                val existed = backing.exists(path)
                backing.mkdirs(path)
                if (!existed) created.add(path)
            }
            override fun deleteRecursively(path: String) { deleted.add(path); backing.deleteRecursively(path) }
        }
        val nio = NioSupervisor().apply { register(files) }
        var commands = 0
        val code = """
            cat > captured.txt
            printf 'unexpected source scratch effect\n' > result.txt
            printf 'started\n' >> "${'$'}2"
            printf 'Unexpected process entered source scratch\n' > "${'$'}1"
            printf 'actual unexpected source scratch process\n'
        """.trimIndent()
        val agent = JvmAgentRunner(
            listOf(AgentCliInfo("local", "/bin/sh", "local shell", true)), source,
            home.toFile(), cas, blackboard = board,
            environment = mapOf("HOME" to directory.toString(), "LANG" to "C"),
            definitions = { info -> AgentCli(info.id, info.path, true) { _, _, last, _ ->
                commands++
                listOf("/bin/sh", "-c", code, "source-boundary-control", last.absolutePath, starts.toString())
            } },
            toolDirs = listOf("/usr/bin", "/bin"),
        )
        try {
            val result = withTimeout(15_000) { withContext(nio) {
                assertNull(currentCoroutineContext()[FileOperations])
                assertSame(files, currentCoroutineContext()[NioSupervisor]?.service<FileOperations>())
                agent.run(AgentRunRequest("local", "Do not admit scratch inside the disposable source",
                    budgetSeconds = 10, retain = false, runId = runId, jobId = "source-boundary-task"))
            } }
            val diagnostic = "result=${result.receipt()}; actualCommands=$commands; " +
                "actualStarts=${if (Files.exists(starts)) Files.readString(starts) else "absent"}; " +
                "created=$created; deleted=$deleted; sourceExists=${source.isDirectory}"
            assertFalse(result.ok, diagnostic)
            assertTrue(result.error.isNotBlank(), diagnostic)
            assertEquals(0, commands, diagnostic)
            assertFalse(Files.exists(starts), diagnostic)
            assertTrue(created.none { path ->
                val canonical = File(path).canonicalPath
                canonical == source.canonicalPath || canonical.startsWith(source.canonicalPath + File.separator)
            }, diagnostic)
            assertTrue(deleted.isEmpty(), diagnostic)
            assertTrue(Files.isRegularFile(marker), diagnostic)
            assertContentEquals(bytes, Files.readAllBytes(marker))
            assertEquals(head, git(source, "rev-parse", "HEAD"))
            assertEquals(status, git(source, "status", "--porcelain"))
            assertFalse(Files.exists(source.toPath().resolve("repo")))
            assertFalse(Files.exists(expected))
            if (linkedAgents) {
                assertTrue(Files.isSymbolicLink(agents))
                assertEquals(tracked, Files.readSymbolicLink(agents))
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { nio.drain() }
                finally {
                    backing.deleteRecursively(home.toString())
                    backing.deleteRecursively(source.path)
                    backing.deleteRecursively(starts.toString())
                }
            }
        }
    }

}
