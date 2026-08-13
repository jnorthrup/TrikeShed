package borg.trikeshed.jules

import borg.trikeshed.job.ContentId
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations
import borg.trikeshed.util.oroboros.FileCasStore
import borg.trikeshed.utils.kanban.JulesBoardStore
import borg.trikeshed.utils.kanban.forForgeDir
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
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        if (args.firstOrNull() == "report") {
            reviewReport(args.drop(1))
            return@runBlocking
        }
        require(args.size in 5..6) {
            "usage: JulesPatchReviewCli <session-id> <patch-cid> <causal-ordinal> " +
                "<reviewer> <receipt-ref> [forge-dir]\n" +
                "   or: JulesPatchReviewCli report <session-id> <report-cid> " +
                "<causal-ordinal> <disposition> <reviewer> <receipt-ref> [forge-dir]"
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
        require(withContext(Dispatchers.IO) { cas.get(patchCid) } != null) {
            "CAS object does not exist: $patchCid"
        }
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

    private fun defaultForgeDir(): String =
        System.getenv("TRIKESHED_HOME") ?: File(System.getProperty("user.home"), ".local/forge").path

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

    private val PATCH_TERMINAL_STATES = setOf("COMPLETED", "FINISHED")
    private val REPORT_TERMINAL_STATES = PATCH_TERMINAL_STATES + setOf("FAILED", "CANCELLED")
}
