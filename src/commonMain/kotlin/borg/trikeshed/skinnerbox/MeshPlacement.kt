package borg.trikeshed.skinnerbox

import borg.trikeshed.context.nuid.Subnet
import borg.trikeshed.job.CanonicalCbor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.*

/** Nonnegative exact micro-USD. Overflow refuses the estimate; it never becomes a cheap quote. */
class MeshPrice private constructor(val numerator: Long, val denominator: Long) : Comparable<MeshPrice> {
    operator fun plus(other: MeshPrice): MeshPrice {
        if (numerator == 0L) return other
        if (other.numerator == 0L) return this
        val common = gcd(denominator, other.denominator)
        val sum = add(multiply(numerator, other.denominator / common), multiply(other.numerator, denominator / common))
        val reduce = gcd(sum, common)
        return MeshPrice(sum / reduce, multiply(denominator / common, other.denominator / reduce))
    }

    operator fun times(value: Long): MeshPrice {
        require(value >= 0)
        val reduce = gcd(value, denominator)
        return of(multiply(numerator, value / reduce), denominator / reduce)
    }

    fun dividedBy(value: Long): MeshPrice {
        require(value > 0)
        val reduce = gcd(numerator, value)
        return of(numerator / reduce, multiply(denominator, value / reduce))
    }

    fun ceilingMicroUsd(): Long = add(numerator / denominator, if (numerator % denominator == 0L) 0 else 1)

    /** Derived quote only: source retail factor 1.33, rounded half-up once. */
    fun postedMicroUsd(): Long {
        val posted = (this * 133).dividedBy(100)
        val whole = posted.numerator / posted.denominator
        val rest = posted.numerator % posted.denominator
        return add(whole, if (rest >= posted.denominator - rest) 1 else 0)
    }

    override fun compareTo(other: MeshPrice): Int {
        var a = numerator; var b = denominator
        var c = other.numerator; var d = other.denominator
        var direction = 1
        while (true) {
            val quotient = (a / b).compareTo(c / d)
            if (quotient != 0) return direction * quotient
            val ar = a % b; val cr = c % d
            if (ar == 0L || cr == 0L) return direction * ar.compareTo(cr)
            a = b; b = ar; c = d; d = cr; direction = -direction
        }
    }

    override fun equals(other: Any?): Boolean = other is MeshPrice && numerator == other.numerator && denominator == other.denominator
    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()
    override fun toString(): String = "$numerator/$denominator micro-USD"

    companion object {
        val ZERO = MeshPrice(0, 1)
        fun of(numerator: Long, denominator: Long = 1): MeshPrice {
            require(numerator >= 0 && denominator > 0) { "Invalid mesh price" }
            val reduce = gcd(numerator, denominator)
            return MeshPrice(numerator / reduce, denominator / reduce)
        }
        internal fun product(numerators: LongArray, denominators: LongArray): MeshPrice {
            require(numerators.all { it >= 0 } && denominators.all { it > 0 })
            if (numerators.any { it == 0L }) return ZERO
            val top = numerators.copyOf(); val bottom = denominators.copyOf()
            for (i in top.indices) for (j in bottom.indices) {
                val reduce = gcd(top[i], bottom[j]); top[i] /= reduce; bottom[j] /= reduce
            }
            return of(top.fold(1L, ::multiply), bottom.fold(1L, ::multiply))
        }
        private fun gcd(a: Long, b: Long): Long {
            var x = a; var y = b
            while (y != 0L) { val r = x % y; x = y; y = r }
            return x
        }
        private fun multiply(a: Long, b: Long): Long {
            require(a == 0L || b <= Long.MAX_VALUE / a) { "Mesh price arithmetic overflow" }
            return a * b
        }
        private fun add(a: Long, b: Long): Long {
            require(b <= Long.MAX_VALUE - a) { "Mesh price arithmetic overflow" }
            return a + b
        }
    }
}

data class MeshByteRate(val microUsd: Long, val bytesPerUnit: Long) {
    init { require(microUsd >= 0 && bytesPerUnit > 0) }
    fun cost(bytes: Long): MeshPrice = MeshPrice.of(microUsd, bytesPerUnit) * bytes
}

/** Linear retention tariff. Provider minimum blocks must be quoted separately, never assumed. */
data class MeshStorageRate(val microUsd: Long, val bytesPerUnit: Long, val secondsPerUnit: Long) {
    init { require(microUsd >= 0 && bytesPerUnit > 0 && secondsPerUnit > 0) }
    fun cost(bytes: Long, seconds: Long): MeshPrice =
        MeshPrice.product(longArrayOf(microUsd, bytes, seconds), longArrayOf(bytesPerUnit, secondsPerUnit))
}

data class MeshHostQuote(
    val observedAtMillis: Long,
    val validUntilMillis: Long,
    val processingMicroUsdPerUnit: Long,
    val storage: MeshStorageRate,
) {
    init { require(observedAtMillis >= 0 && validUntilMillis > observedAtMillis && processingMicroUsdPerUnit >= 0) }
    fun fresh(nowMillis: Long): Boolean = nowMillis in observedAtMillis until validUntilMillis
}

data class MeshHost(
    val peer: MeshPeer,
    val country: String,
    val twin: ContentId,
    val quote: MeshHostQuote,
    val qualified: Boolean,
) {
    init { require(country.length == 2 && country.all { it in 'A'..'Z' }) { "Host country must be an explicit two-letter code" } }

    companion object {
        /** Use only grant-authorized observations; parsing a self-declared measured source is not authority. */
        fun fromTwin(peer: MeshPeer, observation: TwinObservation): MeshHost {
            require(observation.key.kind == "host" && observation.key.key == peer.id) { "Host Twin identity mismatch" }
            val fields = observation.fields()
            fun integer(name: String): Long = (fields[name] as? Long)?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("Host Twin requires exact nonnegative $name")
            return MeshHost(peer,
                fields["country_code"] as? String ?: error("Host Twin country missing"), observation.id,
                MeshHostQuote(observation.atMs, integer("valid_until_ms"), integer("processing_micro_usd_per_unit"),
                    MeshStorageRate(integer("storage_micro_usd"), integer("storage_bytes_per_unit"), integer("storage_seconds_per_unit"))),
                fields["qualified"] as? Boolean ?: false)
        }
    }
}

data class MeshLinkQuote(
    val sourceId: String,
    val targetId: String,
    val twin: ContentId,
    val observedAtMillis: Long,
    val validUntilMillis: Long,
    val ingress: MeshByteRate,
    val egress: MeshByteRate,
) {
    init {
        MeshRouting.requireId(sourceId); MeshRouting.requireId(targetId)
        require(sourceId != targetId && observedAtMillis >= 0 && validUntilMillis > observedAtMillis)
    }
    fun fresh(nowMillis: Long): Boolean = nowMillis in observedAtMillis until validUntilMillis

    companion object {
        fun fromTwin(observation: TwinObservation): MeshLinkQuote {
            require(observation.key.kind == "transfer") { "Transfer Twin kind" }
            val fields = observation.fields()
            fun integer(name: String): Long = (fields[name] as? Long)?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("Transfer Twin requires exact nonnegative $name")
            val source = fields["source"] as? String ?: error("Transfer Twin source missing")
            val target = fields["target"] as? String ?: error("Transfer Twin target missing")
            require(observation.key.key == "$source/$target") { "Transfer Twin identity mismatch" }
            return MeshLinkQuote(source, target, observation.id, observation.atMs, integer("valid_until_ms"),
                MeshByteRate(integer("ingress_micro_usd"), integer("ingress_bytes_per_unit")),
                MeshByteRate(integer("egress_micro_usd"), integer("egress_bytes_per_unit")))
        }
    }
}

data class MeshLoad(
    val requestBytes: Long,
    val replicaBytes: Long,
    val responseBytes: Long,
    val receiptBytes: Long,
    val processingUnits: Long,
    val replicaUnits: Long,
    val retentionSeconds: Long,
) {
    init {
        require(requestBytes >= 0 && replicaBytes >= 0 && responseBytes >= 0 && receiptBytes >= 0 &&
            processingUnits >= 0 && replicaUnits >= 0 && retentionSeconds >= 0) { "Negative mesh workload" }
    }
}

class MeshPlacementPolicy(authority: Subnet, countries: Set<String>, val maxCandidates: Int = 20) {
    private val scope = meshSubnet(authority.toString()).toString()
    private val allowed = countries.toSet()
    val authority: Subnet get() = Subnet.parse(scope)
    val countries: Set<String> get() = allowed.toSet()
    init {
        require(scope.isNotEmpty() && allowed.isNotEmpty()) { "Placement authority and countries must be explicit" }
        require(allowed.all { it.length == 2 && it.all { c -> c in 'A'..'Z' } })
        require(maxCandidates in 3..MeshRouting.MAX_CANDIDATES)
    }
    fun admitsCountry(country: String): Boolean = country in allowed
}

data class MeshCost(
    val processing: MeshPrice = MeshPrice.ZERO,
    val storage: MeshPrice = MeshPrice.ZERO,
    val ingress: MeshPrice = MeshPrice.ZERO,
    val egress: MeshPrice = MeshPrice.ZERO,
) {
    val total: MeshPrice get() = processing + storage + ingress + egress
    operator fun plus(other: MeshCost): MeshCost = MeshCost(processing + other.processing, storage + other.storage,
        ingress + other.ingress, egress + other.egress)
}

enum class MeshTransferKind { REQUEST, RETAIN, RECEIPT, RESPONSE }
data class MeshTransfer(val kind: MeshTransferKind, val sourceId: String, val targetId: String, val bytes: Long,
                        val quote: MeshLinkQuote?, val cost: MeshCost)
enum class MeshExclusion { UNQUALIFIED, COUNTRY, STALE, SCOPE, CANDIDATE_BOUND }
data class MeshExcludedHost(val id: String, val reason: MeshExclusion)

/** A plan must be pinned by the namespace owner. Re-running selection per row is not writer election. */
class MeshPlan internal constructor(
    val executor: MeshHost,
    val replicas: Twin<MeshHost>,
    val cost: MeshCost,
    val transfers: Series<MeshTransfer>,
    val candidates: Series<String>,
    val excluded: Series<MeshExcludedHost>,
    val evaluatedPlans: Int,
    val unpricedPlans: Int,
    val selectedAtMillis: Long,
    val validUntilMillis: Long,
    decision: ByteArray = byteArrayOf(),
) {
    private val decision = decision.copyOf()
    val members: Series<MeshHost> get() = s_[executor, replicas.a, replicas.b]
    fun fresh(nowMillis: Long): Boolean = nowMillis in selectedAtMillis until validUntilMillis
    /** Exact inputs to the bounded optimization, captured before a composing owner pins this cell. */
    fun decisionBytes(): ByteArray = decision.copyOf()
}

object MeshPlacement {
    const val MAX_DECISION_BYTES = 64 * 1024
    /** Exact minimum over the authorized bounded candidate set and available fresh linear tariffs. */
    fun select(
        task: MeshTask,
        source: MeshPeer,
        hosts: Series<MeshHost>,
        links: Series<MeshLinkQuote>,
        policy: MeshPlacementPolicy,
        load: MeshLoad,
        nowMillis: Long,
    ): MeshPlan {
        require(nowMillis >= 0 && hosts.size in 0..MeshRouting.MAX_PEERS && links.size in 0..4096)
        val namespace = Subnet.parse(task.namespace)
        require(MeshRouting.contains(source.subnet, namespace) && MeshRouting.contains(policy.authority, namespace)) {
            "Source lacks namespace authority"
        }
        val byId = mutableMapOf<String, MeshHost>()
        val admitted = mutableListOf<MeshHost>()
        val excluded = mutableListOf<MeshExcludedHost>()
        for (host in hosts.view) {
            require(byId.put(host.peer.id, host) == null) { "Duplicate host identity" }
            if (host.peer.id == source.id) require(host.peer.origin == source.origin &&
                host.peer.failureDomain == source.failureDomain && host.peer.subnet.toString() == source.subnet.toString()) {
                "Source identity has conflicting placement metadata"
            }
            val reason = when {
                !host.qualified -> MeshExclusion.UNQUALIFIED
                !policy.admitsCountry(host.country) -> MeshExclusion.COUNTRY
                !host.quote.fresh(nowMillis) -> MeshExclusion.STALE
                !MeshRouting.contains(policy.authority, host.peer.subnet) ||
                    !MeshRouting.contains(host.peer.subnet, namespace) -> MeshExclusion.SCOPE
                else -> null
            }
            if (reason == null) admitted.add(host) else excluded.add(MeshExcludedHost(host.peer.id, reason))
        }
        val nearby = MeshRouting.closest(task.id, namespace, policy.authority,
            admitted.size j { admitted[it].peer }, policy.maxCandidates)
        val chosen = nearby α { byId.getValue(it.id) }
        val chosenIds = nearby.view.mapTo(mutableSetOf()) { it.id }
        for (host in admitted) if (host.peer.id !in chosenIds) excluded.add(MeshExcludedHost(host.peer.id, MeshExclusion.CANDIDATE_BOUND))
        require(chosen.size >= 3) { "Placement requires three qualified authorized hosts" }

        // A Kotlin Pair supplies value equality for the local lookup; Join does not promise it.
        val indexedPrices = mutableMapOf<Pair<String, String>, MeshLinkQuote>()
        for (link in links.view) {
            require(indexedPrices.put(link.sourceId to link.targetId, link) == null) { "Ambiguous directed transfer quote" }
        }
        fun transfer(kind: MeshTransferKind, from: String, to: String, bytes: Long): MeshTransfer? {
            if (from == to) return MeshTransfer(kind, from, to, 0, null, MeshCost())
            val quote = indexedPrices[from to to]?.takeIf { it.fresh(nowMillis) } ?: return null
            return MeshTransfer(kind, from, to, bytes, quote,
                MeshCost(ingress = quote.ingress.cost(bytes), egress = quote.egress.cost(bytes)))
        }

        var best: MeshPlan? = null
        var bestKey: String? = null
        var evaluated = 0
        var unpriced = 0
        for (i in 0 until chosen.size) for (j in 0 until chosen.size) for (k in j + 1 until chosen.size) {
            if (i == j || i == k) continue
            val executor = chosen[i]
            val a = chosen[j]; val b = chosen[k]
            if (executor.peer.failureDomain == a.peer.failureDomain || executor.peer.failureDomain == b.peer.failureDomain ||
                a.peer.failureDomain == b.peer.failureDomain) continue
            val ordered = if (a.peer.id < b.peer.id) a j b else b j a
            val transfers = arrayOf(
                transfer(MeshTransferKind.REQUEST, source.id, executor.peer.id, load.requestBytes),
                transfer(MeshTransferKind.RETAIN, executor.peer.id, ordered.a.peer.id, load.replicaBytes),
                transfer(MeshTransferKind.RETAIN, executor.peer.id, ordered.b.peer.id, load.replicaBytes),
                transfer(MeshTransferKind.RECEIPT, ordered.a.peer.id, executor.peer.id, load.receiptBytes),
                transfer(MeshTransferKind.RECEIPT, ordered.b.peer.id, executor.peer.id, load.receiptBytes),
                transfer(MeshTransferKind.RESPONSE, executor.peer.id, source.id, load.responseBytes),
            )
            if (transfers.any { it == null }) { unpriced++; continue }
            evaluated++
            var cost = MeshCost(
                processing = MeshPrice.of(executor.quote.processingMicroUsdPerUnit) * load.processingUnits +
                    MeshPrice.of(a.quote.processingMicroUsdPerUnit) * load.replicaUnits +
                    MeshPrice.of(b.quote.processingMicroUsdPerUnit) * load.replicaUnits,
                storage = executor.quote.storage.cost(load.replicaBytes, load.retentionSeconds) +
                    a.quote.storage.cost(load.replicaBytes, load.retentionSeconds) +
                    b.quote.storage.cost(load.replicaBytes, load.retentionSeconds),
            )
            var expires = minOf(executor.quote.validUntilMillis, a.quote.validUntilMillis, b.quote.validUntilMillis)
            for (edge in transfers) {
                cost += edge!!.cost
                edge.quote?.let { expires = minOf(expires, it.validUntilMillis) }
            }
            val key = executor.peer.id + ordered.a.peer.id + ordered.b.peer.id
            val previous = best
            if (previous == null || cost.total < previous.cost.total || cost.total == previous.cost.total && key < bestKey!!) {
                best = MeshPlan(executor, ordered, cost, transfers.size j { transfers[it]!! },
                    nearby α { it.id }, excluded.toTypedArray().let { array -> array.size j { array[it] } },
                    0, 0, nowMillis, expires)
                bestKey = key
            }
        }
        val winner = requireNotNull(best) { "No fresh priced plan with three distinct failure domains" }
        val decision = decision(task, source, chosen, indexedPrices.values, byId, winner, policy, load, evaluated, unpriced)
        return MeshPlan(winner.executor, winner.replicas, winner.cost, winner.transfers, winner.candidates, winner.excluded,
            evaluated, unpriced, winner.selectedAtMillis, winner.validUntilMillis, decision)
    }

    private fun decision(task: MeshTask, source: MeshPeer, candidates: Series<MeshHost>, links: Collection<MeshLinkQuote>,
                         hosts: Map<String, MeshHost>, plan: MeshPlan, policy: MeshPlacementPolicy, load: MeshLoad,
                         evaluated: Int, unpriced: Int): ByteArray {
        fun digest(id: ContentId): ByteArray = ByteArray(32) { id.hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun peer(peer: MeshPeer): List<Any?> = listOf(peer.publicKey, peer.failureDomain, peer.origin, peer.subnet.toString())
        fun price(value: MeshPrice): List<Any?> = listOf(value.numerator, value.denominator)
        val indices = mutableMapOf(source.id to 0L)
        for (i in 0 until candidates.size) indices.getOrPut(candidates[i].peer.id) { i.toLong() + 1 }
        val relevant = links.filter { it.sourceId in indices && it.targetId in indices }
            .sortedWith(compareBy<MeshLinkQuote> { it.sourceId }.thenBy { it.targetId })
        val bytes = CanonicalCbor.encodeMap(mapOf(
            "format" to "skinnerbox-placement/v1",
            "task" to listOf(task.namespace, task.verb, digest(task.id)),
            "source" to peer(source),
            "hosts" to candidates.view.map { host -> listOf(peer(host.peer), host.country, digest(host.twin),
                host.qualified, host.quote.observedAtMillis, host.quote.validUntilMillis,
                host.quote.processingMicroUsdPerUnit, host.quote.storage.microUsd,
                host.quote.storage.bytesPerUnit, host.quote.storage.secondsPerUnit) },
            "links" to relevant.map { quote -> listOf(indices.getValue(quote.sourceId), indices.getValue(quote.targetId),
                digest(quote.twin), quote.observedAtMillis, quote.validUntilMillis,
                quote.ingress.microUsd, quote.ingress.bytesPerUnit, quote.egress.microUsd, quote.egress.bytesPerUnit) },
            "policy" to listOf(policy.authority.toString(), policy.countries.sorted(), policy.maxCandidates.toLong(),
                "subnet-embedding:2,7,16,32,64;local-depth=1"),
            "load" to listOf(load.requestBytes, load.replicaBytes, load.responseBytes, load.receiptBytes,
                load.processingUnits, load.replicaUnits, load.retentionSeconds),
            "selected" to plan.members.view.map { indices.getValue(it.peer.id) },
            "cost" to listOf(price(plan.cost.processing), price(plan.cost.storage), price(plan.cost.ingress), price(plan.cost.egress)),
            "selectedAt" to plan.selectedAtMillis, "validUntil" to plan.validUntilMillis,
            "evaluated" to evaluated.toLong(), "unpriced" to unpriced.toLong(),
            "excluded" to plan.excluded.view.sortedBy { it.id }.map { listOf(digest(ContentId("sha256:${it.id}")),
                it.reason.name, digest(hosts.getValue(it.id).twin)) },
            "tie" to "exact-total/executor-id/sorted-replica-ids",
        ))
        require(bytes.size <= MAX_DECISION_BYTES) { "Placement decision exceeds $MAX_DECISION_BYTES bytes; reduce candidate scope" }
        return bytes
    }
}
