package borg.trikeshed.kif

import borg.trikeshed.job.ContentId

/** Host review metadata; never derive APPROVED from text or guest-provided node parameters. */
enum class KifReview { UNVERIFIED, APPROVED, REJECTED }

data class KifSourceAssertion(
    val id: String,
    val sourceCid: ContentId,
    val sourceRevision: String,
    val review: KifReview,
    val text: String,
)

data class KifActivationLimits(
    val maxAssertions: Int = 256,
    val maxTotalChars: Int = 1048576,
    val maxSteps: Int = 10000,
    val maxProofs: Int = 128,
) {
    init {
        require(maxAssertions in 1..4096 && maxTotalChars in 1..4194304 &&
            maxSteps in 1..1000000 && maxProofs in 1..1024)
    }
}

data class KifActivationAssertion(
    val source: KifSourceAssertion,
    val assertionCid: ContentId,
    val decoded: KifDecoded,
    /** A retained reason, not a silently discarded formula. */
    val exclusion: String?,
)

enum class KifExplanationStatus { SUPPORTED, UNKNOWN, UNSUPPORTED, BOUNDED }

data class KifTaxonomyProof(
    val predicate: String,
    val subject: String,
    val obj: String,
    val assertionIds: List<String>,
    val ruleIds: List<String>,
)

data class KifExplanation(
    val revision: Long,
    val status: KifExplanationStatus,
    val proofs: List<KifTaxonomyProof>,
    val steps: Int,
    val truncated: Boolean,
    /** Positive derivability only; no negative inference or consistency judgment. */
    val semantics: String = "positive-ground-taxonomy-support/v1",
    val excludedAssertionIds: List<String> = emptyList(),
)

/**
 * Immutable positive ground taxonomy context. SUPPORTED means a positive derivation in this
 * fragment, not classical consistency, negative entailment, a NAL belief or human approval.
 * Negative/modal/quantified forms remain visible in [assertions] and are never executed.
 * Construct a NEW snapshot with a new revision after changes, including source review changes.
 * Host owns the source/review metadata; this API does not authenticate an arbitrary caller.
 */
class KifTaxonomySnapshot private constructor(
    val revision: Long,
    val assertions: List<KifActivationAssertion>,
    private val limits: KifActivationLimits,
) {
    private data class Edge(val from: String, val to: String, val assertion: String)
    private val subclasses = mutableMapOf<String, MutableList<Edge>>()
    private val instances = mutableMapOf<String, MutableList<Edge>>()
    private val classes = mutableMapOf<String, MutableList<String>>()

    init {
        for (entry in assertions) {
            if (entry.exclusion != null) continue
            val p = entry.decoded.formula as KifFormula.Predicate
            val a = (p.arguments[0] as KifTerm.Constant).token
            val b = (p.arguments[1] as KifTerm.Constant).token
            val edge = Edge(a, b, entry.source.id)
            if (p.name == "subclass") {
                subclasses.getOrPut(a) { mutableListOf() }.add(edge)
                classes.getOrPut(a) { mutableListOf() }.add(entry.source.id)
                classes.getOrPut(b) { mutableListOf() }.add(entry.source.id)
            } else {
                instances.getOrPut(a) { mutableListOf() }.add(edge)
                classes.getOrPut(b) { mutableListOf() }.add(entry.source.id)
            }
        }
    }

    /** Source records referenced by proof assertion IDs; never use classifier-local integer IDs. */
    fun assertion(id: String): KifActivationAssertion? = assertions.firstOrNull { it.source.id == id }

    fun explain(predicate: String, subject: String, obj: String): KifExplanation {
        require(subject.length in 1..1024 && obj.length in 1..1024) { "Taxonomy query term exceeds bound" }
        if (predicate != "subclass" && predicate != "instance")
            return KifExplanation(revision, KifExplanationStatus.UNSUPPORTED, emptyList(), 0, false,
                excludedAssertionIds = assertions.filter { it.exclusion != null }.map { it.source.id })
        data class Path(val term: String, val visited: Set<String>, val assertions: List<String>)
        val proofs = mutableListOf<KifTaxonomyProof>()
        var steps = 0
        var truncated = false
        fun emit(path: List<String>, rules: List<String>) {
            if (proofs.size == limits.maxProofs) { truncated = true; return }
            proofs.add(KifTaxonomyProof(predicate, subject, obj, path, rules))
        }
        // Reflexivity requires a known class and retains the declaration that established it.
        if (predicate == "subclass" && subject == obj) {
            for (id in classes[subject].orEmpty().distinct()) {
                if (steps == limits.maxSteps || proofs.size == limits.maxProofs) { truncated = true; break }
                steps++
                emit(listOf(id), listOf("kif-taxonomy/v1/class-reflexivity"))
            }
        } else {
            // Iterative DFS enumerates simple paths, including alternative source assertions.
            // Cyclic graphs terminate; bounds prevent exponential diamond expansion.
            val pending = mutableListOf<Path>()
            if (predicate == "subclass") pending.add(Path(subject, setOf(subject), emptyList()))
            else {
                for (edge in instances[subject].orEmpty()) {
                    if (steps == limits.maxSteps) { truncated = true; break }
                    steps++
                    pending.add(Path(edge.to, setOf(edge.to), listOf(edge.assertion)))
                }
            }
            while (pending.isNotEmpty() && !truncated) {
                if (steps == limits.maxSteps) { truncated = true; break }
                val path = pending.removeAt(pending.lastIndex)
                steps++
                if (path.term == obj) {
                    val rules = if (predicate == "instance")
                        listOf("kif-taxonomy/v1/instance-assertion") +
                            List(path.assertions.size - 1) { "kif-taxonomy/v1/instance-subclass" }
                    else listOf("kif-taxonomy/v1/subclass-assertion") +
                        List(path.assertions.size - 1) { "kif-taxonomy/v1/subclass-transitivity" }
                    emit(path.assertions, rules)
                    continue
                }
                for (edge in subclasses[path.term].orEmpty()) {
                    if (steps == limits.maxSteps) { truncated = true; break }
                    steps++
                    if (edge.to !in path.visited) pending.add(Path(edge.to,
                        path.visited + edge.to, path.assertions + edge.assertion))
                }
            }
        }
        return KifExplanation(revision, when {
            proofs.isNotEmpty() -> KifExplanationStatus.SUPPORTED
            truncated -> KifExplanationStatus.BOUNDED
            else -> KifExplanationStatus.UNKNOWN
        }, proofs.toList(), steps, truncated,
            excludedAssertionIds = assertions.filter { it.exclusion != null }.map { it.source.id })
    }

    companion object {
        /** Source records must come from the trusted host curator, not an untrusted review field. */
        fun create(revision: Long, sources: List<KifSourceAssertion>,
                   limits: KifActivationLimits = KifActivationLimits()): KifTaxonomySnapshot {
            require(revision >= 0 && sources.size <= limits.maxAssertions)
            require(sources.sumOf { it.text.length.toLong() } <= limits.maxTotalChars)
            require(sources.map { it.id }.distinct().size == sources.size) { "Duplicate assertion identity" }
            val entries = sources.map { source ->
                require(source.id.length in 1..1024 && source.sourceRevision.length in 1..1024)
                val decoded = KifSemantic.decode(source.text)
                val p = decoded.formula as? KifFormula.Predicate
                val exclusion = when {
                    source.review != KifReview.APPROVED -> "Host review is ${source.review}"
                    p == null || p.name !in setOf("subclass", "instance") -> "Outside positive ground taxonomy fragment"
                    p.arguments.size != 2 || p.arguments.any { it !is KifTerm.Constant } -> "Taxonomy requires two ground constants"
                    else -> null
                }
                KifActivationAssertion(source, ContentId.of(decoded.syntax.toKifString().encodeToByteArray()), decoded, exclusion)
            }
            return KifTaxonomySnapshot(revision, entries.toList(), limits)
        }
    }
}
