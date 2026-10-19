package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId

/** Shared HTTP/LCNC KG intake. Uses the existing bridge and bag, never a second curator. */
suspend fun BeliefBagElement.ingestKg(
    text: String,
    gloss: (Long, String) -> Unit = { _, _ -> },
): Map<String, Any?> {
    require(text.isNotBlank()) { "kg.ingest: empty body" }
    val sourceCid = ContentId.of(text.encodeToByteArray())
    val mapped = KgNalBridge.bridge(text)
    for (statement in mapped) {
        val signal = statement.signal(sourceCid.value)
        val expression = statement.gloss()
        submit(BeliefIntake.Mint(signal, BudgetCoord(0.6f, 0.4f, 0.6f),
            receiptCid = sourceCid, gloss = expression))
        gloss(signal.angular, expression)
    }
    // Legacy wire field: processed assertions, not the number of new resident cells.
    // Same-source replay revises idempotently; the bounded bag may evict older cells.
    return linkedMapOf(
        "format" to KgNalBridge.sniff(text)?.name,
        "statements" to mapped.size,
        "minted" to mapped.size,
        "copulas" to mapped.groupingBy { it.copula.name }.eachCount(),
    )
}
