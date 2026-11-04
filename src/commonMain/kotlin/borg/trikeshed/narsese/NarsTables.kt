package borg.trikeshed.narsese

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.cursor.ColumnCursor
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.cursor.columnNames
import borg.trikeshed.cursor.query.rowsCursor
import borg.trikeshed.cursor.values
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*
import borg.trikeshed.parse.kursive.sql.ordinalOf
import borg.trikeshed.parse.kursive.sql.scalarText

/**
 * Rows as beliefs: a cursor with `subject`, `predicate`, `object` (and optionally `confidence`)
 * columns — a query result, say — is read as [KgTriplet]s, mapped through [KgNalBridge] as the text
 * ingest is, and minted under one [receiptCid] (the query's receipt), so the evidence of one result
 * is counted once however often it is replayed. As the text path names its body, each signal's
 * `subjectCid` is the receipt; as the curator's receipts do, its `provenanceCid` is the receipt too.
 * A row with a null subject, predicate or object mints nothing and is reported under `skipped`.
 */
suspend fun BeliefBagElement.ingestKg(
    rows: Cursor,
    receiptCid: ContentId,
    budget: BudgetCoord = BudgetCoord(0.6f, 0.4f, 0.6f),
): Map<String, Any?> {
    val copulas = LinkedHashMap<String, Int>()
    var minted = 0
    var skipped = 0
    if (rows.size > 0) {
        val names = rows.columnNames
        val subject = names.ordinalOf("subject")
        val predicate = names.ordinalOf("predicate")
        val obj = names.ordinalOf("object")
        val confidence = (0 until names.size).firstOrNull { names[it].contentEquals("confidence") || names[it].endsWith(".confidence") }
        for (r in 0 until rows.size) {
            val v = rows[r].values
            val s = v[subject]?.let(::scalarText)
            val p = v[predicate]?.let(::scalarText)
            val o = v[obj]?.let(::scalarText)
            if (s == null || p == null || o == null) { skipped++; continue }
            val c = confidence?.let { (v[it] as? Number)?.toFloat() } ?: 0.9f
            val mapped = KgNalBridge.map(KgTriplet(s, p, o, confidence = c))
            submit(BeliefIntake.Mint(mapped.signal(receiptCid.value, receiptCid.value), budget, receiptCid = receiptCid, gloss = mapped.gloss()))
            copulas[mapped.copula.name] = (copulas[mapped.copula.name] ?: 0) + 1
            minted++
        }
    }
    return mapOf("statements" to minted, "minted" to minted, "skipped" to skipped, "copulas" to copulas, "receiptCid" to receiptCid.value)
}

/** The ledger as the table `nars_evidence`: slot, key, positive, negative, basis (the cardinality of its source set), occupied slots only. */
fun EvidenceLedger.cursor(): ColumnCursor {
    val slots = eternal(0f)
    return rowsCursor(
        listOf("slot", "key", "positive", "negative", "basis"),
        slots.map { s -> mapOf("slot" to s.toLong(), "key" to key(s), "positive" to evidence(s).positive, "negative" to evidence(s).negative, "basis" to basis(s).cardinality.toLong()) },
        listOf("slot", "key", "positive", "negative", "basis").associateWith { "int8" },
    )
}

/**
 * The bag as the table `nars_beliefs`: angular, frequency and confidence (the bag's own truth horizon),
 * the budget's priority, durability and quality, relation, subjectCid, objectCid, provenanceCid, gloss, then optional propositionCid.
 */
fun BeliefBagElement.cursor(): ColumnCursor = rowsCursor(
    _l["angular", "frequency", "confidence", "priority", "durability", "quality", "relation", "subjectCid", "objectCid", "provenanceCid", "gloss", "propositionCid"],
    snapshot().entries.sortedBy { it.key.a }.map { (key, signal) ->
        val angular = key.a
        val budget = BudgetCoord(key.b)
        val truth = Nal.truthOf(signal.evidence)
        _m[
            "angular" j angular, "frequency" j truth.frequency.toDouble(), "confidence" j truth.confidence.toDouble(),
            "priority" j budget.pf.toDouble(), "durability" j budget.df.toDouble(), "quality" j budget.qf.toDouble(),
            "relation" j signal.relation.name, "subjectCid" j signal.subjectCid, "objectCid" j signal.objectCid,
            "provenanceCid" j signal.provenanceCid, "gloss" j glossOf(angular), "propositionCid" j signal.propositionCid,
        ]
    },
    _m["angular" j "int8", "frequency" j "float8", "confidence" j "float8", "priority" j "float8", "durability" j "float8", "quality" j "float8", "objectCid" j "text", "provenanceCid" j "text", "gloss" j "text", "propositionCid" j "text"],
)
