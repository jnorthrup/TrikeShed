package borg.trikeshed.hazelnut

// ── NARS-3 Semantic Taxonomy: Garden Collection (gk) + Spokes ─────────────

/**
 * Garden collection domains — the top-level operational taxonomy.
 * Each gk identifies a concern domain with associated confidence dimensions.
 */
enum class Nars3GardenDomain(
    val gk: CharSequence,
    val description: CharSequence,
) {
    REASONING("gk:reasoning", "Confidence in local decision outcomes"),
    CONFLICT("gk:conflict", "Split-brain merge correctness and convergence"),
    NETWORK("gk:network", "Transport reliability across SCP/QUIC/HTX/IPFS spokes"),
    DATA("gk:data", "Distributed object state consistency across replicas"),
    NODE("gk:node", "Individual node operational health"),
    TOPOLOGY("gk:topology", "Cluster graph structure correctness"),
    PROTOCOL("gk:protocol", "Spoke protocol compliance and interop"),
    TEMPORAL("gk:temporal", "Timeseries metric consistency and anomaly detection"),
    OPS("gk:ops", "Composite system-wide operational readiness"),
    DEBUG("gk:debug", "Diagnostic capability for incident resolution"),
}

/**
 * Spoke bindings — transport/protocol extension points.
 * Each spoke maps to a measurable dimension in the NARS-3 corpus.
 */
enum class Nars3Spoke(
    val spoke: CharSequence,
    val unit: CharSequence,
    val lowerIsBetter: Boolean,
) {
    REASONING("spoke:reasoning", "prob", false),
    PIJUL("spoke:pijul", "ratio", false),
    CONCURRENCY("spoke:concurrency", "ticks", true),
    SCTP("spoke:sctp", "ms", true),
    QUIC("spoke:quic", "mbps", false),
    HTX("spoke:htx", "ratio", true),
    IPFS("spoke:ipfs", "seconds", false),
    CRDT("spoke:crdt", "ratio", false),
    COUNTER("spoke:counter", "delta", true),
    REGISTER("spoke:register", "agreement", false),
    NODE("spoke:node", "ms", true),
    URING("spoke:uring", "ms", true),
    TOPOLOGY("spoke:topology", "ratio", false),
    GRAPH("spoke:graph", "entropy", false),
    TRANSPORT("spoke:transport", "ratio", false),
    TIMESERIES("spoke:timeseries", "z_score", true),
    OPS("spoke:ops", "ratio", false),
    DEBUG("spoke:debug", "steps", true),
}

/**
 * Confidence dimension — a measurable NARS-3 metric bound to a spoke.
 * Used to compose composite confidence scores for each garden domain.
 */
data class Nars3ConfidenceDimension(
    val gardenDomain: Nars3GardenDomain,
    val spoke: Nars3Spoke,
    val name: CharSequence,
    val minValue: Double,
    val maxValue: Double,
    val idealValue: Double,
)

object Nars3Dimensions {
    val all = listOf(
        Nars3ConfidenceDimension(Nars3GardenDomain.REASONING, Nars3Spoke.REASONING, "truthProbability", 0.0, 1.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.REASONING, Nars3Spoke.REASONING, "evidenceDensity", 0.0, 1.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.REASONING, Nars3Spoke.TIMESERIES, "temporalFreshness", 0.0, 3600000.0, 0.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.CONFLICT, Nars3Spoke.PIJUL, "patchConvergence", 0.0, 1.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.CONFLICT, Nars3Spoke.PIJUL, "causalLineage", 0.0, 100.0, 5.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.CONFLICT, Nars3Spoke.CONCURRENCY, "clockDivergence", 0.0, 1000.0, 0.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.NETWORK, Nars3Spoke.SCTP, "latencyStability", 0.0, 5000.0, 0.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.NETWORK, Nars3Spoke.HTX, "packetLossRate", 0.0, 1.0, 0.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.NETWORK, Nars3Spoke.IPFS, "protocolUptime", 0.0, 31536000.0, 31536000.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.DATA, Nars3Spoke.CRDT, "replicaConvergence", 0.0, 1.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.DATA, Nars3Spoke.COUNTER, "counterConsistency", 0.0, 1000.0, 0.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.DATA, Nars3Spoke.REGISTER, "registerConsensus", 0.0, 1.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.NODE, Nars3Spoke.NODE, "heartbeatRegularity", 0.0, 60000.0, 5000.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.NODE, Nars3Spoke.NODE, "memoryPressure", 0.0, 1.0, 0.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.NODE, Nars3Spoke.URING, "diskIoLatency", 0.0, 100.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.TOPOLOGY, Nars3Spoke.TOPOLOGY, "partitionCoverage", 0.0, 1.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.TOPOLOGY, Nars3Spoke.GRAPH, "edgeConnectivity", 1.0, 10.0, 3.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.PROTOCOL, Nars3Spoke.TRANSPORT, "specCompliance", 0.0, 1.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.PROTOCOL, Nars3Spoke.TRANSPORT, "handshakeSuccess", 0.0, 1.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.TEMPORAL, Nars3Spoke.TIMESERIES, "trendStability", 0.0, 1.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.TEMPORAL, Nars3Spoke.TIMESERIES, "anomalyScore", 0.0, 10.0, 0.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.OPS, Nars3Spoke.OPS, "systemReadiness", 0.0, 1.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.OPS, Nars3Spoke.OPS, "failureRecovery", 0.0, 3600.0, 30.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.DEBUG, Nars3Spoke.DEBUG, "rootCauseClarity", 0.0, 1.0, 1.0),
        Nars3ConfidenceDimension(Nars3GardenDomain.DEBUG, Nars3Spoke.DEBUG, "remediationSteps", 0.0, 10.0, 1.0),
    )
}

/**
 * NARS-3 Semantic Term — a descriptive operational observation.
 * Builds the corpus for production confidence scoring.
 */
data class Nars3SemanticTerm(
    val gardenDomain: Nars3GardenDomain,
    val dimension: Nars3ConfidenceDimension,
    val observation: CharSequence,
    val value: Double,
    val timestamp: Long = kotlin.js.Date.now().toLong(),
    val context: Map<CharSequence, CharSequence> = emptyMap(),
    val severity: Nars3Severity = Nars3Severity.INFO,
)

enum class Nars3Severity { DEBUG, INFO, WARNING, CRITICAL, OUTAGE }

/**
 * Confidence normalization for a single term.
 */
fun Nars3SemanticTerm.normalizedName(): CharSequence =
    "${gardenDomain.gk}:${dimension.spoke.spoke}:${dimension.name}"

fun Nars3SemanticTerm.computeConfidence(): Double {
    val d = dimension
    val range = d.maxValue - d.minValue
    if (range <= 0.0) return 1.0
    val normalized = ((value - d.minValue) / range).coerceIn(0.0, 1.0)
    return if (d.spoke.lowerIsBetter) 1.0 - normalized else normalized
}

/**
 * Feature vector — composite representation of a node's operational state.
 */
data class Nars3FeatureVector(
    val nodeId: CharSequence,
    val terms: List<Nars3SemanticTerm>,
    val vectorTimestamp: Long = kotlin.js.Date.now().toLong(),
) {
    val compositeConfidence: Double
        get() = if (terms.isEmpty()) 0.0 else terms.map { it.computeConfidence() }.average()

    fun byDomain(domain: Nars3GardenDomain): List<Nars3SemanticTerm> =
        terms.filter { it.gardenDomain == domain }

    fun bySpoke(spoke: Nars3Spoke): List<Nars3SemanticTerm> =
        terms.filter { it.dimension.spoke == spoke }

    fun anomalies(threshold: Double = 0.5): List<Nars3SemanticTerm> =
        terms.filter { it.computeConfidence() < threshold }

    fun recommendations(): List<CharSequence> = anomalies(0.6).map { term ->
        val conf = "${term.computeConfidence().toString().take(4)}"
        when (term.severity) {
            Nars3Severity.CRITICAL, Nars3Severity.OUTAGE ->
                "CRITICAL [${conf}]: ${term.dimension.name} on ${term.dimension.spoke.spoke}. " +
                "value=${term.value}, ideal=${term.dimension.idealValue}. ${term.observation}"
            Nars3Severity.WARNING ->
                "WARN [${conf}]: ${term.dimension.name} on ${term.dimension.spoke.spoke}. " +
                "value=${term.value}. ${term.observation}"
            else ->
                "INFO: ${term.dimension.name} = ${term.value}"
        }
    }
}

/**
 * NARS-3 Corpus — rolling window of feature vectors forming a knowledge base
 * for operational confidence and troubleshooting support.
 */
class Nars3Corpus(
    val windowMs: Long = 3600000,
    private val _entries: List<Nars3FeatureVector> = buildList {},
) {
    val size: Int get() = _entries.size

    fun ingest(vector: Nars3FeatureVector) {
        _entries.add(vector)
        prune()
    }

    fun confidenceFor(domain: Nars3GardenDomain, cutoff: Long = kotlin.js.Date.now().toLong() - windowMs): Double {
        val terms = _entries.filter { it.vectorTimestamp >= cutoff }
            .flatMap { it.byDomain(domain) }
        return if (terms.isEmpty()) 0.0 else terms.map { it.computeConfidence() }.average()
    }

    fun confidenceForSpoke(spoke: Nars3Spoke, cutoff: Long = kotlin.js.Date.now().toLong() - windowMs): Double {
        val terms = _entries.filter { it.vectorTimestamp >= cutoff }
            .flatMap { it.bySpoke(spoke) }
        return if (terms.isEmpty()) 0.0 else terms.map { it.computeConfidence() }.average()
    }

    fun systemConfidence(cutoff: Long = kotlin.js.Date.now().toLong() - windowMs): Double {
        val recent = _entries.filter { it.vectorTimestamp >= cutoff }
        return if (recent.isEmpty()) 0.0 else recent.map { it.compositeConfidence }.average()
    }

    fun allAnomalies(): List<Nars3SemanticTerm> = _entries.flatMap { it.anomalies() }

    fun allRecommendations(): List<CharSequence> = _entries.flatMap { it.recommendations() }

    private fun prune() {
        val cutoff = kotlin.js.Date.now().toLong() - windowMs
        _entries.removeAll { it.vectorTimestamp < cutoff }
    }
}

/**
 * NARS-3 Production Runtime — integrates with the existing hazelnut cluster.
 * Refreshes the corpus from NARS node profiles and cluster topology state.
 */
class Nars3Runtime(
    private val profiles: LinkedHashMap<CharSequence, NarsNodeProfile>,
    private val topology: HazelTopology,
    private val analytics: ConflictAnalytics,
    val corpus: Nars3Corpus = Nars3Corpus(),
) {
    fun refreshConfidence() {
        for ((nodeId, profile) in profiles) {
            fun t(domain: Nars3GardenDomain, dim: Nars3ConfidenceDimension, value: Double, observation: CharSequence): Nars3SemanticTerm =
                Nars3SemanticTerm(
                    gardenDomain = domain,
                    dimension = dim,
                    observation = observation,
                    value = value,
                    context = mapOf("nodeId" to nodeId),
                )

            val nodeTerms = listOf(
                t(Nars3GardenDomain.NODE, Nars3Dimensions.all[12], // heartbeat
                    profile.residenceTimeMs.toDouble() / (profile.conflictCount + 1),
                    "Node $nodeId heartbeat interval"),
                t(Nars3GardenDomain.NODE, Nars3Dimensions.all[13], // memory/conflict
                    profile.conflictCount.toDouble() / (profile.conflictResolved + 1),
                    "Node $nodeId conflict load"),
                t(Nars3GardenDomain.CONFLICT, Nars3Dimensions.all[3], // patch convergence
                    if (profile.conflictCount > 0) profile.conflictResolved.toDouble() / profile.conflictCount else 1.0,
                    "Node $nodeId resolution rate"),
                t(Nars3GardenDomain.NETWORK, Nars3Dimensions.all[6], // latency
                    profile.residenceTimeMs.toDouble().coerceAtMost(5000.0),
                    "Transport ${profile.transport.scheme} on $nodeId"),
                t(Nars3GardenDomain.REASONING, Nars3Dimensions.all[1], // evidence
                    profile.reliabilityHistory.takeLast(10).average(),
                    "Node $nodeId reliability"),
            )

            corpus.ingest(Nars3FeatureVector(nodeId.toString(), nodeTerms))
        }
    }

    fun readinessReport(): Map<CharSequence, Double> {
        refreshConfidence()
        return Nars3GardenDomain.values().associate { it.gk to corpus.confidenceFor(it) }
    }

    fun recommendations() = corpus.allRecommendations()
    fun score() = corpus.systemConfidence()
}
