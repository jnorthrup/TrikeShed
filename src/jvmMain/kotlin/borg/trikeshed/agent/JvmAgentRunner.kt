package borg.trikeshed.agent

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.AgentCliInfo
import borg.trikeshed.lcnc.AgentNodes
import borg.trikeshed.lcnc.AgentRunRequest
import borg.trikeshed.lcnc.AgentRunResult
import borg.trikeshed.lcnc.AgentRuns
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import borg.trikeshed.util.oroboros.OroborosAttachmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

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
 * Every blocking call runs on the IO dispatcher; no wait is unbounded; a budget kill takes
 * the process tree down. The git subprocesses of this lane are an explicit, scoped exception
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

    override suspend fun run(request: AgentRunRequest): AgentRunResult = withContext(Dispatchers.IO) { runBlockingIo(request) }

    private fun runBlockingIo(request: AgentRunRequest): AgentRunResult {
        val runId = request.runId.ifBlank { mintRunId() }
        val started = clock()
        val info = rosterRows.firstOrNull { it.id == request.agent }
        if (info == null || !info.enabled) {
            return finish(AgentRunResult(runId = runId, jobId = request.jobId, agent = request.agent, startedAtMs = started, finishedAtMs = clock(),
                budgetSeconds = request.budgetSeconds, error = "agent '${request.agent}' is not installed/enabled here" + (info?.why?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")))
        }
        val cli = definitions(info)
        val source = if (request.repo.isNotBlank()) File(request.repo) else repoDir
        val scratchRoot = File(forgeHome, "agents/$runId")
        val repo = File(scratchRoot, "repo")
        var result = AgentRunResult(runId = runId, jobId = request.jobId, agent = info.id, cli = info.path, version = info.version, model = request.model,
            repo = source.absolutePath, scratch = scratchRoot.absolutePath, retained = request.retain, startedAtMs = started, budgetSeconds = request.budgetSeconds)
        try {
            if (forgeHome.canonicalPath.startsWith(source.canonicalPath + File.separator)) {
                return finish(result.copy(finishedAtMs = clock(), error = "the forge home ${forgeHome.absolutePath} sits inside the repo ${source.absolutePath}; a scratch clone there would be absorbed"))
            }
            if (!File(source, ".git").exists()) return finish(result.copy(finishedAtMs = clock(), error = "not a git repository: ${source.absolutePath}"))
            scratchRoot.mkdirs()
            val clone = exec(listOf("git", "clone", "--shared", "--no-tags", "-q", source.absolutePath, repo.absolutePath), scratchRoot, 120_000L, 65_536)
            if (clone.exit != 0) return finish(result.copy(finishedAtMs = clock(), error = "git clone failed (${clone.exit}): ${clone.text().take(400)}"))
            val base = exec(listOf("git", "rev-parse", "HEAD"), repo, 30_000L, 4096).text().trim()
            result = result.copy(base = base)
            val briefFile = File(scratchRoot, "brief.md").apply { writeText(request.brief) }
            val lastMessage = File(scratchRoot, "last.md")

            // The agent itself, bounded by its budget.
            val command = cli.command(repo, request.model, lastMessage, request.brief)
            val env = AgentEnvironment.build(environment, listOf(File(info.path).parent ?: "/usr/bin") + toolDirs, AgentEnvironment.GIT + extraEnvironment)
            val pb = ProcessBuilder(command).directory(repo).redirectErrorStream(true)
            pb.environment().clear(); pb.environment().putAll(env)
            val process = pb.start()
            val capture = BoundedCapture(request.maxBytes)
            val drain = Thread { runCatching { process.inputStream.use { capture.drain(it) } } }.apply { isDaemon = true; start() }
            if (cli.briefOnStdin) {
                Thread { runCatching { process.outputStream.use { it.write(request.brief.toByteArray()); it.write('\n'.code) } } }.apply { isDaemon = true; start() }
            } else runCatching { process.outputStream.close() }
            val finished = process.waitFor(request.budgetSeconds.toLong(), TimeUnit.SECONDS)
            var killed = false
            if (!finished) { killed = true; killTree(process) }
            drain.join(5_000L)
            val exit = if (finished) process.exitValue() else -1

            // What changed: the staged diff against the base.
            exec(listOf("git", "add", "-A"), repo, 60_000L, 65_536)
            val diff = exec(listOf("git", "diff", "--cached", "--binary", "--no-color", base), repo, 60_000L, 16 * 1024 * 1024)
            val patch = diff.bytes
            // `diff --git a/<path> b/<path>` — take the b-side, which is the path after the change.
            val files = patch.decodeToString().lineSequence().filter { it.startsWith("diff --git ") }
                .mapNotNull { line -> line.substringAfterLast(" b/", "").takeIf { it.isNotBlank() } }
                .distinct().toList()
            val filesChanged = files.size

            val transcript = capture.bytes() + (if (capture.dropped > 0) "\n[truncated: ${capture.dropped} bytes over the ${request.maxBytes} byte cap were dropped]\n".toByteArray() else ByteArray(0))
            val summary = lastMessage.takeIf { it.isFile && it.length() > 0 }?.readText()?.trim()?.ifEmpty { null }
                ?: transcript.decodeToString().trim().takeLast(AgentNodes.SUMMARY_CHARS)
            val transcriptCid = cas.put(transcript).value
            val patchCid = if (patch.isNotEmpty()) cas.put(patch).value else ""
            result = result.copy(finishedAtMs = clock(), exit = exit, killed = killed, bytes = capture.total, kept = capture.kept, truncated = capture.dropped > 0,
                transcriptCid = transcriptCid, patchCid = patchCid, patchBytes = patch.size.toLong(), filesChanged = filesChanged, files = files, summary = summary,
                error = if (killed) "budget of ${request.budgetSeconds}s exceeded; the process tree was killed" else "")
            attachments?.let { att ->
                fun put(name: String, type: String, bytes: ByteArray) {
                    val cid = cas.put(bytes)
                    att.putAttachment(OroborosAttachmentRef(path = "agents/$runId/$name", contentType = type, length = bytes.size.toLong(), contentId = cid, agentId = "agent-lane", revision = info.id, sequence = clock()), bytes)
                }
                put("brief.md", "text/markdown", briefFile.readBytes())
                put("transcript.md", "text/markdown", transcript)
                if (patch.isNotEmpty()) put("patch.diff", "text/x-diff", patch)
            }
            return finish(result)
        } catch (t: Throwable) {
            return finish(result.copy(finishedAtMs = clock(), error = "agent run failed: ${t::class.simpleName}: ${t.message}"))
        } finally {
            if (!request.retain) runCatching { scratchRoot.deleteRecursively() }
        }
    }

    private fun finish(result: AgentRunResult): AgentRunResult {
        blackboard?.put(AgentNodes.RECEIPT_PREFIX + result.runId, result.receipt(), AgentNodes.LANGUAGE)
        return result
    }

    private class Exec(val exit: Int, val bytes: ByteArray, val timedOut: Boolean) { fun text(): String = bytes.decodeToString() }

    /** A bounded subprocess for the git steps: whitelisted env, capped output, forced kill on timeout. */
    private fun exec(command: List<String>, cwd: File, timeoutMs: Long, maxBytes: Int): Exec {
        val pb = ProcessBuilder(command).directory(cwd).redirectErrorStream(true)
        pb.environment().clear(); pb.environment().putAll(AgentEnvironment.build(environment, toolDirs, AgentEnvironment.GIT))
        val p = pb.start()
        runCatching { p.outputStream.close() }
        val capture = BoundedCapture(maxBytes)
        val drain = Thread { runCatching { p.inputStream.use { capture.drain(it) } } }.apply { isDaemon = true; start() }
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) killTree(p)
        drain.join(5_000L)
        return Exec(if (finished) p.exitValue() else -1, capture.bytes(), !finished)
    }

    private fun killTree(p: Process) {
        runCatching { p.descendants().forEach { it.destroyForcibly() } }
        p.destroyForcibly()
        runCatching { p.waitFor(5, TimeUnit.SECONDS) }
    }

    /** Keeps the first [cap] bytes, keeps draining past it, counts what it dropped. */
    private class BoundedCapture(private val cap: Int) {
        private val buffer = ByteArrayOutputStream()
        var total: Long = 0; private set
        var kept: Long = 0; private set
        val dropped: Long get() = total - kept
        fun drain(input: java.io.InputStream) {
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk); if (n < 0) break
                total += n
                val room = (cap - kept).coerceAtLeast(0L).toInt()
                if (room > 0) { val take = minOf(room, n); buffer.write(chunk, 0, take); kept += take }
            }
        }
        fun bytes(): ByteArray = buffer.toByteArray()
    }
}
