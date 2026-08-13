package borg.trikeshed.jules

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Operator bridge for a Jules result delivered only through the API activity
 * stream. It writes the same CAS -> tag -> WAL receipt chain as the daemon;
 * a PR and remote branch are optional identity synonyms, never prerequisites.
 */
object JulesSettlementCli {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        when (args.firstOrNull()) {
            "settle" -> settle(args.drop(1))
            "archive-record" -> recordArchive(args.drop(1))
            else -> error(
                "usage:\n" +
                    "  JulesSettlementCli settle <session-id> <commit> <state> <disposition> <title> [forge-dir] [repo-dir] [pr-url]\n" +
                    "    (reads the exact Jules cumulative patch from stdin)\n" +
                    "  JulesSettlementCli archive-record <session-id> [forge-dir]"
            )
        }
    }

    private suspend fun settle(args: List<String>) {
        require(args.size >= 5) { "settle requires session-id, commit, state, disposition, and title" }
        val sessionId = args[0].substringAfterLast('/')
        require(sessionId.isNotBlank()) { "empty session id" }
        val requestedCommit = args[1]
        val state = args[2]
        val disposition = args[3]
        val title = args[4]
        val forgeDir = File(args.getOrNull(5) ?: defaultForgeDir())
        val repoDir = File(args.getOrNull(6) ?: System.getProperty("user.dir"))
        val prUrl = args.getOrNull(7)?.takeIf { it.isNotBlank() && it != "none" }
        val patch = withContext(Dispatchers.IO) { System.`in`.readBytes() }
        require(patch.isNotEmpty()) { "stdin carried no Jules patch bytes" }

        val commit = git(repoDir, "rev-parse", "$requestedCommit^{commit}").requireSuccess().trim()
        require(git(repoDir, "merge-base", "--is-ancestor", commit, "origin/master").exitCode == 0) {
            "settlement commit $commit is not contained in origin/master"
        }

        val cas = FileCasStore(JvmFileOperations(), File(forgeDir, "cas").absolutePath)
        val patchCid = withContext(Dispatchers.IO) { cas.put(patch) }
        val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val tag = "flywheel/jules-$safeSession-${commit.take(12)}"
        ensureTag(repoDir, tag, commit, sessionId, patchCid, disposition, title)

        val store = JulesBoardStore.forForgeDir(forgeDir)
        val existingQueue = store.loadQueue().firstOrNull { it.sessionId == sessionId }
        if (existingQueue?.isDrained == true) {
            val receipt = requireNotNull(existingQueue.receipt) {
                "session $sessionId already has a hollow WorkDrained record"
            }
            require(receipt.patchCid == patchCid && receipt.revision == commit) {
                "session $sessionId already settled to ${receipt.revision}/${receipt.patchCid}"
            }
            ensureIdentity(store, existingQueue.workId, sessionId, prUrl, tag, commit)
            ensureCardDrained(store, sessionId, state, title, patch.size.toLong(), commit)
            println(receiptJson(sessionId, disposition, commit, tag, patchCid, existingQueue.workId, true))
            return
        }

        val now = System.currentTimeMillis()
        val workId = existingQueue?.workId ?: "session:$safeSession"
        if (existingQueue == null) {
            store.appendWork(workId, JulesCause.WorkQueued(
                workId = workId,
                tier = "operator",
                title = title,
                spec = "API-only Jules settlement: $disposition",
                score = 0.5,
                at = now,
            ))
            store.appendWork(workId, JulesCause.WorkDispatched(
                workId = workId,
                sessionId = sessionId,
                attempt = 1,
                at = now,
            ))
        }
        val receipt = MergeReceipt(
            workId = workId,
            producer = "jules-api",
            producerRef = sessionId,
            patchCid = patchCid,
            revision = commit,
            versionTag = tag,
            lexicalMemory = LexicalMemory(
                summary = disposition,
                title = title,
                content = "Settled from the Jules API cumulative patch stream; PR/branch optional.",
            ),
            claimedAt = now,
            prUrl = prUrl,
        )
        store.appendWork(workId, JulesCause.WorkDrained(
            workId = workId,
            sessionId = sessionId,
            commitSha = commit,
            taskId = tag,
            receipt = receipt,
            at = now,
        ))
        ensureIdentity(store, workId, sessionId, prUrl, tag, commit, now)
        ensureCardDrained(store, sessionId, state, title, patch.size.toLong(), commit)
        println(receiptJson(sessionId, disposition, commit, tag, patchCid, workId, false))
    }

    /** Call only after the Jules API archive transition succeeds. */
    private suspend fun recordArchive(args: List<String>) {
        require(args.isNotEmpty()) { "archive-record requires session-id" }
        val sessionId = args[0].substringAfterLast('/')
        val forgeDir = File(args.getOrNull(1) ?: defaultForgeDir())
        val store = JulesBoardStore.forForgeDir(forgeDir)
        val card = requireNotNull(store.load()[sessionId]) { "no WAL card for session $sessionId" }
        require(card.drained) { "session $sessionId has no durable drain receipt" }
        if (card.causes.any { it is JulesCause.SessionArchived }) {
            println("{\"sessionId\":\"$sessionId\",\"archivedRecorded\":true,\"idempotent\":true}")
            return
        }
        store.append(card.snapshot, drained = true, cause = JulesCause.SessionArchived(System.currentTimeMillis()))
        println("{\"sessionId\":\"$sessionId\",\"archivedRecorded\":true,\"idempotent\":false}")
    }

    private suspend fun ensureIdentity(
        store: JulesBoardStore,
        workId: String,
        sessionId: String,
        prUrl: String?,
        tag: String,
        commit: String,
        at: Long = System.currentTimeMillis(),
    ) {
        if (store.replayCauses(workId).any { cause ->
                cause is JulesCause.WorkIdentitySynthesized &&
                    cause.identity.sessionId == sessionId &&
                    cause.identity.gitTag == tag &&
                    cause.identity.commitSha == commit
            }
        ) return
        store.appendWork(workId, JulesCause.WorkIdentitySynthesized(
            workId = workId,
            identity = WorkIdentity(
                workId = workId,
                sessionId = sessionId,
                prUrl = prUrl,
                gitTag = tag,
                commitSha = commit,
            ),
            at = at,
        ))
    }

    private suspend fun ensureCardDrained(
        store: JulesBoardStore,
        sessionId: String,
        state: String,
        title: String,
        patchBytes: Long,
        commit: String,
    ) {
        val existing = store.load()[sessionId]
        val refreshedAt = System.currentTimeMillis()
        val refreshedSnapshot = existing?.snapshot?.copy(
            state = state,
            title = title,
            patchBytes = patchBytes,
            headSha = commit,
            capturedAt = refreshedAt,
        ) ?: JulesSnapshot(
            sessionId = sessionId,
            state = state,
            title = title,
            patchBytes = patchBytes,
            headSha = commit,
            activeCount = 0,
            awaitingCount = 0,
        )
        if (existing?.drained == true) {
            val changed = existing.snapshot.state != state ||
                existing.snapshot.title != title ||
                existing.snapshot.patchBytes != patchBytes ||
                existing.snapshot.headSha != commit
            if (changed) {
                store.append(
                    refreshedSnapshot,
                    drained = true,
                    cause = JulesCause.StateObserved(existing.snapshot.state, state, refreshedAt),
                )
            }
            return
        }
        val base = existing?.copy(snapshot = refreshedSnapshot)
            ?: JulesSessionCard.capture(refreshedSnapshot)
        store.appendDrainBatch(listOf(base.markDrained(commitSha = commit, rejects = 0)))
    }

    private suspend fun ensureTag(
        repoDir: File,
        tag: String,
        commit: String,
        sessionId: String,
        patchCid: ContentId,
        disposition: String,
        title: String,
    ) {
        val existing = git(repoDir, "rev-parse", "$tag^{commit}")
        if (existing.exitCode == 0) {
            require(existing.output.trim() == commit) { "tag $tag targets ${existing.output.trim()}, not $commit" }
            return
        }
        git(
            repoDir, "tag", "-a", tag, commit, "-m",
            "Jules settlement receipt\nsession=$sessionId\npatchCid=${patchCid.value}\ndisposition=$disposition\ntaskTitle=$title",
        ).requireSuccess()
    }

    private data class CommandResult(val exitCode: Int, val output: String) {
        fun requireSuccess(): String {
            check(exitCode == 0) { output.take(500) }
            return output
        }
    }

    private suspend fun git(repoDir: File, vararg args: String): CommandResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        CommandResult(process.waitFor(), output)
    }

    private fun receiptJson(
        sessionId: String,
        disposition: String,
        commit: String,
        tag: String,
        cid: ContentId,
        workId: String,
        idempotent: Boolean,
    ): String = "{" +
        "\"sessionId\":\"$sessionId\"," +
        "\"disposition\":\"$disposition\"," +
        "\"commit\":\"$commit\"," +
        "\"tag\":\"$tag\"," +
        "\"patchCid\":\"${cid.value}\"," +
        "\"workId\":\"$workId\"," +
        "\"idempotent\":$idempotent}"

    private fun defaultForgeDir(): String =
        System.getenv("TRIKESHED_HOME") ?: File(System.getProperty("user.home"), ".local/forge").path
}
