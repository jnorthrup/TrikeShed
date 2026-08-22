package borg.trikeshed.jules

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
import borg.trikeshed.userspace.containment.PatchAstLinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bounded operator boundary for choosing one already-observed Jules snapshot.
 * It cannot ingest bytes or mint a CID: the requested object and causal
 * observation must already exist, and reviewer plus receipt are mandatory.
 */
object JulesPatchReviewCli {

    // Must match JulesSettlementCli.PATCH_TERMINAL_STATES: the reject lane runs
    // for FAILED/CANCELLED sessions too (a failed session can still have an
    // observed patch that must be explicitly reviewed before settle-reject —
    // otherwise terminal FAILED sessions with patches are undrainable forever).
    private val PATCH_TERMINAL_STATES = setOf("COMPLETED", "FINISHED", "FAILED", "CANCELLED")
    private val REPORT_TERMINAL_STATES = PATCH_TERMINAL_STATES + setOf("FAILED", "CANCELLED")

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        if (args.firstOrNull() == "report") {
            reviewReport(args.drop(1))
            return@runBlocking
        }
        if (args.firstOrNull() == "reject") {
            rejectPatch(args.drop(1))
            return@runBlocking
        }
        require(args.size in 5..6) {
            "usage: JulesPatchReviewCli <session-id> <patch-cid> <causal-ordinal> " +
                "<reviewer> <receipt-ref> [forge-dir]\n" +
                "   or: JulesPatchReviewCli report <session-id> <report-cid> " +
                "<causal-ordinal> <disposition> <reviewer> <receipt-ref> [forge-dir]\n" +
                "   or: JulesPatchReviewCli reject <session-id> <patch-cid> " +
                "<causal-ordinal> <reason> <reviewer> <receipt-ref> [forge-dir]"
        }
        val sessionId = args[0].substringAfterLast('/')
        val patchCid = ContentId(args[1])
        val causalOrdinal = args[2].toIntOrNull()
            ?: error("causal ordinal must be an integer: ${args[2]}")
        require(causalOrdinal >= 0) { "causal ordinal must be non-negative" }
        val forgeDir = File(args.getOrNull(5) ?: defaultForgeDir())
        val store = JulesBoardStore.forForgeDir(forgeDir)
        val card = requireNotNull(withContext(Dispatchers.IO) { store.load()[sessionId] }) {
            "no causal card for Jules session $sessionId"
        }
        require(card.snapshot.state in PATCH_TERMINAL_STATES) {
            "patch review requires a completed Jules session; ${card.snapshot.state} is still mutable"
        }
        require(!card.drained) { "session $sessionId is already drained" }
        val cas = FileCasStore(JvmFileOperations(), File(forgeDir, "cas").absolutePath)
        val patchBytes = withContext(Dispatchers.IO) { cas.get(patchCid) }
        require(patchBytes != null) {
            "CAS object does not exist: $patchCid"
        }

        val patchStr = patchBytes.decodeToString()
        val lintResult = PatchAstLinter.lint(patchStr)
        require(lintResult.clean) { "Patch blocked by AST Linter: ${lintResult.reason}" }

        val continuity = JulesPatchContinuityStore(cas, store)
        continuity.selectReviewed(
            sessionId = sessionId,
            patchCid = patchCid,
            causalOrdinal = causalOrdinal,
            reviewedBy = args[3],
            receiptRef = args[4],
            causes = card.causes,
        )
        println(
            "{\"sessionId\":\"$sessionId\",\"patchCid\":\"${patchCid.value}\"," +
                "\"causalOrdinal\":$causalOrdinal,\"reviewedBy\":${jsonString(args[3])}," +
                "\"receiptRef\":${jsonString(args[4])},\"selected\":true}",
        )
    }

    /**
     * Bounded operator gate for a typed patch reject: the observed chain is
     * superseded or otherwise unusable, so settlement must retire the session
     * without applying any patch.  Names the rejected snapshot, a durable
     * reason, reviewer, and receipt; never ingests bytes.
     */
    private suspend fun rejectPatch(args: List<String>) {
        require(args.size in 6..7) {
            "usage: JulesPatchReviewCli reject <session-id> <patch-cid> " +
                "<causal-ordinal> <reason> <reviewer> <receipt-ref> [forge-dir]"
        }
        val sessionId = args[0].substringAfterLast('/')
        val patchCid = ContentId(args[1])
        val causalOrdinal = args[2].toIntOrNull()
            ?: error("causal ordinal must be an integer: ${args[2]}")
        require(causalOrdinal >= 0) { "causal ordinal must be non-negative" }
        val forgeDir = File(args.getOrNull(6) ?: defaultForgeDir())
        val store = JulesBoardStore.forForgeDir(forgeDir)
        val card = requireNotNull(withContext(Dispatchers.IO) { store.load()[sessionId] }) {
            "no causal card for Jules session $sessionId"
        }
        require(card.snapshot.state in PATCH_TERMINAL_STATES) {
            "patch reject requires a terminal Jules session; ${card.snapshot.state} is still mutable"
        }
        require(!card.drained) { "session $sessionId is already drained" }
        val cas = FileCasStore(JvmFileOperations(), File(forgeDir, "cas").absolutePath)
        require(withContext(Dispatchers.IO) { cas.get(patchCid) } != null) {
            "CAS object does not exist: $patchCid"
        }
        val continuity = JulesPatchContinuityStore(cas, store)
        continuity.selectRejected(
            sessionId = sessionId,
            patchCid = patchCid,
            causalOrdinal = causalOrdinal,
            reason = args[3],
            reviewedBy = args[4],
            receiptRef = args[5],
            causes = card.causes,
        )
        println(
            "{\"sessionId\":\"$sessionId\",\"patchCid\":\"${patchCid.value}\"," +
                "\"causalOrdinal\":$causalOrdinal,\"rejected\":true," +
                "\"reviewedBy\":${jsonString(args[4])},\"receiptRef\":${jsonString(args[5])}}",
        )
    }

    /**
     * Bounded semantic gate for a report-only result.  The report must already
     * be observed in the session WAL and present in CAS; this command never
     * ingests agent text or infers that a no-patch result is a no-op.
     */
    private suspend fun reviewReport(args: List<String>) {
        require(args.size in 6..7) {
            "usage: JulesPatchReviewCli report <session-id> <report-cid> " +
                "<causal-ordinal> <disposition> <reviewer> <receipt-ref> [forge-dir]"
        }
        val sessionId = args[0].substringAfterLast('/')
        val reportCid = ContentId(args[1])
        val causalOrdinal = args[2].toIntOrNull()
            ?: error("causal ordinal must be an integer: ${args[2]}")
        require(causalOrdinal >= 0) { "causal ordinal must be non-negative" }
        val forgeDir = File(args.getOrNull(6) ?: defaultForgeDir())
        val store = JulesBoardStore.forForgeDir(forgeDir)
        val card = requireNotNull(withContext(Dispatchers.IO) { store.load()[sessionId] }) {
            "no causal card for Jules session $sessionId"
        }
        require(card.snapshot.state in REPORT_TERMINAL_STATES) {
            "report review requires a terminal Jules session; ${card.snapshot.state} is still mutable"
        }
        require(!card.drained) { "session $sessionId is already drained" }
        val cas = FileCasStore(JvmFileOperations(), File(forgeDir, "cas").absolutePath)
        require(withContext(Dispatchers.IO) { cas.get(reportCid) } != null) {
            "CAS object does not exist: $reportCid"
        }
        val continuity = JulesPatchContinuityStore(cas, store)
        continuity.selectReportReviewed(
            sessionId = sessionId,
            reportCid = reportCid,
            causalOrdinal = causalOrdinal,
            disposition = args[3],
            reviewedBy = args[4],
            receiptRef = args[5],
            causes = card.causes,
        )
        println(
            "{\"sessionId\":\"$sessionId\",\"reportCid\":\"${reportCid.value}\"," +
                "\"causalOrdinal\":$causalOrdinal,\"disposition\":${jsonString(args[3])}," +
                "\"reviewedBy\":${jsonString(args[4])}," +
                "\"receiptRef\":${jsonString(args[5])},\"selected\":true}",
        )
    }

    /**
     * Public API: reject a patch directly within the same JVM process.
     * Replaces the `gitJava(repoDir, cp, "JulesPatchReviewCli", "reject", ...)` subprocess call.
     * Eliminates per-session JVM startup overhead in batch drain operations.
     */
    suspend fun apiRejectPatch(
        forgeDir: File,
        sessionId: String,
        patchCid: ContentId,
        causalOrdinal: Int,
        reason: String,
        reviewer: String,
        receiptRef: String,
    ): Result<Unit> = runCatching {
        require(causalOrdinal >= 0) { "causal ordinal must be non-negative" }
        val store = JulesBoardStore.forForgeDir(forgeDir)
        val card = requireNotNull(withContext(Dispatchers.IO) { store.load()[sessionId] }) {
            "no causal card for Jules session $sessionId"
        }
        require(card.snapshot.state in PATCH_TERMINAL_STATES) {
            "patch reject requires a terminal Jules session; ${card.snapshot.state} is still mutable"
        }
        require(!card.drained) { "session $sessionId is already drained" }
        val cas = FileCasStore(JvmFileOperations(), File(forgeDir, "cas").absolutePath)
        require(withContext(Dispatchers.IO) { cas.get(patchCid) } != null) {
            "CAS object does not exist: $patchCid"
        }
        val continuity = JulesPatchContinuityStore(cas, store)
        continuity.selectRejected(
            sessionId = sessionId,
            patchCid = patchCid,
            causalOrdinal = causalOrdinal,
            reason = reason,
            reviewedBy = reviewer,
            receiptRef = receiptRef,
            causes = card.causes,
        )
        println("""{"sessionId":"$sessionId","patchCid":"${patchCid.value}","causalOrdinal":$causalOrdinal,"rejected":true}""")
    }

    /**
     * Public API: review a patch directly within the same JVM process.
     * Eliminates per-session JVM startup overhead in batch drain operations.
     */
    suspend fun apiReviewPatch(
        forgeDir: File,
        sessionId: String,
        patchCid: ContentId,
        causalOrdinal: Int,
        reviewer: String,
        receiptRef: String,
    ): Result<Unit> = runCatching {
        require(causalOrdinal >= 0) { "causal ordinal must be non-negative" }
        val store = JulesBoardStore.forForgeDir(forgeDir)
        val card = requireNotNull(withContext(Dispatchers.IO) { store.load()[sessionId] }) {
            "no causal card for Jules session $sessionId"
        }
        require(card.snapshot.state in PATCH_TERMINAL_STATES) {
            "patch review requires a completed Jules session; ${card.snapshot.state} is still mutable"
        }
        require(!card.drained) { "session $sessionId is already drained" }
        val cas = FileCasStore(JvmFileOperations(), File(forgeDir, "cas").absolutePath)
        val patchBytes = withContext(Dispatchers.IO) { cas.get(patchCid) }
        require(patchBytes != null) { "CAS object does not exist: $patchCid" }
        val patchStr = patchBytes.decodeToString()
        val lintResult = PatchAstLinter.lint(patchStr)
        require(lintResult.clean) { "Patch blocked by AST Linter: ${lintResult.reason}" }
        val continuity = JulesPatchContinuityStore(cas, store)
        continuity.selectReviewed(
            sessionId = sessionId,
            patchCid = patchCid,
            causalOrdinal = causalOrdinal,
            reviewedBy = reviewer,
            receiptRef = receiptRef,
            causes = card.causes,
        )
        println("""{"sessionId":"$sessionId","patchCid":"${patchCid.value}","causalOrdinal":$causalOrdinal,"reviewedBy":${jsonString(reviewer)},"receiptRef":${jsonString(receiptRef)},"selected":true}""")
    }

    private fun defaultForgeDir(): String =
        System.getenv("TRIKESHED_HOME") ?: File(System.getProperty("user.home"), ".local/forge").path

    /** JSON string escaping. */
    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
