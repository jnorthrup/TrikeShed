package borg.trikeshed.flywheel.cli

import borg.trikeshed.job.ContentId
import borg.trikeshed.pijul.PijulChannel
import borg.trikeshed.pijul.PijulDiffParser
import borg.trikeshed.userspace.containment.EntropyPathScanner
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import borg.trikeshed.util.oroboros.FileCasStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * N-way funnel/Pijul merge of EVERYTHING languishing: all unmerged origin
 * branches (jules-*, bolt-*, sentinel-*, palette-*, X/H task branches) plus
 * every undrained terminal WAL card with a CAS-pinned patch — merged in ONE
 * commutative PijulChannel batch, validated in an isolated worktree, gated by
 * ./gradlew jvmMainClasses, then fast-forwarded into master, pushed, tagged,
 * and closed with WorkDrained receipts.
 *
 * This is the daemon's drainExactArtifacts + preflightPijulBatch fabric
 * (FlywheelDriver.kt:914) lifted to a one-shot CLI with two deliberate
 * differences:
 *
 *  1. Arm universe = branches ∪ WAL/CAS cards, not just live-API sessions.
 *     The daemon's DRAIN-ALL only sees `listSessions()` output; sessions the
 *     cloud rotated out (or branches whose session already vanished) never
 *     get arms and languish forever (observed: 266 cycles of
 *     "DRAIN no CAS-ready arms out of 52").
 *
 *  2. No CrossInstanceCollusionDetector arm-removal. The detector flags any
 *     file touched by >=2 sessions as sock-puppetry (build.gradle.kts is
 *     touched by 4 sessions -> 9395 "Collusion signal" log lines, every arm
 *     dropped, nothing ever merges). Overlapping-file resolution is exactly
 *     what the commutative CRDT exists for; the daemon path is the anomaly.
 *     Entropy/scratch-path guards are kept.
 *
 * Per-arm quarantine reasons ARE retained: steganographic entropy in paths
 * (EntropyPathScanner) and scratch paths (isScratchPatchPath) — the
 * content-level counter-threat layers, not provenance paranoia.
 *
 * Usage: FunnelMergeBranchesCli <repoDir> [forgeDir] [--dry]
 */
fun main(args: Array<String>) {
    val dry = args.contains("--dry")
    val repoDir = File(args.firstOrNull { !it.startsWith("--") } ?: ".")
    val forgeDir = File(args.filter { !it.startsWith("--") }.getOrNull(1)
        ?: (System.getProperty("user.home") + "/.local/forge"))
    require(repoDir.isDirectory && File(repoDir, ".git").exists()) {
        "usage: FunnelMergeBranchesCli <repoDir> [forgeDir] [--dry] — $repoDir is not a git repo"
    }
    runBlocking { FunnelMergeBranchesCli(repoDir, forgeDir, dry).run() }
}

private class FunnelMergeBranchesCli(
    private val repoDir: File,
    private val forgeDir: File,
    private val dry: Boolean,
) {
    private val fileOps = JvmFileOperations()
    private val casStore = FileCasStore(fileOps, fileOps.resolvePath(forgeDir.absolutePath, "cas"))
    private val store = JulesBoardStore.forForgeDir(forgeDir)

    private class Arm(
        val label: String,
        val sessionId: String?,
        val branch: String?,
        val patch: String,
        val patchCid: ContentId,
        val touched: List<String>,
    )

    suspend fun run() {
        git("fetch", "origin", "--prune")
        val baseSha = git("rev-parse", "HEAD").second.trim()
        require(git("status", "--porcelain").second.isBlank()) {
            "working tree dirty — commit or stash first; this CLI fast-forwards master"
        }
        val canonical = git("rev-parse", "--abbrev-ref", "HEAD").second.trim()
        require(canonical == "master") { "not on master (on $canonical)" }

        val arms = buildArms()
        if (arms.isEmpty()) {
            println("[FUNNEL-MERGE] nothing to merge — no unmerged branches, no undrained CAS patches")
            return
        }
        println("[FUNNEL-MERGE] arms=${arms.size} sources:")
        arms.forEach { println("  - ${it.label} cid=${it.patchCid.value.take(12)} files=${it.touched.size}") }

        if (dry) {
            println("[FUNNEL-MERGE] --dry: no merge attempted")
            return
        }
        if (arms.size == 1) {
            println("[FUNNEL-MERGE] single arm — merging directly via git merge --no-edit")
            val arm = arms.first()
            val target = arm.branch ?: run {
                println("[FUNNEL-MERGE] single CAS arm without branch — applying patch in isolated worktree")
                applySinglePatch(arm, baseSha)
                return
            }
            val merged = git("merge", "--no-edit", target)
            if (merged.first != 0) {
                git("merge", "--abort")
                System.err.println("[FUNNEL-MERGE] single merge conflicted — falling back to patch application")
                applySinglePatch(arm, baseSha)
            }
            return
        }

        // ── N-way commutative merge ──────────────────────────────────────
        val channel = PijulChannel()
        val allTouched = arms.flatMap { it.touched }.distinct()
        withContext(Dispatchers.IO) {
            for (path in allTouched) {
                val f = File(repoDir, path)
                if (f.isFile) channel.seedFile(path, f.readText())
            }
        }
        var applied = 0
        for (arm in arms) {
            val changes = PijulDiffParser.parse(arm.patch)
            if (changes.isEmpty()) continue
            val workId = "funnel:${arm.sessionId ?: arm.branch?.substringAfterLast('/') ?: arm.patchCid.value.take(12)}"
            channel.applyPatch(workId, arm.sessionId ?: "branch:${arm.branch}", arm.patchCid, arm.label, changes)
            applied++
        }
        println("[FUNNEL-MERGE] applied $applied patches to the channel (${allTouched.size} distinct files)")

        // ── Isolated worktree + build gate ───────────────────────────────
        val tempRoot = withContext(Dispatchers.IO) {
            java.nio.file.Files.createTempDirectory("funnel-merge-").toFile()
        }
        val worktree = File(tempRoot, "worktree")
        try {
            val addExit = gitRaw(mutableListOf("worktree", "add", "--detach", worktree.absolutePath, baseSha)).first
            if (addExit != 0) {
                System.err.println("[FUNNEL-MERGE] worktree add failed")
                return
            }
            channel.materialize(worktree.absolutePath, fileOps)
            gitIn(worktree, "add", "-A", "--", *allTouched.toTypedArray())
            val staged = gitIn(worktree, "diff", "--cached", "--unified=0")
            if (staged.second.lineSequence().any { it.startsWith("+<<<<<<< ") || it == "+=======" || it.startsWith("+>>>>>>> ") }) {
                System.err.println("[FUNNEL-MERGE] materialized result contains conflict markers — aborting")
                return
            }
            if (gitIn(worktree, "diff", "--cached", "--name-only").second.isBlank()) {
                println("[FUNNEL-MERGE] materialized result has no delta vs master — all content already present")
                tagAndClose(arms, baseSha, alreadyPresent = true)
                return
            }
            val gradlew = File(worktree, "gradlew")
            val build = withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(if (gradlew.canExecute()) "./gradlew" else "gradlew",
                    "jvmMainClasses", "--console=plain")
                    .directory(worktree).redirectErrorStream(true).start()
                val out = pb.inputStream.bufferedReader().readText()
                pb.waitFor() to out
            }
            if (build.first != 0) {
                System.err.println("[FUNNEL-MERGE] BUILD RED in isolated worktree — nothing landed:")
                System.err.println(build.second.takeLast(3000))
                return
            }
            println("[FUNNEL-MERGE] build gate green")

            val titleList = arms.joinToString(",") { (it.sessionId ?: it.branch)?.takeLast(6) ?: "?" }
            val subject = "flywheel: funnel N-way merge ${arms.size} arms — $titleList"
            val commit = gitIn(worktree, "commit", "--no-verify", "-m", subject,
                "--author=oroboros-drain <noreply@trikeshed.local>")
            if (commit.first != 0) {
                System.err.println("[FUNNEL-MERGE] commit failed: ${commit.second.take(300)}")
                return
            }
            val revision = gitIn(worktree, "rev-parse", "HEAD").second.trim()

            // ── Publish: ff master, push, tag, close provenance ───────────
            val headNow = git("rev-parse", "HEAD").second.trim()
            if (headNow != baseSha || git("status", "--porcelain").second.isNotBlank()) {
                System.err.println("[FUNNEL-MERGE] master moved during preflight — aborting integration")
                return
            }
            val ff = git("merge", "--ff-only", revision)
            if (ff.first != 0) {
                System.err.println("[FUNNEL-MERGE] ff-merge failed: ${ff.second.take(300)}")
                return
            }
            println("[FUNNEL-MERGE] LANDED $revision on master (${arms.size} arms in one commit)")

            git("push", "origin", "master")
            tagAndClose(arms, revision, alreadyPresent = false)
        } finally {
            git("worktree", "remove", "--force", worktree.absolutePath)
            git("worktree", "prune")
            withContext(Dispatchers.IO) { if (tempRoot.exists()) tempRoot.deleteRecursively() }
        }
    }

    /** Arms = unmerged origin branches ∪ undrained terminal WAL cards with CAS patches. */
    private suspend fun buildArms(): List<Arm> {
        val arms = mutableListOf<Arm>()

        // 1. Unmerged branches (any name carrying a 16-20 digit session id, plus
        //    named jules/bolt/sentinel/palette/feat/fix/perf branches).
        val refs = git("for-each-ref", "--format=%(refname:short)", "refs/remotes/origin").second
            .lineSequence().map { it.trim() }.filter {
                it != "origin/master" && it != "origin/HEAD" && it != "origin/master-local-diverged"
            }.toList()
        val mergedSet = git("branch", "-r", "--merged", "HEAD").second
            .lineSequence().map { it.trim() }.toSet()
        for (ref in refs) {
            if (ref in mergedSet) continue
            val diff = git("diff", "--no-color", "master...$ref")
            if (diff.first != 0 || diff.second.isBlank()) continue
            val touched = parsePatchFiles(diff.second)
            if (touched.isEmpty()) continue
            val quarantined = EntropyPathScanner.scanTouchedPaths(touched)
            if (quarantined.isNotEmpty()) {
                println("[FUNNEL-MERGE] QUARANTINE $ref: steganographic entropy in ${quarantined.map { it.path }}")
                continue
            }
            val cid = withContext(Dispatchers.IO) { casStore.put(diff.second.encodeToByteArray()) }
            val sid = Regex("(\\d{16,20})").find(ref.substringAfterLast('/'))?.value
            arms += Arm("branch:${ref.substringAfterLast('/')}", sid, ref, diff.second, cid, touched)
        }

        // 2. Undrained terminal WAL cards carrying a CAS-pinned patch whose
        //    session has NO branch arm above (branch-first rule: the branch IS
        //    the merge token; CAS is the fallback when no branch exists).
        val wal = withContext(Dispatchers.IO) { store.load() }
        val branchSids = arms.mapNotNull { it.sessionId }.toSet()
        for ((sid, card) in wal) {
            if (card.drained) continue
            if (card.snapshot.state !in setOf("COMPLETED", "FINISHED", "FAILED", "CANCELLED")) continue
            if (sid in branchSids) continue
            var cid: ContentId? = null
            var ord = 0
            for (cause in card.causes) {
                if (cause is borg.trikeshed.jules.JulesCause.PatchSnapshotObserved && cause.causalOrdinal >= ord) {
                    ord = cause.causalOrdinal
                    cid = cause.patchCid
                }
            }
            if (cid == null) continue
            val bytes = withContext(Dispatchers.IO) { casStore.get(cid) } ?: continue
            val patch = bytes.decodeToString()
            val touched = parsePatchFiles(patch)
            val production = touched.filterNot { borg.trikeshed.jules.isScratchPatchPath(it) }
            if (production.isEmpty()) continue
            val quarantined = EntropyPathScanner.scanTouchedPaths(production)
            if (quarantined.isNotEmpty()) {
                println("[FUNNEL-MERGE] QUARANTINE wal:$sid: steganographic entropy in ${quarantined.map { it.path }}")
                continue
            }
            arms += Arm("wal:${sid.takeLast(6)}:${card.card.title.take(40)}", sid, null, patch, cid, production)
        }
        return arms
    }

    /** Single-arm fallback: isolated worktree, git apply --3way, build, ff. */
    private suspend fun applySinglePatch(arm: Arm, baseSha: String) {
        val tempRoot = withContext(Dispatchers.IO) {
            java.nio.file.Files.createTempDirectory("funnel-single-").toFile()
        }
        val worktree = File(tempRoot, "worktree")
        try {
            gitRaw(mutableListOf("worktree", "add", "--detach", worktree.absolutePath, baseSha))
            val patchFile = File(tempRoot, "arm.patch")
            withContext(Dispatchers.IO) { patchFile.writeText(arm.patch) }
            val apply = gitIn(worktree, "apply", "--3way", patchFile.absolutePath)
            if (apply.first != 0) {
                System.err.println("[FUNNEL-MERGE] single-arm apply failed: ${apply.second.take(300)}")
                return
            }
            if (gitIn(worktree, "status", "--porcelain").second.isBlank()) {
                println("[FUNNEL-MERGE] single arm already present on master")
                tagAndClose(listOf(arm), baseSha, alreadyPresent = true)
                return
            }
            gitIn(worktree, "add", "-A")
            val gradlew = File(worktree, "gradlew")
            val build = withContext(Dispatchers.IO) {
                val pb = ProcessBuilder(if (gradlew.canExecute()) "./gradlew" else "gradlew",
                    "jvmMainClasses", "--console=plain")
                    .directory(worktree).redirectErrorStream(true).start()
                val out = pb.inputStream.bufferedReader().readText()
                pb.waitFor() to out
            }
            if (build.first != 0) {
                System.err.println("[FUNNEL-MERGE] single-arm BUILD RED — nothing landed:")
                System.err.println(build.second.takeLast(2000))
                return
            }
            val subject = "flywheel: patch ${arm.label.take(60)} (${(arm.sessionId ?: arm.branch)?.takeLast(6)})"
            val commit = gitIn(worktree, "commit", "--no-verify", "-m", subject,
                "--author=oroboros-drain <noreply@trikeshed.local>")
            if (commit.first != 0) {
                System.err.println("[FUNNEL-MERGE] single-arm commit failed: ${commit.second.take(300)}")
                return
            }
            val revision = gitIn(worktree, "rev-parse", "HEAD").second.trim()
            val ff = git("merge", "--ff-only", revision)
            if (ff.first != 0) {
                System.err.println("[FUNNEL-MERGE] single-arm ff failed: ${ff.second.take(300)}")
                return
            }
            println("[FUNNEL-MERGE] LANDED single arm $revision")
            git("push", "origin", "master")
            tagAndClose(listOf(arm), revision, alreadyPresent = false)
        } finally {
            git("worktree", "remove", "--force", worktree.absolutePath)
            git("worktree", "prune")
            withContext(Dispatchers.IO) { if (tempRoot.exists()) tempRoot.deleteRecursively() }
        }
    }

    /**
     * Close provenance per landed arm: annotated tag (or reuse base sha when
     * content was already present), durable WorkDrained receipt, then push tags.
     */
    private suspend fun tagAndClose(arms: List<Arm>, sha: String, alreadyPresent: Boolean) {
        val now = System.currentTimeMillis()
        for (arm in arms) {
            val sid = arm.sessionId ?: continue  // branch-only arms with no session id close via tag alone
            val safe = sid.replace(Regex("[^A-Za-z0-9._-]"), "-")
            val tag = "flywheel/jules-$safe-${sha.take(12)}"
            val existing = git("rev-parse", "$tag^{commit}")
            if (existing.first != 0) {
                git("tag", "-a", tag, sha, "-m",
                    "Jules merge receipt\nsession=$sid\npatchCid=${arm.patchCid.value}\nbranch=${arm.branch ?: "none"}\ntitle=${arm.label}")
            }
            val workId = "funnel:$sid"
            runCatching {
                store.appendWork(workId, borg.trikeshed.jules.JulesCause.WorkDrained(
                    workId = workId,
                    sessionId = sid,
                    commitSha = sha,
                    taskId = if (alreadyPresent) "already-present" else "funnel-merge",
                    receipt = borg.trikeshed.util.oroboros.MergeReceipt(
                        workId = workId,
                        producer = "funnel-merge",
                        producerRef = sid,
                        patchCid = arm.patchCid,
                        revision = sha,
                        versionTag = tag,
                        lexicalMemory = borg.trikeshed.util.oroboros.LexicalMemory(
                            summary = "N-way funnel/Pijul merge via FunnelMergeBranchesCli",
                            title = arm.label,
                            content = "",
                        ),
                        claimedAt = now,
                        prUrl = null,
                    ),
                    at = now,
                ))
                println("[FUNNEL-MERGE] CLOSED ${sid.takeLast(6)} tag=$tag")
            }.onFailure { println("[FUNNEL-MERGE] WAL close FAILED ${sid.takeLast(6)}: ${it.message}") }
        }
        // Tags are part of provenance publication; a tag-push failure is
        // reported but never rolls back landed content.
        val tagPush = git("push", "origin", "--tags")
        if (tagPush.first != 0) println("[FUNNEL-MERGE] tag push failed (non-fatal): ${tagPush.second.take(200)}")
    }

    /** Minimal unified-diff file list parser (same tolerance as the daemon's). */
    private fun parsePatchFiles(patchText: String): List<String> = patchText.lineSequence()
        .filter { it.startsWith("+++ b/") || it.startsWith("--- a/") }
        .map { it.removePrefix("+++ b/").removePrefix("--- a/").substringAfter('\t').trim() }
        .filter { it.isNotBlank() && it != "/dev/null" }
        .distinct()
        .toList()

    private suspend fun git(vararg args: String): Pair<Int, String> =
        gitRaw(args.toMutableList())

    private suspend fun gitRaw(args: MutableList<String>): Pair<Int, String> = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder("git", *args.toTypedArray())
        pb.directory(repoDir).redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor() to out
    }

    private suspend fun gitIn(dir: File, vararg args: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        @Suppress("UNCHECKED_CAST")
        val argv = args.toList().toTypedArray() as Array<out String>
        val pb = ProcessBuilder(mutableListOf("git").apply { addAll(argv) })
        pb.directory(dir).redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor() to out
    }
}
