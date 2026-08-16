package borg.trikeshed.jules

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.util.oroboros.LexicalMemory
import borg.trikeshed.util.oroboros.MergeReceipt
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/**
 * Operator bridge for a Jules result delivered only through the API activity
 * stream. It writes the same CAS -> tag -> WAL receipt chain as the daemon;
 * a PR and remote branch are optional identity synonyms, never prerequisites.
 */
object JulesSettlementCli {
    private enum class ArtifactKind(val wireName: String) {
        PATCH("patch"),
        REPORT("report"),
        REJECT("reject"),
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        when (args.firstOrNull()) {
            "settle" -> settle(args.drop(1), ArtifactKind.PATCH)
            "settle-report" -> settle(args.drop(1), ArtifactKind.REPORT)
            "settle-reject" -> settleReject(args.drop(1))
            "archive-record" -> recordArchive(args.drop(1))
            else -> error(
                "usage:\n" +
                    "  JulesSettlementCli settle <session-id> <commit> <state> <disposition> <title> [forge-dir] [repo-dir] [pr-url]\n" +
                    "    (reads the exact Jules cumulative patch from stdin)\n" +
                    "  JulesSettlementCli settle-report <session-id> <commit> <state> <disposition> <title> [forge-dir] [repo-dir] [pr-url]\n" +
                    "    (reads the exact final Jules agent report from stdin)\n" +
                    "  JulesSettlementCli settle-reject <session-id> <commit> <state> <disposition> <title> [forge-dir] [repo-dir] [pr-url]\n" +
                    "    (retires a terminal session via the reviewed PatchRejected cause; no stdin bytes)\n" +
                    "  JulesSettlementCli archive-record <session-id> [forge-dir]"
            )
        }
    }

    /**
     * Reject settlement: retire a terminal session through the reviewed
     * [borg.trikeshed.jules.JulesCause.PatchRejected] cause.  No patch is
     * applied and no stdin bytes are read; the durable reject reason and the
     * observed snapshot's CAS bytes are the evidence, so the receipt pins the
     * rejected patch CID and the reject settlement commit (verified
     * origin/master) without laundering the patch into the tree.
     */
    private suspend fun settleReject(args: List<String>) {
        require(args.size >= 5) { "settle-reject requires session-id, commit, state, disposition, and title" }
        val sessionId = args[0].substringAfterLast('/')
        require(sessionId.isNotBlank()) { "empty session id" }
        val requestedCommit = args[1]
        val state = args[2]
        val disposition = args[3]
        val title = args[4]
        val forgeDir = File(args.getOrNull(5) ?: defaultForgeDir())
        val repoDir = File(args.getOrNull(6) ?: System.getProperty("user.dir"))
        val prUrl = args.getOrNull(7)?.takeIf { it.isNotBlank() && it != "none" }

        requireCanonicalRepository(repoDir)
        val originMaster = fetchAndVerifyOriginMaster(repoDir)
        val commit = git(repoDir, "rev-parse", "$requestedCommit^{commit}").requireSuccess().trim()
        require(commit == originMaster) {
            "reject settlement must anchor verified origin/master $originMaster, not $commit"
        }

        val cas = FileCasStore(JvmFileOperations(), File(forgeDir, "cas").absolutePath)
        val store = JulesBoardStore.forForgeDir(forgeDir)
        val durable = withContext(Dispatchers.IO) { store.load() to store.loadQueue() }
        val card = requireNotNull(durable.first[sessionId]) {
            "session $sessionId has no observed API timeline; poll Jules before settlement"
        }
        require(card.snapshot.state in PATCH_TERMINAL_STATES) {
            "session $sessionId is ${card.snapshot.state}, not terminal for reject settlement"
        }
        require(state == card.snapshot.state) {
            "requested state $state differs from observed ${card.snapshot.state}"
        }
        require(title == card.snapshot.title) {
            "requested title differs from the observed Jules title"
        }
        val rejected = requireNotNull(
            selectJulesPatchForDrain(card.causes) as? JulesPatchDrainSelection.Rejected
        ) {
            "session $sessionId has no reviewed reject cause; run JulesPatchReviewCli reject first"
        }
        val artifactCid = rejected.rejectedSnapshot.patchCid
        val existingQueue = durable.second.firstOrNull { it.sessionId == sessionId }
        val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val tag = "flywheel/jules-$safeSession-${commit.take(12)}"
        val artifact = withContext(Dispatchers.IO) {
            requireNotNull(cas.get(artifactCid)) { "missing rejected patch CAS object $artifactCid" }
        }

        if (existingQueue?.isDrained == true) {
            val receipt = requireNotNull(existingQueue.receipt) {
                "session $sessionId already has a hollow WorkDrained record"
            }
            require(receipt.patchCid == artifactCid && receipt.revision == commit) {
                "session $sessionId already settled to ${receipt.revision}/${receipt.patchCid}"
            }
            println(receiptJson(sessionId, disposition, commit, tag, artifactCid, ArtifactKind.REJECT, existingQueue.workId, true))
            return
        }

        ensureTag(repoDir, tag, commit, sessionId, artifactCid, ArtifactKind.REJECT, disposition, title)
        ensureTagPublished(repoDir, tag, commit)

        val now = System.currentTimeMillis()
        val workId = existingQueue?.workId ?: "session:$safeSession"
        if (existingQueue == null) {
            store.appendWork(workId, JulesCause.WorkQueued(
                workId = workId,
                tier = "operator",
                title = title,
                spec = "API-only Jules reject settlement: $disposition",
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
        ensureIdentity(store, workId, sessionId, prUrl, tag, commit, now)
        val receipt = MergeReceipt(
            workId = workId,
            producer = "jules-api",
            producerRef = sessionId,
            patchCid = artifactCid,
            revision = commit,
            versionTag = tag,
            lexicalMemory = LexicalMemory(
                summary = disposition,
                title = title,
                content = "Settled by typed reject; observed patch CAS bytes retained, not applied.",
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
        ensureCardDrained(store, sessionId, state, title, 0L, commit)
        println(receiptJson(sessionId, disposition, commit, tag, artifactCid, ArtifactKind.REJECT, workId, false))
    }

    private suspend fun settle(args: List<String>, artifactKind: ArtifactKind) {
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
        val artifact = withContext(Dispatchers.IO) { System.`in`.readBytes() }
        require(artifact.isNotEmpty()) { "stdin carried no Jules ${artifactKind.wireName} bytes" }
        val artifactCid = ContentId.of(artifact)

        requireCanonicalRepository(repoDir)
        val originMaster = fetchAndVerifyOriginMaster(repoDir)

        val commit = git(repoDir, "rev-parse", "$requestedCommit^{commit}").requireSuccess().trim()
        when (artifactKind) {
            ArtifactKind.PATCH -> require(
                git(repoDir, "merge-base", "--is-ancestor", commit, originMaster).exitCode == 0,
            ) {
                "settlement commit $commit is not contained in verified origin/master $originMaster"
            }
            ArtifactKind.REPORT -> require(commit == originMaster) {
                "report settlement must anchor verified origin/master $originMaster, not $commit"
            }
            ArtifactKind.REJECT -> require(commit == originMaster) {
                "reject settlement must anchor verified origin/master $originMaster, not $commit"
            }
        }

        val cas = FileCasStore(JvmFileOperations(), File(forgeDir, "cas").absolutePath)
        val store = JulesBoardStore.forForgeDir(forgeDir)
        val durable = withContext(Dispatchers.IO) { store.load() to store.loadQueue() }
        val card = requireNotNull(durable.first[sessionId]) {
            "session $sessionId has no observed API timeline; poll Jules before settlement"
        }
        val allowedStates = when (artifactKind) {
            ArtifactKind.PATCH -> PATCH_TERMINAL_STATES
            ArtifactKind.REPORT -> REPORT_TERMINAL_STATES
            ArtifactKind.REJECT -> PATCH_TERMINAL_STATES
        }
        require(card.snapshot.state in allowedStates) {
            "session $sessionId is ${card.snapshot.state}, not terminal for ${artifactKind.wireName} settlement"
        }
        require(state == card.snapshot.state) {
            "requested state $state differs from observed ${card.snapshot.state}"
        }
        require(title == card.snapshot.title) {
            "requested title differs from the observed Jules title"
        }
        val existingQueue = durable.second.firstOrNull { it.sessionId == sessionId }
        val safeSession = sessionId.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val tag = "flywheel/jules-$safeSession-${commit.take(12)}"
        validateSelectedArtifact(
            sessionId = sessionId,
            state = state,
            disposition = disposition,
            artifactKind = artifactKind,
            artifactCid = artifactCid,
            artifact = artifact,
            commit = commit,
            repoDir = repoDir,
            cas = cas,
            store = store,
            card = card,
        )
        if (existingQueue?.isDrained == true) {
            val receipt = requireNotNull(existingQueue.receipt) {
                "session $sessionId already has a hollow WorkDrained record"
            }
            require(receipt.patchCid == artifactCid && receipt.revision == commit) {
                "session $sessionId already settled to ${receipt.revision}/${receipt.patchCid}"
            }
            val settledBytes = withContext(Dispatchers.IO) { cas.get(artifactCid) }
            require(settledBytes?.contentEquals(artifact) == true) {
                "settled CAS bytes are missing or differ for $artifactCid"
            }
            ensureTag(repoDir, tag, commit, sessionId, artifactCid, artifactKind, disposition, title)
            ensureTagPublished(repoDir, tag, commit)
            ensureIdentity(
                store = store,
                workId = existingQueue.workId,
                sessionId = sessionId,
                prUrl = receipt.prUrl,
                tag = tag,
                commit = commit,
            )
            ensureCardDrained(
                store,
                sessionId,
                state,
                title,
                if (artifactKind == ArtifactKind.PATCH) artifact.size.toLong() else 0L,
                commit,
            )
            println(receiptJson(sessionId, disposition, commit, tag, artifactCid, artifactKind, existingQueue.workId, true))
            return
        }

        ensureTag(repoDir, tag, commit, sessionId, artifactCid, artifactKind, disposition, title)
        ensureTagPublished(repoDir, tag, commit)

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
        // Identity is a premise of settlement, not a synonym repaired after
        // the fact.  Append it before WorkDrained so replay never observes a
        // terminal receipt whose session/tag/commit identity is still absent.
        ensureIdentity(store, workId, sessionId, prUrl, tag, commit, now)
        val receipt = MergeReceipt(
            workId = workId,
            producer = "jules-api",
            producerRef = sessionId,
            patchCid = artifactCid,
            revision = commit,
            versionTag = tag,
            lexicalMemory = LexicalMemory(
                summary = disposition,
                title = title,
                content = when (artifactKind) {
                    ArtifactKind.PATCH -> "Settled from the Jules API cumulative patch stream; PR/branch optional."
                    ArtifactKind.REPORT -> "Settled from the exact final Jules API agent report; no patch, PR, or branch required."
                    ArtifactKind.REJECT -> "Settled by typed reject; observed patch CAS bytes retained, not applied."
                },
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
        ensureCardDrained(
            store,
            sessionId,
            state,
            title,
            if (artifactKind == ArtifactKind.PATCH) artifact.size.toLong() else 0L,
            commit,
        )
        println(receiptJson(sessionId, disposition, commit, tag, artifactCid, artifactKind, workId, false))
    }

    /**
     * Validate the exact durable activity artifact before any tag or terminal
     * WAL write.  A failed/cancelled patch is evidence from an unsuccessful
     * run, so only an explicit operator review can select it for settlement.
     */
    private suspend fun validateSelectedArtifact(
        sessionId: String,
        state: String,
        disposition: String,
        artifactKind: ArtifactKind,
        artifactCid: ContentId,
        artifact: ByteArray,
        commit: String,
        repoDir: File,
        cas: FileCasStore,
        store: JulesBoardStore,
        card: JulesSessionCard,
    ) {
        val continuity = JulesPatchContinuityStore(cas, store)
        when (artifactKind) {
            ArtifactKind.PATCH -> {
                val selected = when (val selection = selectJulesPatchForDrain(card.causes)) {
                    is JulesPatchDrainSelection.Selected -> selection
                    is JulesPatchDrainSelection.ReviewRequired -> error(
                        "session $sessionId patch regressed and needs explicit review; " +
                            "missing=${selection.missingFiles.joinToString(",")}",
                    )
                    is JulesPatchDrainSelection.Rejected -> error(
                        "session $sessionId chain is rejected; use settle-reject",
                    )
                    JulesPatchDrainSelection.Unobserved -> error(
                        "session $sessionId has no observed API patch snapshot",
                    )
                }
                if (state == "FAILED" || state == "CANCELLED") {
                    require(selected.reviewed && !selected.receiptRef.isNullOrBlank()) {
                        "$state patch settlement requires an explicit reviewed snapshot and receipt"
                    }
                }
                require(selected.snapshot.patchCid == artifactCid) {
                    "stdin patch $artifactCid is not selected ${selected.snapshot.patchCid}"
                }
                require(continuity.bytes(selected).contentEquals(artifact)) {
                    "stdin patch bytes differ from selected CAS object $artifactCid"
                }
                provePatchEmbodied(repoDir, commit, artifact)
            }
            ArtifactKind.REPORT -> {
                val selected = when (val selection = selectJulesReportForSettlement(card.causes)) {
                    is JulesReportSettlementSelection.Selected -> selection
                    is JulesReportSettlementSelection.ReviewRequired -> error(
                        "session $sessionId report ${selection.finalReport.reportCid} needs explicit semantic review",
                    )
                    JulesReportSettlementSelection.Unobserved -> error(
                        "session $sessionId has no observed full Jules agent report",
                    )
                }
                require(selected.disposition == disposition) {
                    "settlement disposition '$disposition' differs from reviewed '${selected.disposition}'"
                }
                require(selected.report.reportCid == artifactCid) {
                    "stdin report $artifactCid is not selected ${selected.report.reportCid}"
                }
                require(continuity.reportBytes(selected).contentEquals(artifact)) {
                    "stdin report bytes differ from selected CAS object $artifactCid"
                }
            }
            ArtifactKind.REJECT -> {
                val rejected = requireNotNull(
                    selectJulesPatchForDrain(card.causes) as? JulesPatchDrainSelection.Rejected,
                ) {
                    "session $sessionId has no reviewed reject cause; run JulesPatchReviewCli reject first"
                }
                require(rejected.rejectedSnapshot.patchCid == artifactCid) {
                    "rejected chain head $artifactCid is not the reviewed ${rejected.rejectedSnapshot.patchCid}"
                }
            }
        }
    }

    /**
     * Prove that [commit] contains the exact selected patch.  The reverse check
     * runs against a disposable detached worktree so neither local master nor
     * any user worktree/index participates in the proof.  `--check` performs no
     * mutation; the worktree and exact temporary patch file are removed even
     * when the proof fails.
     */
    private suspend fun provePatchEmbodied(repoDir: File, commit: String, patch: ByteArray) {
        val tempRoot = withContext(Dispatchers.IO) {
            Files.createTempDirectory("oroboros-settle-patch-").toFile()
        }
        val checkout = File(tempRoot, "detached")
        val patchFile = File(tempRoot, "selected.patch")
        var worktreeAdded = false
        try {
            withContext(Dispatchers.IO) { patchFile.writeBytes(patch) }
            git(
                repoDir,
                "worktree", "add", "--detach", checkout.absolutePath, commit,
            ).requireSuccess()
            worktreeAdded = true
            val proof = git(
                checkout,
                "apply", "--reverse", "--check", "--whitespace=nowarn", patchFile.absolutePath,
            )
            require(proof.exitCode == 0) {
                "selected Jules patch is not embodied by commit $commit: ${proof.output.take(500)}"
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (worktreeAdded) {
                    git(
                        repoDir,
                        "worktree", "remove", "--force", checkout.absolutePath,
                    ).requireSuccess()
                }
                if (tempRoot.exists()) {
                    require(tempRoot.deleteRecursively() && !tempRoot.exists()) {
                        "failed to remove disposable settlement worktree root ${tempRoot.absolutePath}"
                    }
                }
            }
        }
    }

    /** Fetch origin/master and confirm the server still advertises that SHA. */
    private suspend fun fetchAndVerifyOriginMaster(repoDir: File): String {
        git(
            repoDir,
            "fetch", "--no-tags", "origin",
            "+refs/heads/master:refs/remotes/origin/master",
        ).requireSuccess()
        val fetched = git(
            repoDir,
            "rev-parse", "refs/remotes/origin/master^{commit}",
        ).requireSuccess().trim()
        val advertised = git(
            repoDir,
            "ls-remote", "--heads", "origin", "refs/heads/master",
        ).requireSuccess().lineSequence()
            .firstOrNull { it.substringAfter('\t', "") == "refs/heads/master" }
            ?.substringBefore('\t')
            ?: error("origin does not advertise refs/heads/master")
        require(advertised == fetched) {
            "origin/master moved during verification: fetched=$fetched advertised=$advertised"
        }
        return fetched
    }

    /** Call only after the Jules API archive transition succeeds. */
    private suspend fun recordArchive(args: List<String>) {
        require(args.isNotEmpty()) { "archive-record requires session-id" }
        val sessionId = args[0].substringAfterLast('/')
        val forgeDir = File(args.getOrNull(1) ?: defaultForgeDir())
        val store = JulesBoardStore.forForgeDir(forgeDir)
        val durable = withContext(Dispatchers.IO) { store.load() to store.loadQueue() }
        val card = requireNotNull(durable.first[sessionId]) { "no WAL card for session $sessionId" }
        require(card.drained) { "session $sessionId has no durable drain receipt" }
        require(card.causes.any { it is JulesCause.DrainApplied }) {
            "session $sessionId lacks a DrainApplied card cause"
        }
        require(durable.second.any { entry ->
            entry.sessionId == sessionId && entry.receipt?.let(::isImmutableReceipt) == true
        }) { "session $sessionId lacks an immutable WorkDrained receipt" }
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
        if (withContext(Dispatchers.IO) { store.replayCauses(workId) }.any { cause ->
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
        val existing = withContext(Dispatchers.IO) { store.load()[sessionId] }
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
        artifactCid: ContentId,
        artifactKind: ArtifactKind,
        disposition: String,
        title: String,
    ) {
        val existing = git(repoDir, "rev-parse", "$tag^{commit}")
        if (existing.exitCode == 0) {
            require(existing.output.trim() == commit) { "tag $tag targets ${existing.output.trim()}, not $commit" }
            val message = git(repoDir, "for-each-ref", "--format=%(contents)", "refs/tags/$tag").requireSuccess()
            require("session=$sessionId" in message && "artifactKind=${artifactKind.wireName}" in message &&
                "artifactCid=${artifactCid.value}" in message
            ) { "tag $tag annotation does not match this exact Jules artifact" }
            return
        }
        git(
            repoDir, "tag", "-a", tag, commit, "-m",
            "Jules settlement receipt\nsession=$sessionId\nartifactKind=${artifactKind.wireName}\nartifactCid=${artifactCid.value}\ndisposition=$disposition\ntaskTitle=$title",
        ).requireSuccess()
    }

    /** Publish and verify the exact annotated receipt tag before terminal WAL. */
    private suspend fun ensureTagPublished(repoDir: File, tag: String, commit: String) {
        git(repoDir, "push", "origin", "refs/tags/$tag:refs/tags/$tag").requireSuccess()
        val remote = git(repoDir, "ls-remote", "--tags", "origin", "refs/tags/$tag^{}").requireSuccess()
        require(remote.lineSequence().any { it.substringBefore('\t') == commit }) {
            "origin does not expose annotated tag $tag peeled to $commit"
        }
    }

    private suspend fun requireCanonicalRepository(repoDir: File) {
        val branch = git(repoDir, "symbolic-ref", "--short", "HEAD").requireSuccess().trim()
        require(branch == "master") { "settlement requires local master, found $branch" }
        val remote = git(repoDir, "config", "--get", "remote.origin.url").requireSuccess().trim()
        val cleaned = remote.removeSuffix(".git").removePrefix("git@github.com:")
        val normalized = (if ("github.com/" in cleaned) cleaned.substringAfter("github.com/") else cleaned)
            .trim('/')
        require(normalized == "jnorthrup/TrikeShed") {
            "origin $normalized does not match Jules source jnorthrup/TrikeShed"
        }
    }

    private fun isImmutableReceipt(receipt: MergeReceipt): Boolean =
        receipt.producer != "retired" &&
            receipt.revision.isNotBlank() && !receipt.revision.startsWith("outbox-") &&
            receipt.versionTag.isNotBlank() && receipt.versionTag != "retired"

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
        artifactKind: ArtifactKind,
        workId: String,
        idempotent: Boolean,
    ): String = "{" +
        "\"sessionId\":\"$sessionId\"," +
        "\"disposition\":\"$disposition\"," +
        "\"commit\":\"$commit\"," +
        "\"tag\":\"$tag\"," +
        "\"artifactKind\":\"${artifactKind.wireName}\"," +
        "\"artifactCid\":\"${cid.value}\"," +
        "\"patchCid\":\"${cid.value}\"," +
        "\"workId\":\"$workId\"," +
        "\"idempotent\":$idempotent}"

    private fun defaultForgeDir(): String =
        System.getenv("TRIKESHED_HOME") ?: File(System.getProperty("user.home"), ".local/forge").path

    private val PATCH_TERMINAL_STATES = setOf("COMPLETED", "FINISHED", "FAILED", "CANCELLED")
    private val REPORT_TERMINAL_STATES = PATCH_TERMINAL_STATES
}
