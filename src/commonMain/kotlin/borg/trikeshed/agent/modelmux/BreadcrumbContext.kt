package borg.trikeshed.agent.modelmux

import borg.trikeshed.lib.*
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.parse.*
import borg.trikeshed.parse.confix.ConfixDoc
import kotlinx.datetime.Clock
import borg.trikeshed.agent.modelmux.acp.BREADCRUMB_SKILL
import borg.trikeshed.agent.modelmux.breadcrumbPrefix
import borg.trikeshed.agent.modelmux.particles
import borg.trikeshed.agent.modelmux.project
import kotlin.jvm.JvmInline
import kotlin.math.pow

@JvmInline
value class BreadcrumbId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 256) { "breadcrumb id must contain 1-256 characters" } }
}

@JvmInline
value class BreadcrumbPresence(val value: Double) {
    init { require(value.isFinite() && value in 0.0..1.0) { "presence must be finite and within [0,1]" } }
}

@JvmInline
value class BreadcrumbTimestamp(val value: Long)

typealias BreadcrumbDuration = Long
typealias BreadcrumbSource = String
typealias BreadcrumbContent = String

/** Original content is retained independently of its current prompt projection. */
data class BreadcrumbRecord(
    val id: borg.trikeshed.agent.modelmux.BreadcrumbId,
    val source: borg.trikeshed.agent.modelmux.BreadcrumbSource,
    val content: borg.trikeshed.agent.modelmux.BreadcrumbContent,
    val createdAt: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp,
    val lastAccessAt: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp,
    /** The cleaner's weight on decay: scale-back multiplies it down or drops it to 0, scale-up restores it. */
    val gain: Double = 1.0,
    /** Breadcrumbs this one carried (a turn's tool results); recalling it recalls them, in sequence. */
    val children: List<borg.trikeshed.agent.modelmux.BreadcrumbId> = emptyList(),
) {
    init { require(gain.isFinite() && gain in 0.0..1.0) { "breadcrumb gain must be finite and within [0,1]" } }
}

/** Age and time since explicit retrieval independently reduce prompt presence. */
data class BreadcrumbRetention(
    val ageHalfLifeMs: borg.trikeshed.agent.modelmux.BreadcrumbDuration = 3_600_000,
    val recencyHalfLifeMs: borg.trikeshed.agent.modelmux.BreadcrumbDuration = 300_000,
    val threshold: borg.trikeshed.agent.modelmux.BreadcrumbPresence = borg.trikeshed.agent.modelmux.BreadcrumbPresence(
        0.01
    ),
) {
    init {
        require(ageHalfLifeMs > 0) { "age half-life must be positive" }
        require(recencyHalfLifeMs > 0) { "recency half-life must be positive" }
    }

    fun presence(record: borg.trikeshed.agent.modelmux.BreadcrumbRecord, at: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp): borg.trikeshed.agent.modelmux.BreadcrumbPresence {
        val age = (at.value.toDouble() - record.createdAt.value.toDouble()).coerceAtLeast(0.0)
        val idle = (at.value.toDouble() - record.lastAccessAt.value.toDouble()).coerceAtLeast(0.0)
        val value = 0.5.pow(age / ageHalfLifeMs + idle / recencyHalfLifeMs) * record.gain
        return borg.trikeshed.agent.modelmux.BreadcrumbPresence(if (value < threshold.value) 0.0 else value)
    }
}

/** Fractional presence holds a particulate summary of the original ([borg.trikeshed.agent.modelmux.particles]); children render nested. */
data class BreadcrumbProjection(
    val id: borg.trikeshed.agent.modelmux.BreadcrumbId,
    val source: borg.trikeshed.agent.modelmux.BreadcrumbSource,
    val presence: borg.trikeshed.agent.modelmux.BreadcrumbPresence,
    val content: borg.trikeshed.agent.modelmux.BreadcrumbContent,
    val children: List<BreadcrumbProjection> = emptyList(),
    /** False for a breadcrumb carried by another; the trail lists only roots. */
    val root: Boolean = true,
    val reference: borg.trikeshed.agent.modelmux.BreadcrumbRef? = null,
    val cid: ContentId? = null,
    val title: String? = null,
) {
    /** A zero-presence reference remains addressable with the breadcrumb tool. */
    fun render(): String = buildString {
        append("{\"id\":").append(jsonOf(id.value))
        reference?.let { append(",\"ref\":").append(jsonOf(it.value)) }
        cid?.let { append(",\"cid\":").append(jsonOf(it.value)) }
        title?.let { append(",\"title\":").append(jsonOf(it.breadcrumbPrefix(600))) }
        append(",\"source\":").append(jsonOf(source.breadcrumbPrefix(160)))
        append(",\"presence\":").append(presence.value)
        if (presence.value > 0.0) append(",\"content\":").append(jsonOf(content))
        if (children.isNotEmpty()) {
            append(",\"breadcrumbs\":[")
            children.forEachIndexed { index, child -> if (index > 0) append(','); append(child.render()) }
            append(']')
        }
        append('}')
    }
}

/** Immutable tool-message JSON from one submitted request, independent of later decay. */
data class BreadcrumbSubmission(
    val model: String,
    val at: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp,
    val messages: Series<String>,
    val messageCount: Int,
    val exact: Boolean,
) {
    fun json(context: String): String = buildString {
        append("{\"context\":").append(jsonOf(context))
        append(",\"model\":").append(jsonOf(model))
        append(",\"atMs\":").append(at.value)
        append(",\"exact\":").append(exact)
        append(",\"reason\":").append(if (exact) "null" else "\"capacity\"")
        append(",\"messageCount\":").append(messageCount)
        append(",\"messages\":[")
        messages.view.forEachIndexed { index, message ->
            if (index > 0) append(',')
            append(message)
        }
        append("]}")
    }
}

fun borg.trikeshed.agent.modelmux.BreadcrumbRecord.project(presence: borg.trikeshed.agent.modelmux.BreadcrumbPresence, lexicon: borg.trikeshed.agent.modelmux.BreadcrumbLexicon? = null): borg.trikeshed.agent.modelmux.BreadcrumbProjection =
    borg.trikeshed.agent.modelmux.BreadcrumbProjection(
        id,
        source,
        presence,
        particles(presence.value, lexicon)
    )

fun String.breadcrumbPrefix(limit: Int): String {
    var end = minOf(length, limit)
    if (end > 0 && end < length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end--
    return substring(0, end)
}

/** Synchronous session state; asynchronous callers serialize access through their owning channel. */
class BreadcrumbContext(
    val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    val retention: borg.trikeshed.agent.modelmux.BreadcrumbRetention = borg.trikeshed.agent.modelmux.BreadcrumbRetention(),
    val index: borg.trikeshed.agent.modelmux.BreadcrumbIndex = borg.trikeshed.agent.modelmux.BreadcrumbIndex(),
) {
    private val records = LinkedHashMap<borg.trikeshed.agent.modelmux.BreadcrumbId, borg.trikeshed.agent.modelmux.BreadcrumbRecord>()
    private val pending = LinkedHashMap<borg.trikeshed.agent.modelmux.BreadcrumbId, borg.trikeshed.agent.modelmux.BreadcrumbPresence>()
    private val emissions = mutableSetOf<borg.trikeshed.agent.modelmux.BreadcrumbId>()
    private val emissionReservations = mutableMapOf<borg.trikeshed.agent.modelmux.BreadcrumbId, Int>()
    private val calls = LinkedHashMap<Triple<String, Long, Long>, borg.trikeshed.agent.modelmux.MuxCallRecord>()
    private var sequence = 0L
    var submission: borg.trikeshed.agent.modelmux.BreadcrumbSubmission? = null
        private set
    val overflow = borg.trikeshed.agent.modelmux.BreadcrumbOverflow()
    var band = borg.trikeshed.agent.modelmux.BreadcrumbBand.SCALE_UP
        private set
    var bandAt: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp? = null
        private set
    var lastTokens = 0L
        private set

    /** A transaction checkpoint includes reservations; a durable state deliberately does not. */
    fun copy(): BreadcrumbContext = BreadcrumbContext(clock, retention, index.copy()).also { it.restore(this) }

    fun restore(checkpoint: BreadcrumbContext) {
        require(retention == checkpoint.retention) { "breadcrumb retention differs from checkpoint" }
        if (checkpoint === this) return
        records.clear(); records.putAll(checkpoint.records)
        index.restore(checkpoint.index)
        pending.clear(); pending.putAll(checkpoint.pending)
        emissions.clear(); emissions.addAll(checkpoint.emissions)
        emissionReservations.clear(); emissionReservations.putAll(checkpoint.emissionReservations)
        calls.clear(); calls.putAll(checkpoint.calls)
        sequence = checkpoint.sequence
        submission = checkpoint.submission
        overflow.restore(checkpoint.overflow)
        band = checkpoint.band
        bandAt = checkpoint.bandAt
        lastTokens = checkpoint.lastTokens
    }

    /**
     * One turn of the cleaner: record whether the outgoing prompt of [tokens] overflowed, then act on the band.
     * Scale-back drops records older than a minute under 0.3 presence (gain 0 keeps their reference) and
     * multiplies the rest by 0.9; scale-up recalls dropped records among the 50 newest and multiplies all by 1.05.
     */
    fun clean(tokens: Long, at: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp = borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(
        clock()
    )
    ): borg.trikeshed.agent.modelmux.BreadcrumbBand {
        require(tokens >= 0) { "breadcrumb token estimate cannot be negative" }
        lastTokens = tokens
        overflow.record(tokens > borg.trikeshed.agent.modelmux.BreadcrumbOverflow.BUDGET_TOKENS)
        val current = overflow.band
        if (current != band) { band = current; bandAt = at }
        when (current) {
            borg.trikeshed.agent.modelmux.BreadcrumbBand.SCALE_BACK -> for (record in records.values.toList()) {
                val drop = at.value - record.createdAt.value > borg.trikeshed.agent.modelmux.BreadcrumbHorizon.DROP_AGE_MS &&
                    retention.presence(record, at).value < borg.trikeshed.agent.modelmux.BreadcrumbHorizon.DROP_PRESENCE
                records[record.id] = record.copy(gain = if (drop) 0.0 else record.gain * borg.trikeshed.agent.modelmux.BreadcrumbHorizon.BACK)
            }
            borg.trikeshed.agent.modelmux.BreadcrumbBand.SCALE_UP -> {
                val recall = records.values.sortedByDescending { it.createdAt.value }.take(borg.trikeshed.agent.modelmux.BreadcrumbHorizon.RECALL)
                    .filter { it.gain == 0.0 }.map { it.id }.toSet()
                for (record in records.values.toList()) {
                    val gain = if (record.id in recall) 1.0 else record.gain
                    records[record.id] = record.copy(gain = minOf(1.0, gain * borg.trikeshed.agent.modelmux.BreadcrumbHorizon.UP))
                }
            }
            borg.trikeshed.agent.modelmux.BreadcrumbBand.HOLD -> {}
        }
        return current
    }

    /** The cleaner's state for inspection, the trail depth-first in sequence order with each record's particles; reading it neither cleans nor refreshes recency. */
    fun horizonJson(context: String, at: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp = borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(
        clock()
    )
    ): String {
        val carried = records.values.flatMapTo(mutableSetOf()) { it.children }
        val lexicon = lexicon()
        val trail = mutableListOf<Map<String, Any?>>()
        val seen = mutableSetOf<borg.trikeshed.agent.modelmux.BreadcrumbId>()
        fun walk(record: borg.trikeshed.agent.modelmux.BreadcrumbRecord, depth: Int) {
            if (!seen.add(record.id)) return
            val presence = retention.presence(record, at).value
            trail += mapOf("id" to record.id.value, "source" to record.source.breadcrumbPrefix(160), "depth" to depth,
                "presence" to presence, "gain" to record.gain,
                "ageMs" to (at.value - record.createdAt.value), "chars" to record.content.length,
                "children" to record.children.size, "content" to record.particles(presence, lexicon))
            for (child in record.children) records[child]?.let { walk(it, depth + 1) }
        }
        for (record in records.values) if (record.id !in carried) walk(record, 0)
        for (record in records.values) walk(record, 0)
        return jsonOf(mapOf(
            "context" to context, "band" to band.wire, "bandAtMs" to bandAt?.value,
            "errorRate" to overflow.rate, "turns" to overflow.count, "window" to overflow.window,
            "outcomes" to overflow.encode(), "lastTokens" to lastTokens, "budgetTokens" to borg.trikeshed.agent.modelmux.BreadcrumbOverflow.BUDGET_TOKENS,
            "records" to trail,
        ))
    }

    /** Longs are decimal strings; submitted rows remain opaque strings with their original JSON bytes. */
    fun state(): String {
        require(records.size <= RECORD_LIMIT && emissions.size <= RECORD_LIMIT) { "breadcrumb record capacity reached" }
        require(records.values.sumOf { it.content.length.toLong() } <= CONTENT_LIMIT) { "breadcrumb content capacity reached" }
        require(records.values.all { it.source.length <= SOURCE_LIMIT }) { "breadcrumb source capacity reached" }
        submission?.let(::validateSubmission)
        val value = jsonOf(mapOf(
            "version" to 1,
            "retention" to mapOf("ageHalfLifeMs" to retention.ageHalfLifeMs.toString(),
                "recencyHalfLifeMs" to retention.recencyHalfLifeMs.toString(), "threshold" to retention.threshold.value),
            "sequence" to sequence.toString(),
            "records" to records.values.map { mapOf("id" to it.id.value, "source" to it.source,
                "content" to it.content, "createdAt" to it.createdAt.value.toString(), "lastAccessAt" to it.lastAccessAt.value.toString(),
                "gain" to it.gain, "children" to it.children.map { child -> child.value }) },
            "index" to index.metadataState(),
            "pending" to pending.map { mapOf("id" to it.key.value, "presence" to it.value.value) },
            "emissions" to emissions.map { it.value },
            "receipts" to calls.values.map(::receiptState),
            "horizon" to mapOf("outcomes" to overflow.encode(), "band" to band.wire,
                "bandAtMs" to bandAt?.value?.toString(), "lastTokens" to lastTokens.toString()),
            "submission" to submission?.let { mapOf("model" to it.model, "atMs" to it.at.value.toString(),
                "messageCount" to it.messageCount, "exact" to it.exact, "messages" to it.messages.view) },
        ))
        // Escaping surrogate code units also preserves lone surrogates across the UTF-8 storage boundary.
        return buildString {
            for (char in value) if (char.isSurrogate()) append("\\u").append(char.code.toString(16).padStart(4, '0'))
            else append(char)
        }.also { require(it.length <= STATE_LIMIT) { "breadcrumb state capacity reached" } }
    }

    fun submit(value: borg.trikeshed.agent.modelmux.BreadcrumbSubmission) { submission = value }

    /** Only completed attempts are durable; model and start time disambiguate restarted activity counters. */
    fun record(call: borg.trikeshed.agent.modelmux.MuxCallRecord) {
        require(call.endedAt != null && call.status in setOf("completed", "failed", "cancelled")) { "breadcrumb receipt must be finished" }
        require(receiptSize(call) <= RECEIPT_CONTENT_LIMIT) { "breadcrumb receipt capacity reached" }
        calls[Triple(call.model, call.id, call.startedAt)] = call
        while (calls.size > RECORD_LIMIT || calls.values.sumOf(::receiptSize) > RECEIPT_CONTENT_LIMIT) {
            calls.remove(calls.keys.first())
        }
    }

    fun receipts(): Series<borg.trikeshed.agent.modelmux.MuxCallRecord> = calls.values.toTypedArray().toSeries()

    fun summary(context: String): Map<String, Any?> = mapOf(
        "context" to context, "records" to records.size,
        "submittedAtMs" to submission?.at?.value, "model" to submission?.model,
        "submittedMessages" to (submission?.messageCount ?: 0), "exact" to submission?.exact,
        "calls" to calls.size, "lastCallAtMs" to calls.values.maxOfOrNull { it.startedAt },
        "band" to band.wire, "errorRate" to overflow.rate, "turns" to overflow.count,
    )

    fun submittedJson(context: String): String = submission?.json(context) ?: jsonOf(mapOf(
        "context" to context, "model" to null, "atMs" to null,
        "exact" to false, "reason" to "not_submitted", "messageCount" to 0,
        "messages" to emptyList<Any>(),
    ))

    /** Re-ingestion of a tool result neither refreshes recency nor replaces its evidence. */
    fun put(
        id: borg.trikeshed.agent.modelmux.BreadcrumbId,
        source: borg.trikeshed.agent.modelmux.BreadcrumbSource,
        content: borg.trikeshed.agent.modelmux.BreadcrumbContent,
        at: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp = borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(
            clock()
        ),
        children: List<borg.trikeshed.agent.modelmux.BreadcrumbId> = emptyList(),
        title: String? = null,
        heritage: ConfixDoc? = null,
    ): borg.trikeshed.agent.modelmux.BreadcrumbRecord {
        records[id]?.let { record ->
            require(record.source == source && record.content == content) { "breadcrumb id already contains different evidence: ${id.value}" }
            return record
        }
        return borg.trikeshed.agent.modelmux.BreadcrumbRecord(
            id,
            source,
            content,
            at,
            at,
            children = children.filter { it != id }.distinct()
        ).also {
            index.put(it, title ?: if (source.startsWith("summary:")) content else source, heritage)
            records[id] = it
        }
    }

    fun put(
        source: borg.trikeshed.agent.modelmux.BreadcrumbSource,
        content: borg.trikeshed.agent.modelmux.BreadcrumbContent,
        at: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp = borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(
            clock()
        ),
    ): borg.trikeshed.agent.modelmux.BreadcrumbRecord {
        var id: borg.trikeshed.agent.modelmux.BreadcrumbId
        do { id = borg.trikeshed.agent.modelmux.BreadcrumbId("breadcrumb-${++sequence}")
        } while (id in records)
        return put(id, source, content, at)
    }

    /** Metadata/evidence lookup is observational; only an explicit resize updates access time. */
    fun lookup(id: borg.trikeshed.agent.modelmux.BreadcrumbId): borg.trikeshed.agent.modelmux.BreadcrumbRecord? = records[id]

    /** Retrieval envelopes become history only after a successful model response. */
    fun emitted(id: borg.trikeshed.agent.modelmux.BreadcrumbId): Boolean = id in emissions

    fun reserveEmissions(ids: Set<borg.trikeshed.agent.modelmux.BreadcrumbId>) {
        val outstanding = emissionReservations.keys.count { it !in emissions }
        val additional = ids.count { it !in emissions && it !in emissionReservations }
        require(emissions.size + outstanding + additional <= 1000) { "breadcrumb emission capacity reached" }
        for (id in ids) emissionReservations[id] = (emissionReservations[id] ?: 0) + 1
    }

    fun completeEmissions(ids: Set<borg.trikeshed.agent.modelmux.BreadcrumbId>, successful: Boolean) {
        for (id in ids) {
            val count = requireNotNull(emissionReservations[id]) { "breadcrumb emission was not reserved" }
            if (successful) emissions.add(id)
            if (count == 1) emissionReservations.remove(id) else emissionReservations[id] = count - 1
        }
    }

    /** The latest explicit request for each ID applies to the next result, exactly once; carried breadcrumbs follow it. */
    fun resize(
        id: borg.trikeshed.agent.modelmux.BreadcrumbId,
        presence: borg.trikeshed.agent.modelmux.BreadcrumbPresence,
        at: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp = borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(
            clock()
        ),
    ): Boolean = access(id, at, presence)

    /** Indexed recall refreshes lineage access without scheduling an unbounded legacy projection. */
    fun access(
        id: borg.trikeshed.agent.modelmux.BreadcrumbId,
        at: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp = borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(
            clock()
        ),
        presence: borg.trikeshed.agent.modelmux.BreadcrumbPresence? = null,
    ): Boolean {
        if (id !in records) return false
        val visited = mutableSetOf<borg.trikeshed.agent.modelmux.BreadcrumbId>()
        fun explode(next: borg.trikeshed.agent.modelmux.BreadcrumbId) {
            val record = records[next] ?: return
            if (!visited.add(next)) return
            records[next] = record.copy(lastAccessAt = borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(
                maxOf(record.lastAccessAt.value, at.value)
            )
            )
            if (presence != null) pending[next] = presence
            for (child in record.children) explode(child)
        }
        explode(id)
        return true
    }

    /** The trail's word counts, for [borg.trikeshed.agent.modelmux.particles]. */
    fun lexicon(): borg.trikeshed.agent.modelmux.BreadcrumbLexicon =
        borg.trikeshed.agent.modelmux.BreadcrumbLexicon(records.values)

    /** Automatic rendering does not count as access and does not consume queued overrides. */
    fun snapshot(at: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp = borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(
        clock()
    )
    ): Series<borg.trikeshed.agent.modelmux.BreadcrumbProjection> =
        project(at, emptyMap())

    /** Snapshot both evidence and overrides before clearing pending state, so lazy reads stay stable. */
    fun nextToolResult(at: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp = borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(
        clock()
    )
    ): Series<borg.trikeshed.agent.modelmux.BreadcrumbProjection> {
        val overrides = pending.toMap()
        val result = project(at, overrides)
        pending.clear()
        return result
    }

    /** Every record in sequence order, each carrying its children's projections; roots are those no record carries. */
    private fun project(
        at: borg.trikeshed.agent.modelmux.BreadcrumbTimestamp,
        overrides: Map<borg.trikeshed.agent.modelmux.BreadcrumbId, borg.trikeshed.agent.modelmux.BreadcrumbPresence>,
    ): Series<borg.trikeshed.agent.modelmux.BreadcrumbProjection> {
        val rows = records.values.toTypedArray()
        val byId = records.toMap()
        val carried = rows.flatMapTo(mutableSetOf()) { it.children }
        val lexicon = lexicon()
        fun one(record: borg.trikeshed.agent.modelmux.BreadcrumbRecord, path: Set<borg.trikeshed.agent.modelmux.BreadcrumbId>): borg.trikeshed.agent.modelmux.BreadcrumbProjection =
            record.project(overrides[record.id] ?: retention.presence(record, at), lexicon).copy(
                reference = index[record.id]?.ref, cid = index[record.id]?.cid, title = index[record.id]?.title,
                children = record.children.filter { it !in path }.mapNotNull { byId[it] }.map { one(it, path + it.id) },
                root = record.id !in carried)
        return rows.size j { index -> one(rows[index], setOf(rows[index].id)) }
    }

    companion object {
        const val RECORD_LIMIT = 1000
        const val CONTENT_LIMIT = 500_000
        const val SOURCE_LIMIT = 1024
        const val SUBMISSION_LIMIT = 500_000 * 6 + 1000 * 320
        const val RECEIPT_CONTENT_LIMIT = 500_000
        const val STATE_LIMIT = 24_000_000

        /** Decode before admission. Reads retain original time and queued overrides without exercising them. */
        fun restore(state: String, clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
                    cas: CasStore = CasStore.inMemory(), facets: ProjectionFacets? = null): BreadcrumbContext {
            require(state.length <= STATE_LIMIT) { "breadcrumb state capacity reached" }
            val fields = setOf("version", "retention", "sequence", "records", "pending", "emissions", "submission")
            val parsed = reifyStrict(state)
            val present = (parsed as? Map<*, *>)?.keys ?: emptySet<Any?>()
            val root = objectState(parsed, fields + setOf("receipts", "horizon", "index").filter { it in present })
            require(root["version"] == 1.0) { "unsupported breadcrumb state version" }
            val policy = objectState(root["retention"], setOf("ageHalfLifeMs", "recencyHalfLifeMs", "threshold"))
            val retention = borg.trikeshed.agent.modelmux.BreadcrumbRetention(
                longState(policy["ageHalfLifeMs"]),
                longState(policy["recencyHalfLifeMs"]),
                presenceState(policy["threshold"])
            )
            val context = BreadcrumbContext(clock, retention,
                borg.trikeshed.agent.modelmux.BreadcrumbIndex(cas, facets)
            )
            context.sequence = longState(root["sequence"]).also { require(it in 0 until Long.MAX_VALUE) { "invalid breadcrumb sequence" } }
            var size = 0L
            for (value in arrayState(root["records"])) {
                val row = objectState(value, setOf("id", "source", "content", "createdAt", "lastAccessAt") +
                    setOf("gain", "children").filter { (value as? Map<*, *>)?.containsKey(it) == true })
                val id = borg.trikeshed.agent.modelmux.BreadcrumbId(stringState(row["id"]))
                val source = stringState(row["source"]).also { require(it.length <= SOURCE_LIMIT) { "breadcrumb source capacity reached" } }
                val content = stringState(row["content"])
                size += content.length
                require(size <= CONTENT_LIMIT) { "breadcrumb content capacity reached" }
                val gain = if (row.containsKey("gain")) row["gain"] as? Double ?: throw IllegalArgumentException("invalid breadcrumb gain") else 1.0
                val children = if (row.containsKey("children")) arrayState(row["children"]).map {
                    borg.trikeshed.agent.modelmux.BreadcrumbId(
                        stringState(it)
                    )
                } else emptyList()
                val record = borg.trikeshed.agent.modelmux.BreadcrumbRecord(
                    id,
                    source,
                    content,
                    borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(longState(row["createdAt"])),
                    borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(longState(row["lastAccessAt"])),
                    gain,
                    children
                )
                require(context.records.put(id, record) == null) { "duplicate breadcrumb record" }
                context.index.put(record)
            }
            root["index"]?.let(context.index::restoreMetadata)
            for (value in arrayState(root["pending"])) {
                val row = objectState(value, setOf("id", "presence"))
                val id = borg.trikeshed.agent.modelmux.BreadcrumbId(stringState(row["id"]))
                require(id in context.records) { "pending breadcrumb record not found" }
                require(context.pending.put(id, presenceState(row["presence"])) == null) { "duplicate pending breadcrumb" }
            }
            for (value in arrayState(root["emissions"])) {
                require(context.emissions.add(
                    borg.trikeshed.agent.modelmux.BreadcrumbId(
                        stringState(
                            value
                        )
                    )
                )) { "duplicate breadcrumb emission" }
            }
            context.submission = root["submission"]?.let { value ->
                val row = objectState(value, setOf("model", "atMs", "messageCount", "exact", "messages"))
                val count = row["messageCount"] as? Double ?: throw IllegalArgumentException("invalid breadcrumb message count")
                require(count >= 0 && count <= Int.MAX_VALUE && count == count.toInt().toDouble()) { "invalid breadcrumb message count" }
                borg.trikeshed.agent.modelmux.BreadcrumbSubmission(
                    stringState(row["model"]),
                    borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(longState(row["atMs"])),
                    arrayState(row["messages"]).map { stringState(it) }.toSeries(),
                    count.toInt(),
                    row["exact"] as? Boolean ?: throw IllegalArgumentException("invalid breadcrumb exactness")
                )
                    .also(::validateSubmission)
            }
            var receiptChars = 0L
            for (value in if ("receipts" in root) arrayState(root["receipts"]) else emptyList<Any>()) {
                val call = receiptState(value)
                receiptChars += receiptSize(call)
                require(receiptChars <= RECEIPT_CONTENT_LIMIT) { "breadcrumb receipt capacity reached" }
                val identity = Triple(call.model, call.id, call.startedAt)
                require(identity !in context.calls) { "duplicate breadcrumb receipt" }
                context.record(call)
            }
            root["horizon"]?.let { value ->
                val row = objectState(value, setOf("outcomes", "band", "bandAtMs", "lastTokens"))
                context.overflow.restore(borg.trikeshed.agent.modelmux.BreadcrumbOverflow.decode(stringState(row["outcomes"])))
                context.band = borg.trikeshed.agent.modelmux.BreadcrumbBand.entries.firstOrNull { it.wire == row["band"] }
                    ?: throw IllegalArgumentException("invalid breadcrumb band")
                context.bandAt = row["bandAtMs"]?.let {
                    borg.trikeshed.agent.modelmux.BreadcrumbTimestamp(
                        longState(it)
                    )
                }
                context.lastTokens = longState(row["lastTokens"]).also { require(it >= 0) { "invalid breadcrumb token estimate" } }
            }
            return context
        }

        private fun receiptSize(call: borg.trikeshed.agent.modelmux.MuxCallRecord): Long = call.model.length.toLong() + call.provider.length + call.status.length +
            (call.assessmentId?.length ?: 0) + (call.keyId?.length ?: 0) + (call.error?.length ?: 0) + (call.contextId?.length ?: 0)

        private fun receiptState(call: borg.trikeshed.agent.modelmux.MuxCallRecord): Map<String, Any?> = mapOf(
            "id" to call.id.toString(), "conversationId" to call.conversationId?.toString(), "turnId" to call.turnId?.toString(),
            "assessmentId" to call.assessmentId, "model" to call.model, "provider" to call.provider,
            "startedAt" to call.startedAt.toString(), "endedAt" to call.endedAt?.toString(), "status" to call.status,
            "keyId" to call.keyId, "httpStatus" to call.httpStatus, "inputTokens" to call.inputTokens,
            "outputTokens" to call.outputTokens, "cachedHit" to call.cachedHit, "cacheReadTokens" to call.cacheReadTokens,
            "cacheWriteTokens" to call.cacheWriteTokens, "error" to call.error, "contextId" to call.contextId,
        )

        private fun receiptState(value: Any?): borg.trikeshed.agent.modelmux.MuxCallRecord {
            val row = objectState(value, setOf("id", "conversationId", "turnId", "assessmentId", "model", "provider",
                "startedAt", "endedAt", "status", "keyId", "httpStatus", "inputTokens", "outputTokens", "cachedHit",
                "cacheReadTokens", "cacheWriteTokens", "error", "contextId"))
            return borg.trikeshed.agent.modelmux.MuxCallRecord(
                id = longState(row["id"]),
                conversationId = row["conversationId"]?.let(::longState),
                turnId = row["turnId"]?.let(::longState),
                assessmentId = row["assessmentId"]?.let(::stringState),
                model = stringState(row["model"]),
                provider = stringState(row["provider"]),
                startedAt = longState(row["startedAt"]),
                endedAt = row["endedAt"]?.let(::longState),
                status = stringState(row["status"]),
                keyId = row["keyId"]?.let(::stringState),
                httpStatus = row["httpStatus"]?.let(::intState),
                inputTokens = intState(row["inputTokens"]),
                outputTokens = intState(row["outputTokens"]),
                cachedHit = row["cachedHit"] as? Boolean
                    ?: throw IllegalArgumentException("invalid breadcrumb cache status"),
                cacheReadTokens = intState(row["cacheReadTokens"]),
                cacheWriteTokens = intState(row["cacheWriteTokens"]),
                error = row["error"]?.let(::stringState),
                contextId = row["contextId"]?.let(::stringState)
            )
        }

        private fun objectState(value: Any?, fields: Set<String>): Map<*, *> {
            val result = value as? Map<*, *> ?: throw IllegalArgumentException("breadcrumb state object required")
            require(result.size == fields.size && result.keys.toSet() == fields) { "invalid breadcrumb state fields" }
            return result
        }

        private fun arrayState(value: Any?): List<*> = (value as? List<*>
            ?: throw IllegalArgumentException("breadcrumb state array required")).also {
            require(it.size <= RECORD_LIMIT) { "breadcrumb record capacity reached" }
        }

        private fun stringState(value: Any?): String = value as? String
            ?: throw IllegalArgumentException("breadcrumb state string required")

        private fun longState(value: Any?): Long = stringState(value).toLongOrNull()
            ?: throw IllegalArgumentException("breadcrumb state integer required")

        private fun intState(value: Any?): Int {
            val number = value as? Double ?: throw IllegalArgumentException("breadcrumb state integer required")
            require(number >= Int.MIN_VALUE && number <= Int.MAX_VALUE && number == number.toInt().toDouble()) { "breadcrumb state integer required" }
            return number.toInt()
        }

        private fun presenceState(value: Any?): borg.trikeshed.agent.modelmux.BreadcrumbPresence =
            borg.trikeshed.agent.modelmux.BreadcrumbPresence(
                value as? Double ?: throw IllegalArgumentException("breadcrumb presence required")
            )

        private fun validateSubmission(value: borg.trikeshed.agent.modelmux.BreadcrumbSubmission) {
            require(value.model.length <= 4096 && value.messages.size <= RECORD_LIMIT && value.messageCount >= 0) { "breadcrumb submission capacity reached" }
            require(if (value.exact) value.messages.size == value.messageCount else value.messages.size == 0) { "invalid breadcrumb submission count" }
            require(value.messages.view.sumOf { it.encodeToByteArray().size.toLong() } <= SUBMISSION_LIMIT) { "breadcrumb submission capacity reached" }
            for (message in value.messages.view) {
                val row = objectState(reifyStrict(message), setOf("role", "tool_call_id", "content"))
                require(row["role"] == "tool") { "breadcrumb submission must contain tool messages" }
            }
        }
    }
}

/** The trail: root breadcrumbs in sequence order, each carrying the breadcrumbs it holds. */
fun Series<borg.trikeshed.agent.modelmux.BreadcrumbProjection>.breadcrumbJson(): String = buildString {
    append("{\"skill\":").append(jsonOf(borg.trikeshed.agent.modelmux.acp.BREADCRUMB_SKILL)).append(",\"breadcrumbs\":[")
    var first = true
    for (projection in view) if (projection.root) {
        if (!first) append(',')
        append(projection.render())
        first = false
    }
    append("]}")
}
