package borg.trikeshed.modelmux

import borg.trikeshed.lib.AppendWal
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Append-only logging leaf for modelmux assessments and their receipts.
 *
 * One JSON record per line:
 *   {"t":"assess",  "callId":..., "cardId":..., "modelId":..., "provider":..., "action":..., "prompt":..., "at":...}
 *   {"t":"receipt", ...full ModelResponseReceipt fields...}
 *   {"t":"associate","callId":..., "receiptId":..., "at":...}   // explicit receipt→assessment pointer
 *
 * The leaf is the single source of truth for which model call produced
 * which response. `KanbanBoard.modelCallLog` is an in-memory projection
 * of this leaf; `board.reload()` no longer loses history.
 *
 * Thread-safety: append is guarded by the underlying AppendWal
 * implementation (RandomAccessFile monitor on JVM).
 */
class ModelCallLeaf(private val wal: AppendWal) {

    /** Append the assessment (the intent) to the leaf. */
    suspend fun appendAssessment(
        callId: String,
        cardId: String,
        modelId: String,
        provider: String,
        action: String,
        prompt: String,
    ): Long = withContext(Dispatchers.IO) {
        wal.append(
            key = "assess:$callId",
            payload = """{"t":"assess","callId":"${esc(callId)}","cardId":"${esc(cardId)}","modelId":"${esc(modelId)}","provider":"${esc(provider)}","action":"${esc(action)}","prompt":"${esc(prompt)}","at":${System.currentTimeMillis()}}""".encodeToByteArray()
        )
    }

    /** Append a real response receipt. */
    suspend fun appendReceipt(receipt: ModelResponseReceipt): Long = withContext(Dispatchers.IO) {
        wal.append(
            key = "receipt:${receipt.receiptId}",
            payload = receipt.toJsonLine().encodeToByteArray()
        )
    }

    /**
     * Append an explicit association row that ties a receipt to its
     * assessment. The receipt already carries `assessmentId`, so this
     * row is the cheap observer-friendly audit trail in addition to
     * the field inside the receipt.
     */
    suspend fun appendAssociation(callId: String, receiptId: String): Long = withContext(Dispatchers.IO) {
        wal.append(
            key = "associate:$callId:$receiptId",
            payload = """{"t":"associate","callId":"${esc(callId)}","receiptId":"${esc(receiptId)}","at":${System.currentTimeMillis()}}""".encodeToByteArray()
        )
    }

    /**
     * Append an assessment AND its receipt AND the association in one
     * sequence. Best-effort: a crash between the three appends leaves a
     * partial leaf, but the next read can still join receipts to
     * assessments by `assessmentId` field.
     */
    suspend fun appendCall(
        callId: String,
        cardId: String,
        modelId: String,
        provider: String,
        action: String,
        prompt: String,
        receipt: ModelResponseReceipt,
    ): List<Long> {
        val a = appendAssessment(callId, cardId, modelId, provider, action, prompt)
        val r = appendReceipt(receipt)
        val asn = appendAssociation(callId, receipt.receiptId)
        return listOf(a, r, asn)
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}
