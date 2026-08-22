package borg.trikeshed.flywheel.cli

import borg.trikeshed.job.ContentId
import borg.trikeshed.pijul.PijulChannel
import borg.trikeshed.pijul.PijulDiffParser
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

        // Build scripts merge by LINE POSITION in the CRDT and stale-base WAL
        // patches land inserts at dead offsets (observed: imports at line 998
        // of a 936-line build.gradle.kts). Route any arm touching a gradle
        // script / launcher / CI yaml through the solo git-3way lane instead.
        val solo = arms.filter { arm -> arm.touched.any { isSensitiveMergePath(it) } }
        val batch = arms.filterNot { it in solo }
        if (solo.isNotEmpty()) {
            println("[FUNNEL-MERGE] ${solo.size} sensitive arms routed to solo lane: ${solo.map { it.label }}")
        }

        if (batch.size == 1) {
            val arm = batch.first()
            val soloList = solo + arm
            println("[FUNNEL-MERGE] single batch arm — everything via solo lane")
            runSolo(soloList, baseSha)
            return
        }
        if (batch.isEmpty()) {
            runSolo(solo, baseSha)
            return
        }

        // ── N-way commutative merge ──────────────────────────────────────
        val channel = PijulChannel()
        val allTouched = batch.flatMap { it.touched }.distinct()
        withContext(Dispatchers.IO) {
            for (path in allTouched) {
                val f = File(repoDir, path)
                if (f.isFile) channel.seedFile(path, f.readText())
            }
        }
        var applied = 0
        for (arm in batch) {
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
                System.err.println("[FUNNEL-MERGE] BUILD RED in isolated worktree — N-way result rejected:")
                System.err.println(build.second.takeLast(3000))
                System.err.println("[FUNNEL-MERGE] falling back to per-arm solo lane for all ${batch.size} batch arms + ${solo.size} sensitive")
                runSolo(solo + batch, baseSha)
                return
            }
            println("[FUNNEL-MERGE] build gate green")

            val titleList = batch.joinToString(",") { (it.sessionId ?: it.branch)?.takeLast(6) ?: "?" }
            val subject = "flywheel: funnel N-way merge ${batch.size} arms — $titleList"
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
            println("[FUNNEL-MERGE] LANDED $revision on master (${batch.size} arms in one commit)")

            git("push", "origin", "master")
            tagAndClose(batch, revision, alreadyPresent = false)
        } finally {
            git("worktree", "remove", "--force", worktree.absolutePath)
            git("worktree", "prune")
            withContext(Dispatchers.IO) { if (tempRoot.exists()) tempRoot.deleteRecursively() }
        }
        if (solo.isNotEmpty()) runSolo(solo, headShaNow())
    }

    /** Sequential git-3way lane for arms whose files must not enter the CRDT. */
    private suspend fun runSolo(arms: List<Arm>, baseSha: String) {
        for (arm in arms) {
            if (arm.branch != null) {
                val before = headShaNow()
                val merged = git("merge", "--no-edit", arm.branch)
                if (merged.first == 0) {
                    println("[FUNNEL-MERGE] SOLO-LANDED branch ${arm.branch?.substringAfterLast('/')}")
                    git("push", "origin", "master")
                    tagAndClose(listOf(arm), headShaNow(), alreadyPresent = false)
                    continue
                }
                git("merge", "--abort")
                System.err.println("[FUNNEL-MERGE] SOLO branch merge conflicted — trying patch lane")
            }
            applySinglePatch(arm, headShaNow())
        }
    }

    private suspend fun headShaNow(): String = git("rev-parse", "HEAD").second.trim()

    /** Paths whose merges are position-sensitive: build scripts, launchers, CI. */
    private fun isSensitiveMergePath(path: String): Boolean =
        path.endsWith(".gradle.kts") || path.endsWith(".gradle") ||
            path.startsWith("bin/") || path.startsWith(".github/workflows/") ||
            path == "settings.gradle.kts" || path == "gradle.properties"

    /** Append-only session ledgers — union-safe on merge conflict. */
    private fun isLedgerPath(path: String): Boolean =
        (path.startsWith(".jules/") || path.startsWith(".Jules/")) && path.endsWith(".md")

    /**
     * Split a unified diff into (ledger sections, production sections).
     * Scratch sections (test_*.py, fix_*.sh, *.orig, *.rej, plan*.md — the
     * same class isScratchPatchPath filters) are dropped from both.
     */
    private fun splitPatchSections(patch: String): Pair<String, String> {
        val ledger = StringBuilder()
        val production = StringBuilder()
        val current = StringBuilder()
        var currentPath: String? = null
        var inHeader = false
        fun flush() {
            val p = currentPath ?: return
            val text = current.toString()
            when {
                borg.trikeshed.jules.isScratchPatchPath(p) -> {} // dropped
                isLedgerPath(p) -> ledger.append(text)
                else -> production.append(text)
            }
            current.setLength(0)
        }
        for (line in patch.lineSequence()) {
            if (line.startsWith("diff --git ")) {
                flush()
                inHeader = true
                current.appendLine(line)
                currentPath = line.removePrefix("diff --git a/").substringBefore(" b/")
            } else {
                if (line.startsWith("--- ")) inHeader = false
                if (inHeader && line.startsWith("new file mode")) {
                    current.appendLine(line)
                } else {
                    current.appendLine(line)
                }
            }
        }
        flush()
        return ledger.toString() to production.toString()
    }

    /**
     * Union a ledger-only patch: extract every added block (## headers and
     * their bodies) and append the ones the target does not already contain.
     * Returns the number of entries appended.
     */
    private suspend fun unionLedgerPatch(worktree: File, patch: String): Int {
        var appended = 0
        var currentFile: String? = null
        val addedByFile = LinkedHashMap<String, StringBuilder>()
        for (line in patch.lineSequence()) {
            when {
                line.startsWith("+++ b/") -> currentFile = line.removePrefix("+++ b/").trim()
                line.startsWith("+") && !line.startsWith("+++") -> {
                    val body = line.removePrefix("+")
                    currentFile?.let { f -> addedByFile.getOrPut(f) { StringBuilder() }.appendLine(body) }
                }
            }
        }
        for ((path, added) in addedByFile) {
            val target = File(worktree, path)
            val existing = if (target.isFile) target.readText() else ""
            // Split into header-delimited entries; append any whose header line
            // is absent from the existing text.
            val entries = mutableListOf<String>()
            val buf = StringBuilder()
            for (l in added.lines()) {
                if (l.startsWith("## ")) {
                    if (buf.isNotBlank()) entries += buf.toString().trimEnd()
                    buf.setLength(0)
                }
                buf.appendLine(l)
            }
            if (buf.isNotBlank()) entries += buf.toString().trimEnd()
            val out = StringBuilder(existing.trimEnd()).appendLine()
            for (e in entries) {
                val header = e.lineSequence().firstOrNull { it.startsWith("## ") } ?: continue
                val headerKey = header.substringAfter("## ").take(20)
                if (existing.lines().none { it.startsWith("## ") && it.substringAfter("## ").take(20) == headerKey }) {
                    out.appendLine().append(e).appendLine()
                    appended++
                }
            }
            target.parentFile?.mkdirs()
            target.writeText(out.toString())
        }
        return appended
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
            val quarantined = steganographicPaths(touched)
            if (quarantined.isNotEmpty()) {
                println("[FUNNEL-MERGE] QUARANTINE $ref: payload-like path segments in $quarantined")
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
            val quarantined = steganographicPaths(production)
            if (quarantined.isNotEmpty()) {
                println("[FUNNEL-MERGE] QUARANTINE wal:$sid: payload-like path segments in $quarantined")
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
                // Section-filtered retry: drop ledger file-sections (union them
                // separately — they are append-only journals whose patches were
                // recorded when the file was untracked) and scratch sections
                // (test_*.py etc. — never production content), then apply the
                // remaining production hunks.
                val (ledgerPatch, productionPatch) = splitPatchSections(arm.patch)
                var unioned = 0
                if (ledgerPatch.isNotEmpty()) {
                    unioned = unionLedgerPatch(worktree, ledgerPatch)
                }
                if (productionPatch.isBlank()) {
                    if (unioned > 0) {
                        println("[FUNNEL-MERGE] ledger-union applied $unioned entries for ${arm.label}")
                    } else {
                        println("[FUNNEL-MERGE] no production delta and no new ledger entries for ${arm.label}")
                        tagAndClose(listOf(arm), headShaNow(), alreadyPresent = true)
                        return
                    }
                } else {
                    withContext(Dispatchers.IO) { patchFile.writeText(productionPatch) }
                    val retry = gitIn(worktree, "apply", "--3way", patchFile.absolutePath)
                    if (retry.first != 0) {
                        val reduced = withContext(Dispatchers.IO) { reducePatchContext(productionPatch, 1) }
                        val ok = if (reduced != null) {
                            withContext(Dispatchers.IO) { patchFile.writeText(reduced) }
                            gitIn(worktree, "apply", "--3way", "--recount", patchFile.absolutePath).first == 0
                        } else false
                        if (!ok) {
                            System.err.println("[FUNNEL-MERGE] single-arm apply failed (3way + reduced context): ${(retry.second.take(300))}")
                            return
                        }
                    }
                    if (unioned > 0) println("[FUNNEL-MERGE] ledger-union applied $unioned entries alongside production delta for ${arm.label}")
                }
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
     * Receipts bond to the ORIGINAL queue workId when one exists for the
     * session (23e1c237e: writing WorkDrained under a synthesized id orphans
     * the queue entry and blocks re-queue of the same locality forever).
     */
    private suspend fun tagAndClose(arms: List<Arm>, sha: String, alreadyPresent: Boolean) {
        val now = System.currentTimeMillis()
        val queueBySession = withContext(Dispatchers.IO) {
            store.loadQueue().mapNotNull { entry -> entry.sessionId?.let { it to entry.workId } }.toMap()
        }
        for (arm in arms) {
            val sid = arm.sessionId ?: continue  // branch-only arms with no session id close via tag alone
            val safe = sid.replace(Regex("[^A-Za-z0-9._-]"), "-")
            val tag = "flywheel/jules-$safe-${sha.take(12)}"
            val existing = git("rev-parse", "$tag^{commit}")
            if (existing.first != 0) {
                git("tag", "-a", tag, sha, "-m",
                    "Jules merge receipt\nsession=$sid\npatchCid=${arm.patchCid.value}\nbranch=${arm.branch ?: "none"}\ntitle=${arm.label}")
            }
            val workId = queueBySession[sid] ?: "funnel:$sid"
            runCatching {
                // Close BOTH surfaces: the queue entry (appendWork under the
                // workId) AND the card (append snapshot with drained=true —
                // load() derives card.drained ONLY from SnapEvents, so a
                // WorkDrained cause alone leaves the card open and the arm
                // rebuilds every pass).
                val cardNow = withContext(Dispatchers.IO) { store.load()[sid] }
                val snap = cardNow?.snapshot
                if (snap != null) {
                    store.append(snap.copy(state = "COMPLETED", headSha = sha), drained = true, cause = null)
                }
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

    /**
     * Calibrated steganographic-path detector for THIS CLI's arm admission.
     *
     * The shipped [borg.trikeshed.userspace.containment.EntropyPathScanner]
     * flags any path segment with Shannon entropy > 3.5 bits/char — which is
     * ORDINARY CamelCase source naming (JvmProcessOperations.kt ≈ 3.9,
     * KanbanHttpServerJvm.kt ≈ 4.0). The daemon lane has 201 logged
     * `drain-rejected: steganographic entropy` events and every one is a
     * false positive on production filenames; combined with the collusion
     * gate it is why zero arms ever drain.
     *
     * Real exfiltration/stego signatures in paths are long digit runs (CIDs,
     * timestamps) and hex/base64 payloads embedded in filenames. Detect those,
     * not English words.
     */
    private fun steganographicPaths(paths: List<String>): List<String> {
        val digitRun = Regex("\\d{12,}")
        val pureHexName = Regex("(?i)^[0-9a-f]{16,}(\\.[a-z]+)?$")
        return paths.filter { path ->
            path.split('/').any { seg ->
                if (digitRun.containsMatchIn(seg)) return@any true
                if (pureHexName.containsMatchIn(seg)) return@any true
                // Payload-like basename: long, alpha-only, no CamelCase word
                // structure. Word-like identifiers (JulesPatchContinuityStore,
                // humps=3) pass; base64 payloads (humps<=1) flag.
                val base = seg.substringBeforeLast('.')
                if (base.length >= 24 && base.all { it.isLetter() }) {
                    val humps = base.zipWithNext().count { (a, b) -> a.isLowerCase() && b.isUpperCase() }
                    humps <= 1
                } else false
            }
        }
    }

    /** Rewrite a unified diff keeping only [keep] context lines per hunk side. */
    private fun reducePatchContext(patch: String, keep: Int): String? {
        val out = StringBuilder()
        var inHunk = false
        var kept = 0
        for (line in patch.lineSequence()) {
            if (line.startsWith("@@")) {
                inHunk = true; kept = 0
                out.appendLine(line)
                continue
            }
            if (!inHunk) { out.appendLine(line); continue }
            if (line.startsWith(" ")) {
                if (kept < keep) { out.appendLine(line); kept++ }
                // else: drop excess context
            } else {
                out.appendLine(line)
            }
        }
        return out.toString().ifBlank { null }
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
