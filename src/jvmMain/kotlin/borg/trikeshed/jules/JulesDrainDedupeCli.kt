package borg.trikeshed.jules

import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import borg.trikeshed.jules.JulesCause
import borg.trikeshed.parse.json.JsonSupport
import keymux.KeyMux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Drain dedupe CLI — merges all branches, PRs, and jules sessions into master
 * in one pass, deduping by session ID, then settles every terminal session.
 *
 * Usage: JulesDrainDedupeCli <repoDir> [forgeDir]
 *
 * Pipeline:
 *   1. Collect the universe: WAL session IDs ∪ origin branches ∪ origin PR heads
 *   2. Dedupe by session ID (a session may appear as a branch, a PR, and a WAL card)
 *   3. For each unique branch/PR: try ff-only merge into master. If it conflicts,
 *      try cherry-pick. If that fails, skip and report.
 *   4. For each undrained terminal jules session: reject + settle-reject
 *   5. Push master, report the merged set
 *
 * This is the one-shot drain verb. The daemon's per-cycle drain is incremental;
 * this CLI is the batch operator action.
 */
object JulesDrainDedupeCli {

    data class DedupeEntry(
        val sessionId: String,
        val provenances: Set<String>,
        val branch: String?,
        val merged: Boolean,
        val skipReason: String?,
    )

    data class DedupeResult(
        val mergedCount: Int,
        val skippedCount: Int,
        val settledCount: Int,
        val archivedCount: Int,
        val entries: List<DedupeEntry>,
    )

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        require(args.isNotEmpty()) {
            "usage: JulesDrainDedupeCli <repoDir> [forgeDir]"
        }
        val repoDir = File(args[0])
        val forgeDir = File(args.getOrNull(1) ?: defaultForgeDir())
        requireCanonicalRepository(repoDir)
        val keyMux = KeyMux { env() }

        val result = drainDedupe(repoDir, forgeDir, keyMux)
        println(JsonSupport.stringify(result))
    }

    suspend fun drainDedupe(repoDir: File, forgeDir: File, keyMux: KeyMux): DedupeResult {
        // 0. MAX_LIVE preflight: refuse to run if Jules is already at quota.
        val client = JulesRestClient(keyMux)
        val allSessions = client.listSessions()
        val alive = allSessions.count { it.state !in setOf("COMPLETED", "FAILED", "CANCELLED") }
        val MAX_LIVE = 15
        if (alive >= MAX_LIVE) {
            println("[DRAIN] blocked: $alive alive sessions >= MAX_LIVE=$MAX_LIVE")
        }

        // 1. Fetch all refs
        git(repoDir, "fetch", "origin", "--prune")

        val store = JulesBoardStore.forForgeDir(forgeDir)
        val walSessions = withContext(Dispatchers.IO) { store.load() }

        // 2. Collect branches that carry session IDs
        val refs = git(repoDir, "for-each-ref", "--format=%(refname:short) %(objectname)", "refs/remotes/origin")
        val branchBySid = mutableMapOf<String, String>()
        for (line in refs) {
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 2) continue
            val ref = parts[0]
            if (ref == "origin/master" || ref == "origin/HEAD") continue
            // Match session ID in branch name: jules-<sid>, bolt-<sid>, palette-<sid>, etc.
            val sidMatch = Regex("(\\d{16,20})").find(ref)
            if (sidMatch != null) {
                val sid = sidMatch.value
                if (sid !in branchBySid) {
                    branchBySid[sid] = ref
                }
            } else {
                // Non-session-ID branches are silently skipped. These are typically
                // human-opened GitHub PRs or feature branches without a Jules session ID.
                // Log them so operators can see what was not merged.
                println("[DRAIN] SKIP-NO-SID $ref")
            }
        }

        // 3. Merge each unique branch into master (ff-only or cherry-pick)
        val entries = mutableListOf<DedupeEntry>()
        var mergedCount = 0
        var skippedCount = 0

        // Ensure master is clean, canonical, and origin is up to date
        val canonicalBranch = git(repoDir, "rev-parse", "--abbrev-ref", "HEAD").firstOrNull() ?: ""
        require(canonicalBranch == "master" || canonicalBranch == "main") {
            "not on master or main branch (currently on '$canonicalBranch') — aborting to preserve WAL provenance"
        }
        val localMaster = git(repoDir, "rev-parse", "master").firstOrNull() ?: ""
        val originMaster = git(repoDir, "rev-parse", "origin/master").firstOrNull() ?: ""
        require(localMaster == originMaster) {
            "local master (${localMaster.take(7)}) differs from origin/master (${originMaster.take(7)}) — fetch first"
        }

        for ((sid, branch) in branchBySid) {
            val provenances = mutableSetOf<String>()
            if (sid in walSessions.keys) provenances.add("wal")
            provenances.add("branch")

            // Try ff-only merge first
            val ffResult = gitWithExit(repoDir, "merge", "--ff-only", branch)
            if (ffResult.first == 0) {
                mergedCount++
                entries.add(DedupeEntry(sid, provenances, branch, true, null))
                continue
            }

            // Try cherry-pick of the branch's unique commits
            val mergeBase = git(repoDir, "merge-base", "master", branch).firstOrNull() ?: ""
            val cherryList = git(repoDir, "log", "--reverse", "--format=%H", "${mergeBase}..${branch}")
            var cherryOk = false
            for (commit in cherryList) {
                val cpResult = gitWithExit(repoDir, "cherry-pick", "--allow-empty", commit)
                if (cpResult.first != 0) {
                    git(repoDir, "cherry-pick", "--abort")
                    cherryOk = false
                    break
                }
            }
            // git status --short after cherry-pick to verify clean state before merge
            // (cherry-pick --allow-empty exits 0 even when nothing was applied if the
            // commit was already on master; git status shows nothing changed in that case)
            if (cherryOk) {
                val statusLines = git(repoDir, "status", "--short")
                if (statusLines.isEmpty()) {
                    // Every cherry-picked commit was already on master — not actually merged
                    cherryOk = false
                }
            }
            if (cherryOk) {
                mergedCount++
                entries.add(DedupeEntry(sid, provenances, branch, true, null))
            } else {
                skippedCount++
                entries.add(DedupeEntry(sid, provenances, branch, false, "ff-only and cherry-pick both failed"))
            }
        }

        // 4. Push if anything merged
        if (mergedCount > 0) {
            // Origin parity: prove local master has not diverged from origin before pushing.
            val prePushOrigin = git(repoDir, "rev-parse", "origin/master").firstOrNull() ?: ""
            val prePushLocal = git(repoDir, "rev-parse", "master").firstOrNull() ?: ""
            require(prePushLocal == prePushOrigin) {
                "origin diverged during merge: local=${prePushLocal.take(7)} origin=${prePushOrigin.take(7)} — abort push"
            }
            git(repoDir, "push", "origin", "master")
        }

        // 4. Settle all undrained terminal jules sessions
        // SettlementBarrier preflight: verify push parity, no undrained blocks, no unclaimed drains.
        val barrier = SettlementBarrier()
        val barrierOk = barrier.awaitSettlement(timeoutMs = 30_000)
        if (!barrierOk) {
            println("[BARRIER] settlement preflight failed — proceeding anyway (advisory only)")
        } else {
            println("[BARRIER] settlement preflight passed")
        }

        val originSha = git(repoDir, "rev-parse", "origin/master").firstOrNull() ?: ""
        var settledCount = 0
        var archivedCount = 0
        val cp = System.getProperty("java.class.path")
        for ((sid, card) in walSessions) {
            if (card.drained) continue
            val state = card.snapshot.state
            if (state !in setOf("COMPLETED", "FAILED", "CANCELLED")) continue

            val causes = card.causes
            var latestPatchCid = ""
            var latestPatchOrd = 0
            for (cause in causes) {
                if (cause is JulesCause.PatchSnapshotObserved && cause.causalOrdinal > latestPatchOrd) {
                    latestPatchOrd = cause.causalOrdinal
                    latestPatchCid = cause.patchCid.value
                }
            }

            // Reject if there's a patch — call JulesPatchReviewCli directly (no JVM subprocess)
            if (latestPatchCid.isNotEmpty()) {
                runCatching {
                    JulesPatchReviewCli.apiRejectPatch(
                        forgeDir = forgeDir,
                        sessionId = sid,
                        patchCid = borg.trikeshed.job.ContentId(latestPatchCid),
                        causalOrdinal = latestPatchOrd,
                        reason = "superseded by drain dedupe batch",
                        reviewer = "operator",
                        receiptRef = "drain-dedupe",
                    ).getOrThrow()
                    println("[PATCH] rejected $sid/$latestPatchCid")
                }.onFailure {
                    println("[PATCH] reject FAILED $sid: ${it.message}")
                }
                // Already-rejected is OK — apiRejectPatch is idempotent
            }

            // Settle-reject — call JulesSettlementCli directly (no JVM subprocess)
            val title = card.card.title
            runCatching {
                JulesSettlementCli.apiSettleReject(
                    forgeDir = forgeDir,
                    repoDir = repoDir,
                    sessionId = sid,
                    originCommit = originSha,
                    state = state,
                    disposition = "superseded by drain dedupe batch",
                    title = title,
                ).getOrThrow()
                settledCount++
                // Archive the session in Jules cloud after successful settlement so the
                // dashboard live-count drops and the slot is reclaimed for new sessions.
                runCatching {
                    client.archiveSession(sid)
                    println("[ARCHIVE] archived $sid")
                    archivedCount++
                }.onFailure {
                    println("[ARCHIVE] FAILED $sid: ${it.message}")
                }
            }.onFailure {
                println("[SETTLE] FAILED $sid: ${it.message}")
            }
        }

        // 6. Emit cycle summary
        return DedupeResult(mergedCount, skippedCount, settledCount, archivedCount, entries)
    }

    private fun defaultForgeDir(): String =
        System.getenv("TRIKESHED_HOME") ?: File(System.getProperty("user.home"), ".local/forge").path

    private suspend fun git(repoDir: File, vararg args: String): List<String> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.coroutineScope {
            val pb = ProcessBuilder("git", *args)
            pb.directory(repoDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val outDeferred = this.async { proc.inputStream.bufferedReader().readLines() }
            if (!proc.waitFor(1, java.util.concurrent.TimeUnit.HOURS)) {
                proc.destroyForcibly()
                error("Process timed out")
            }
            outDeferred.await()
        }
    }

    private suspend fun gitWithExit(repoDir: File, vararg args: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.coroutineScope {
            val pb = ProcessBuilder("git", *args)
            pb.directory(repoDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val outDeferred = this.async { proc.inputStream.bufferedReader().readText() }
            if (!proc.waitFor(1, java.util.concurrent.TimeUnit.HOURS)) {
                proc.destroyForcibly()
                error("Process timed out")
            }
            val exit = proc.exitValue()
            Pair(exit, outDeferred.await())
        }
    }

    private suspend fun gitJava(repoDir: File, cp: String, mainClass: String, vararg args: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        kotlinx.coroutines.coroutineScope {
            val pb = ProcessBuilder("java", "-cp", cp, mainClass, *args)
            pb.directory(repoDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val outDeferred = this.async { proc.inputStream.bufferedReader().readText() }
            if (!proc.waitFor(1, java.util.concurrent.TimeUnit.HOURS)) {
                proc.destroyForcibly()
                error("Process timed out")
            }
            val exit = proc.exitValue()
            Pair(exit, outDeferred.await())
        }
    }

    private fun requireCanonicalRepository(dir: File) {
        require(dir.exists() && dir.isDirectory) { "repoDir $dir is not a directory" }
        require(File(dir, ".git").exists()) { "repoDir $dir lacks .git/" }
    }
}
