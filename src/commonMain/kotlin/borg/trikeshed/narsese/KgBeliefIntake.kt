package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.lib.*

/** Shared HTTP/LCNC KG intake. Uses the existing bridge and bag, never a second curator. */
suspend fun BeliefBagElement.ingestKg(
    text: String,
    gloss: (Long, String) -> Unit = { _, _ -> },
): Map<String, Any?> {
    require(text.isNotBlank()) { "kg.ingest: empty body" }
    if (text.length > KgRetention.MAX_SOURCE_CHARS) throw KgRetentionRefused(KgRetentionIssue.LIMIT)
    // Reject unpaired UTF16 before parsing or hashing; preserve accepted text exactly, including CRLF.
    text.encodeToByteArray(throwOnInvalidSequence = true)
    val format = KgNalBridge.sniff(text)
    require(format == KgFormat.KIF || format == KgFormat.TURTLE || format == KgFormat.N_TRIPLES) {
        "kg.ingest: unsupported interchange format"
    }
    val mapped = KgNalBridge.bridge(text)
    val artifacts = KgRetention.prepare(text, mapped)
    val sourceCid = artifacts.source.cid
    val durability = retainKg(artifacts)
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
        "format" to format.name,
        "statements" to mapped.size,
        "minted" to mapped.size,
        "copulas" to mapped.groupingBy { it.copula.name }.eachCount(),
        "sourceCid" to sourceCid.value,
        "propositionCids" to artifacts.definitions.view.map { it.cid.value },
        "processedAssertions" to mapped.size,
        "globalResidentCountAfterImport" to size,
        "retention" to _m["status" j "sync-and-exact-bounded-readback-completed",
            "storageDurability" j durability.name, "durabilityGuarantee" j "storage-dependent",
            "sourceRepresentation" j "exact-accepted-text-utf8",
            "sourceAuthentication" j "not-established", "reviewAuthority" j "not-established"],
    )
}
