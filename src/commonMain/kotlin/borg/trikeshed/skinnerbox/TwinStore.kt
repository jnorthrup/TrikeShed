package borg.trikeshed.skinnerbox

import borg.trikeshed.cas.LineCas
import borg.trikeshed.cas.LineNode
import borg.trikeshed.cas.LineSpine
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

class TwinPage(val savepoint: ContentId, val observations: Series<TwinObservation>, val next: ContentId?)
data class TwinAdmission(val added: Int, val savepoint: ContentId)
enum class TwinReferenceFailure { MISSING, MALFORMED, UNMEASURED_REVISION }
data class TwinReferenceIssue(val owner: TwinKey, val target: TwinKey?, val failure: TwinReferenceFailure)
data class TwinReferenceCursor(val owner: TwinKey, val offset: Int)
class TwinReferencePage(val savepoint: ContentId, val issues: Series<TwinReferenceIssue>, val next: TwinReferenceCursor?)

/** Single-owner CRDT state. The reactor persists a batch before admitting it here; this class does no I/O. */
class TwinStore(val maxObservations: Int = 8192, val maxBytes: Long = 16L * 1024 * 1024) {
    private val held = mutableMapOf<ContentId, TwinObservation>()
    private val winners = mutableMapOf<TwinKey, TwinObservation>()
    private var retainedBytes = 0L
    private var cachedSpine: LineSpine? = null
    private var cachedSavepoint: ContentId? = null
    val size: Int get() = held.size
    val bytes: Long get() = retainedBytes

    init { require(maxObservations in 1..1_048_576 && maxBytes > 0) { "twin store bounds" } }
    fun get(key: TwinKey): TwinObservation? = winners[key]
    fun contains(id: ContentId): Boolean = id in held
    /** Bounded candidate selection for one derived coordinate; observation fields are parsed only after this cap. */
    internal fun select(kind: String, limit: Int = 64, accepts: (TwinKey) -> Boolean): Series<TwinObservation> {
        require(limit in 1..64)
        val selected = mutableListOf<TwinObservation>()
        for ((key, observation) in winners) if (key.kind == kind && accepts(key)) {
            require(selected.size < limit) { "Twin projection candidate bound" }
            selected.add(observation)
        }
        return selected.sortedBy { it.key }.toSeries()
    }
    fun insert(observation: TwinObservation, grant: TwinGrant): Int = merge(s_[observation], grant)

    /** Validate a batch without changing the view, allowing persistence admission to precede publication. */
    fun validate(observations: Series<TwinObservation>, grant: TwinGrant) { additions(observations, grant) }

    /** Predicted result recorded with the durable batch, so retry replies never depend on subsequent merges. */
    fun preview(observations: Series<TwinObservation>, grant: TwinGrant): TwinAdmission {
        val staged = additions(observations, grant)
        if (staged.isEmpty()) return TwinAdmission(0, savepoint())
        val merged = (held.values + staged.values).sortedWith(order).toSeries()
        return TwinAdmission(staged.size, LineCas.spineCid(spineOf(merged)))
    }

    fun merge(observations: Series<TwinObservation>, grant: TwinGrant): Int {
        val staged = additions(observations, grant)
        for (observation in staged.values) {
            held[observation.id] = observation
            retainedBytes += observation.byteSize
            val prior = winners[observation.key]
            if (prior == null || observation.compareRank(prior) > 0) winners[observation.key] = observation
        }
        if (staged.isNotEmpty()) { cachedSpine = null; cachedSavepoint = null }
        return staged.size
    }

    private fun additions(observations: Series<TwinObservation>, grant: TwinGrant): Map<ContentId, TwinObservation> {
        require(observations.size in 0..maxObservations) { "twin admission batch bound" }
        val staged = mutableMapOf<ContentId, TwinObservation>()
        var bytes = retainedBytes
        for (observation in observations.view) {
            grant.authorize(observation)
            if (observation.id in held || observation.id in staged) continue
            require(held.size.toLong() + staged.size + 1 <= maxObservations && observation.byteSize <= maxBytes - bytes) { "twin store capacity" }
            staged[observation.id] = observation
            bytes += observation.byteSize
        }
        return staged
    }

    /** Snapshot in the old spine order; subsequent admissions do not change this Series. */
    fun observations(): Series<TwinObservation> = held.values.sortedWith(order).toSeries()

    private val order = compareBy<TwinObservation>({ it.key.kind }, { it.key.key }, { it.atMs }, { it.id.value })

    fun spine(): LineSpine {
        cachedSpine?.let { return it }
        return spineOf(observations()).also { cachedSpine = it }
    }

    private fun spineOf(observations: Series<TwinObservation>): LineSpine {
        val nodes = Array(observations.size) { i ->
            LineNode(observations[i].id, LineCas.stamp(
                if (i == 0) null else observations[i - 1].id,
                if (i + 1 == observations.size) null else observations[i + 1].id,
            ), i)
        }
        return nodes.size j { i: Int -> nodes[i] }
    }

    fun savepoint(): ContentId = cachedSavepoint ?: LineCas.spineCid(spine()).also { cachedSavepoint = it }

    /** Content residuals in CID order. Pin [expectedSavepoint] across pages or restart after a changed view. */
    fun residual(known: Series<ContentId>, limit: Int = 64, maxPageBytes: Int = 64 * 1024,
        after: ContentId? = null, expectedSavepoint: ContentId? = null): TwinPage {
        require(limit in 1..maxObservations && maxPageBytes >= TwinObservation.MAX_BYTES) { "twin residual page bounds" }
        require(known.size in 0..maxObservations) { "twin residual inventory bound" }
        val savepoint = savepoint()
        require(expectedSavepoint == null || expectedSavepoint == savepoint) { "twin residual savepoint changed" }
        val present = known.view.toSet()
        val pending = held.values.asSequence().filter { it.id !in present && (after == null || it.id.value > after.value) }.sortedBy { it.id.value }.iterator()
        val page = mutableListOf<TwinObservation>()
        var bytes = 0
        var more = false
        while (pending.hasNext()) {
            val observation = pending.next()
            if (page.size == limit || observation.byteSize > maxPageBytes - bytes) { more = true; break }
            page.add(observation)
            bytes += observation.byteSize
        }
        return TwinPage(savepoint, page.toSeries(), if (more) page.last().id else null)
    }

    /** Relationships are a derived diagnostic: incomplete arrival order never deletes a valid observation. */
    fun referenceIssues(): Series<TwinReferenceIssue> = winners.values.sortedBy { it.key }
        .flatMap { referenceIssues(it) }.toSeries()

    /** Inspect at most [maxOwners] bounded observations and return at most [limit] diagnostics. */
    fun referencePage(limit: Int = 64, maxOwners: Int = 64, after: TwinReferenceCursor? = null,
        expectedSavepoint: ContentId? = null): TwinReferencePage {
        require(limit in 1..64 && maxOwners in 1..64) { "twin reference page bounds" }
        val savepoint = savepoint()
        require(expectedSavepoint == null || expectedSavepoint == savepoint) { "twin reference savepoint changed" }
        require(after == null || expectedSavepoint != null) { "twin reference continuation requires savepoint" }
        val owners = winners.keys.sorted()
        var index = if (after == null) 0 else owners.indexOf(after.owner).also { require(it >= 0) { "twin reference cursor owner" } }
        var scanned = 0
        val issues = mutableListOf<TwinReferenceIssue>()
        while (index < owners.size && scanned < maxOwners) {
            val owner = owners[index]
            val found = referenceIssues(winners.getValue(owner))
            var offset = if (after != null && owner == after.owner) after.offset else 0
            require(offset in 0..found.size) { "twin reference cursor offset" }
            while (offset < found.size) {
                if (issues.size == limit) return TwinReferencePage(savepoint, issues.toSeries(), TwinReferenceCursor(owner, offset))
                issues.add(found[offset++])
            }
            index++
            scanned++
            if (issues.size == limit) break
        }
        val next = if (index < owners.size) TwinReferenceCursor(owners[index], 0) else null
        return TwinReferencePage(savepoint, issues.toSeries(), next)
    }

    private fun referenceIssues(observation: TwinObservation): List<TwinReferenceIssue> {
        val issues = mutableListOf<TwinReferenceIssue>()
        val key = observation.key
        val fields = observation.fields()
        fun malformed() { issues.add(TwinReferenceIssue(key, null, TwinReferenceFailure.MALFORMED)) }
        fun reference(kind: String, target: String) {
            val reference = runCatching { TwinKey(kind, target) }.getOrElse { malformed(); return }
            if (reference !in winners) issues.add(TwinReferenceIssue(key, reference, TwinReferenceFailure.MISSING))
        }
        fun field(name: String, kind: String, prefix: String) {
            val value = fields[name] as? String
            if (value.isNullOrEmpty()) malformed() else reference(kind, prefix + value)
        }
        when (key.kind) {
            "model" -> if (!key.key.startsWith("family/")) field("family", "model", "family/")
            "quant" -> {
                val model = key.key.substringBefore('/', "")
                if (model.isEmpty() || key.key.endsWith('/')) malformed() else reference("model", model)
            }
            "hardware" -> if (key.key.startsWith("revision/")) {
                val suffix = key.key.removePrefix("revision/")
                val cls = suffix.substringBefore('/', "")
                if (cls.isEmpty() || suffix.endsWith('/')) malformed() else reference("hardware", "class/$cls")
                if (observation.source != TwinSource.MEASURED) issues.add(TwinReferenceIssue(key, null, TwinReferenceFailure.UNMEASURED_REVISION))
            }
            "rental" -> {
                val parts = key.key.split('/', limit = 3)
                if (parts.size != 3 || parts.any { it.isEmpty() }) malformed() else reference("hardware", "class/${parts[1]}")
                field("country_code", "region", "country/")
            }
            "region" -> if (key.key.startsWith("country/")) field("continent", "region", "continent/")
        }
        if ("references" in fields) {
            val raw = fields["references"]
            if (raw !is List<*>) malformed()
            else for (entry in raw) {
                val target = entry as? Map<*, *>
                val kind = target?.get("kind") as? String
                val name = target?.get("key") as? String
                if (kind == null || name == null || target.keys != setOf("kind", "key")) malformed() else reference(kind, name)
            }
        }
        return issues
    }
}
