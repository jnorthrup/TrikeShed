package borg.trikeshed.agent

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.lcnc.AgentCliInfo
import borg.trikeshed.lcnc.AgentNodes
import borg.trikeshed.lcnc.AgentRunRequest
import borg.trikeshed.lcnc.AgentRunResult
import borg.trikeshed.lcnc.AgentRuns
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import borg.trikeshed.userspace.nio.channels.spi.JvmProcessProtocolPipes
import borg.trikeshed.userspace.nio.file.spi.*
import borg.trikeshed.userspace.nio.process.*
import borg.trikeshed.userspace.nio.spi.NioSupervisor
import kotlinx.coroutines.*
import borg.trikeshed.agent.modelmux.Airgap
import java.io.File

/**
 * One bounded coding-agent run on this host (Forge genesis, Cut A).
 *
 * Isolation is a shared clone of the daemon's repo at its committed HEAD under
 * `<forgeHome>/agents/<runId>/repo`: exactly HEAD, none of the shared tree's uncommitted
 * work, and nothing written into the owner's `.git` (a worktree would). The CLI runs there
 * with a whitelisted environment, its brief on stdin or as an argument, a wall-clock budget
 * under the reaper's fifteen minutes, and a bounded output capture that keeps draining past
 * the cap. Afterwards `git add -A` + `git diff --cached --binary` is the patch; the transcript
 * and the patch go to CAS and to attachment documents so they outlive a restart; the receipt
 * lands on the blackboard as `agent/run/<runId>`; the scratch is removed unless retained.
 *
 * Common userspace owns bounded byte delivery, input, exit and cleanup; a budget kill
 * revokes the process tree and joins its pipe stages. The git subprocesses are a scoped exception
 * to the daemon's no-git rule.
 */
class JvmAgentRunner(
    private val rosterRows: List<AgentCliInfo>,
    private val repoDir: File,
    private val forgeHome: File,
    private val cas: CasStore,
    private val attachments: CouchAttachmentGateway? = null,
    private val blackboard: ConfixBlackboard? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val environment: Map<String, String> = System.getenv(),
    /** Extra environment for the spawned CLI; the tests steer their stub with it. */
    private val extraEnvironment: Map<String, String> = emptyMap(),
    private val definitions: (AgentCliInfo) -> AgentCli = { AgentCli.definition(it.id, it.path) },
    private val mintRunId: () -> String = { java.util.UUID.randomUUID().toString() },
    /** Where git and its helpers (git-lfs, credential helpers) live on this host; on PATH for the git steps and the agent. */
    private val toolDirs: List<String> = AgentCli.candidateDirs().take(3),
) : AgentRuns {

    override suspend fun roster(): List<AgentCliInfo> = rosterRows

    override suspend fun run(request: AgentRunRequest): AgentRunResult = withContext(fileIoContext) {
        Airgap.requireRemote()
        val context = currentCoroutineContext()
        val files = context[FileOperations] ?: context[NioSupervisor]?.service<FileOperations>() ?: JvmFileOperations()
        run(request, files)
    }

    private suspend fun run(request: AgentRunRequest, files: FileOperations): AgentRunResult {
        val runId = request.runId.ifBlank { mintRunId() }
        val started = clock()
        val info = rosterRows.firstOrNull { it.id == request.agent }
        if (info == null || !info.enabled) {
            return finish(AgentRunResult(runId = runId, jobId = request.jobId, agent = request.agent, startedAtMs = started, finishedAtMs = clock(),
                budgetSeconds = request.budgetSeconds, error = "agent '${request.agent}' is not installed/enabled here" + (info?.why?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")))
        }
        val cli = definitions(info)
        val source = if (request.repo.isNotBlank()) File(request.repo) else repoDir
        val agentsRoot = File(forgeHome, "agents")
        val scratchRoot = File(agentsRoot, runId)
        val repo = File(scratchRoot, "repo")
        var result = AgentRunResult(runId = runId, jobId = request.jobId, agent = info.id, cli = info.path, version = info.version, model = request.model,
            repo = source.absolutePath, scratch = scratchRoot.absolutePath, retained = request.retain, startedAtMs = started, budgetSeconds = request.budgetSeconds)
        val owner = currentCoroutineContext()
        var cancellation: CancellationException? = null
        var scratchAdmitted = false
        var completed = kotlin.run execution@ {
            try {
                require(runId.isNotBlank() && runId != "." && runId != ".." && runId.none { it == '/' || it == '\\' || it == '\u0000' }) {
                    "agent run identity must be one path component"
                }
                val sourcePath = files.canonicalPath(source.path)
                val homePath = files.canonicalPath(forgeHome.path)
                if (homePath == sourcePath || homePath.startsWith(sourcePath.trimEnd(File.separatorChar) + File.separator)) {
                    return@execution result.copy(finishedAtMs = clock(), error = "the forge home ${forgeHome.absolutePath} sits inside the repo ${source.absolutePath}; a scratch clone there would be absorbed")
                }
                if (!files.exists(File(source, ".git").path)) return@execution result.copy(finishedAtMs = clock(), error = "not a git repository: ${source.absolutePath}")
                val agentsPath = files.canonicalPath(agentsRoot.path)
                if (agentsPath == sourcePath || agentsPath.startsWith(sourcePath.trimEnd(File.separatorChar) + File.separator)) {
                    return@execution result.copy(finishedAtMs = clock(), error = "the agents directory ${agentsRoot.absolutePath} sits inside the repo ${source.absolutePath}; a scratch clone there would be absorbed")
                }
                files.mkdirs(agentsRoot.path)
                files.createDirectory(scratchRoot.path)
                scratchAdmitted = true
                val clone = exec(listOf("git", "clone", "--shared", "--no-tags", "-q", source.absolutePath, repo.absolutePath), scratchRoot, 120_000L, 65_536)
                if (clone.timedOut || clone.exit != 0) return@execution result.copy(finishedAtMs = clock(), error = "git clone failed (${clone.exit}): ${clone.text().take(400)}")
                val base = git(listOf("rev-parse", "HEAD"), repo, 30_000L, 4096).text().trim()
                check(base.isNotBlank()) { "git rev-parse returned no HEAD" }
                result = result.copy(base = base)
                val briefFile = File(scratchRoot, "brief.md")
                files.write(briefFile.path, request.brief)
                val lastMessage = File(scratchRoot, "last.md")

                // The agent itself, bounded by its budget.
                val command = cli.command(repo, request.model, lastMessage, request.brief)
                val env = AgentEnvironment.build(environment, listOf(File(info.path).parent ?: "/usr/bin") + toolDirs, AgentEnvironment.GIT + extraEnvironment)
                Airgap.requireRemote()
                val capture = exec(command, repo, request.budgetSeconds.toLong() * 1000L, request.maxBytes,
                    if (cli.briefOnStdin) (request.brief + "\n").encodeToByteArray() else null, env)
                val transcript = capture.bytes + (if (capture.dropped > 0)
                    "\n[truncated: ${capture.dropped} bytes over the ${request.maxBytes} byte cap were dropped]\n".encodeToByteArray()
                    else byteArrayOf())
                val summary = if (files.isFile(lastMessage.path)) files.readString(lastMessage.path).trim().ifEmpty { null } else null
                val transcriptCid = cas.put(transcript).value
                result = result.copy(finishedAtMs = clock(), exit = capture.exit, killed = capture.timedOut,
                    bytes = capture.total, kept = capture.kept, truncated = capture.dropped > 0,
                    transcriptCid = transcriptCid, summary = summary ?: transcript.decodeToString().trim().takeLast(AgentNodes.SUMMARY_CHARS),
                    error = if (capture.timedOut) "budget of ${request.budgetSeconds}s exceeded; the process tree was killed" else "")

                // What changed: the staged diff against the base.
                git(listOf("add", "-A"), repo, 60_000L, 65_536)
                val diff = git(listOf("diff", "--cached", "--binary", "--no-color", base), repo, 60_000L, 16 * 1024 * 1024)
                check(diff.dropped == 0L) { "git diff exceeded the patch capture limit" }
                val patch = diff.bytes
                // `diff --git a/<path> b/<path>` — take the b-side, which is the path after the change.
                val changed = patch.decodeToString().lineSequence().filter { it.startsWith("diff --git ") }
                    .mapNotNull { line -> line.substringAfterLast(" b/", "").takeIf { it.isNotBlank() } }
                    // Bolt: replace distinct().toList() with toCollection(LinkedHashSet()) to avoid intermediate allocation
                    .toCollection(LinkedHashSet()).toList()
                val filesChanged = changed.size

                result = result.copy(patchCid = if (patch.isNotEmpty()) cas.put(patch).value else "",
                    patchBytes = patch.size.toLong(), filesChanged = filesChanged, files = changed)
                attachments?.let { att ->
                    fun put(name: String, type: String, bytes: ByteArray) {
                        val cid = cas.put(bytes)
                        att.putAttachment(OroborosAttachmentRef(path = "agents/$runId/$name", contentType = type, length = bytes.size.toLong(), contentId = cid, agentId = "agent-lane", revision = info.id, sequence = clock()), bytes)
                    }
                    put("brief.md", "text/markdown", files.readAllBytes(briefFile.path))
                    put("transcript.md", "text/markdown", transcript)
                    if (patch.isNotEmpty()) put("patch.diff", "text/x-diff", patch)
                }
                result.copy(finishedAtMs = clock())
            } catch (cancelled: CancellationException) {
                cancellation = cancelled
                result.copy(finishedAtMs = clock(), error = "agent run cancelled: ${cancelled.message.orEmpty()}")
            } catch (t: Throwable) {
                result.copy(finishedAtMs = clock(), error = "agent run failed: ${t::class.simpleName}: ${t.message}")
            }
        }
        // Publication follows required cleanup of admitted scratch, outside the execution catch paths.
        return withContext(NonCancellable + fileIoContext) {
            var cleanupFailure: Throwable? = null
            if (scratchAdmitted && !request.retain) try { files.deleteRecursively(scratchRoot.path) }
            catch (failure: Throwable) { cleanupFailure = failure }
            if (cancellation == null) try { owner.ensureActive() }
            catch (cancelled: CancellationException) {
                cancellation = cancelled
                val error = "agent run cancelled: ${cancelled.message.orEmpty()}"
                completed = completed.copy(error = if (completed.error.isEmpty()) error else "${completed.error}; $error")
            }
            cleanupFailure?.let { failure ->
                cancellation?.let { if (failure !== it) it.addSuppressed(failure) }
                val error = "scratch cleanup failed: ${failure::class.simpleName}: ${failure.message}"
                completed = completed.copy(error = if (completed.error.isEmpty()) error else "${completed.error}; $error")
            }
            completed = completed.copy(finishedAtMs = clock())
            val cancelled = cancellation
            if (cancelled != null) {
                try { finish(completed) }
                catch (failure: Throwable) { if (failure !== cancelled) cancelled.addSuppressed(failure) }
                throw cancelled
            }
            finish(completed)
        }
    }

    private fun finish(result: AgentRunResult): AgentRunResult {
        blackboard?.put(AgentNodes.RECEIPT_PREFIX + result.runId, result.receipt(), AgentNodes.LANGUAGE)
        return result
    }

    /** Every git helper must complete before its bytes can describe a repository or patch. */
    private suspend fun git(args: List<String>, cwd: File, timeoutMs: Long, maxBytes: Int): ProcessCapture {
        val result = exec(listOf("git") + args, cwd, timeoutMs, maxBytes)
        check(!result.timedOut && result.exit == 0) {
            "git ${args.first()} failed (${if (result.timedOut) "timeout" else result.exit}): ${result.text().take(400)}"
        }
        return result
    }

    private suspend fun exec(command: List<String>, cwd: File, timeoutMs: Long, maxBytes: Int,
                             stdin: ByteArray? = null,
                             env: Map<String, String> = AgentEnvironment.build(environment, toolDirs, AgentEnvironment.GIT)): ProcessCapture {
        require(command.isNotEmpty() && maxBytes >= 0 && timeoutMs > 0)
        val pipes = JvmProcessProtocolPipes.open(command = { _, _ -> command }, environment = env,
            workingDirectory = cwd.absolutePath, mergeError = true)
        val channel = try { ProcessCaptureChannel(currentCoroutineContext(), pipes, stdin, maxBytes, timeoutMs) }
        catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { pipes.close() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            }
            throw failure
        }
        return channel.await()
    }
}
