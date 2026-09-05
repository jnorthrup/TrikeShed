package borg.trikeshed.narsese

import borg.trikeshed.cursor.BlackboardContext
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.dag.Activation
import borg.trikeshed.dag.FactId
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProduction
import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size

/** Stanford typed-dependency LABEL SCHEMA only — no CoreNLP dependency. */
object StanfordDependency {
    const val NSUBJ = "nsubj"
    const val DOBJ = "dobj"
    const val NMOD = "nmod"
    const val ACL = "acl"
    const val ADVCL_BECAUSE = "advcl:because"
    const val MARK_IF = "mark:if"
    const val ADVCL_IF = "advcl:if"
    const val CC_THEREFORE = "cc:therefore"
    const val NEG = "neg"
}

data class CausalConstruction(
    val subject: String,
    val relation: String,
    val obj: String,
    val polarity: Boolean,
    val evidenceCid: ContentId,
    val dependency: String = StanfordDependency.NSUBJ,
)

data class ConstructionRefusal(val construction: CausalConstruction, val reason: String)

/**
 * Deterministic claim-check gate. Bots propose; this gate disposes by reading the
 * ACTUAL CAS line named by evidenceCid. Subject/object and the declared causal
 * surface must all occur in that line; otherwise the tuple is refused.
 */
object ConstructionPatternGate {
    private val phraseByRelation = mapOf(
        "causes" to listOf(" causes ", " cause ", " caused "),
        "results_in" to listOf(" results in ", " resulted in "),
        "leads_to" to listOf(" leads to ", " led to "),
        "because" to listOf(" because "),
        "therefore" to listOf(" therefore "),
    )

    fun validate(construction: CausalConstruction, cas: CasStore): ConstructionRefusal? {
        val bytes = cas.get(construction.evidenceCid)
            ?: return ConstructionRefusal(construction, "evidence CID is absent from CAS")
        return validateLine(construction, bytes.decodeToString())
    }

    fun validateLine(c: CausalConstruction, source: String): ConstructionRefusal? {
        val line = " ${source.lowercase().replace(Regex("\\s+"), " ").trim()} "
        val subject = c.subject.lowercase().trim()
        val obj = c.obj.lowercase().trim()
        if (subject.isEmpty() || subject !in line) return ConstructionRefusal(c, "subject not present in evidence line")
        if (obj.isEmpty() || obj !in line) return ConstructionRefusal(c, "object not present in evidence line")
        val causal = if (c.relation == "if_then") {
            val i = line.indexOf(" if ")
            val t = line.indexOf(" then ")
            i >= 0 && t > i
        } else {
            phraseByRelation[c.relation]?.any { it in line } == true
        }
        if (!causal) return ConstructionRefusal(c, "declared causal construction not present in evidence line")
        if (c.dependency !in setOf(
                StanfordDependency.NSUBJ, StanfordDependency.DOBJ, StanfordDependency.NMOD,
                StanfordDependency.ACL, StanfordDependency.ADVCL_BECAUSE, StanfordDependency.MARK_IF,
                StanfordDependency.ADVCL_IF, StanfordDependency.CC_THEREFORE, StanfordDependency.NEG,
            )) return ConstructionRefusal(c, "unknown Stanford dependency label")
        return null
    }
}

/** Bot seat seam: ONLY this interface may spend tokens. Everything after it is deterministic. */
fun interface ConstructionBot {
    suspend fun propose(lines: Series<ConstructionSourceLine>): Series<CausalConstruction>
}

data class ConstructionSourceLine(val cid: ContentId, val text: String)

data class ConstructionAggregate(
    val subject: String,
    val relation: String,
    val obj: String,
    val polarityRollup: Int,
    val evidence: Series<ContentId>,
) {
    val count: Int get() = evidence.size
    val identity: ContentId get() = ContentId.of("$relation\u0000$subject\u0000$obj".encodeToByteArray())
    val angular: Long get() = identity.hex.substring(0, 16).toULong(16).toLong()
}

data class ConstructionReadingReceipt(
    val accepted: Series<CausalConstruction>,
    val refused: Series<ConstructionRefusal>,
    val aggregates: Series<ConstructionAggregate>,
)

/**
 * P4 reading loop: bot proposals → CAS claim-check → bounded evidence aggregation →
 * belief revision + Rete rule registration + SUMO/KIF assertion sink.
 */
class ConstructionReadingLoop(
    private val bot: ConstructionBot,
    private val cas: CasStore,
    private val bag: BeliefBagElement,
    private val rete: ReteNetwork,
    private val kifSink: (String) -> Unit = {},
) {
    suspend fun read(lines: Series<ConstructionSourceLine>): ConstructionReadingReceipt {
        val proposals = bot.propose(lines) // the ONLY model-spend seam
        val accepted = ArrayList<CausalConstruction>()
        val refused = ArrayList<ConstructionRefusal>()
        for (i in 0 until proposals.size) {
            val p = proposals[i]
            val refusal = ConstructionPatternGate.validate(p, cas)
            if (refusal == null) accepted += p else refused += refusal
        }
        val aggregates = aggregate(accepted)
        for (a in aggregates) land(a)
        return ConstructionReadingReceipt(
            accepted.size j { i: Int -> accepted[i] },
            refused.size j { i: Int -> refused[i] },
            aggregates.size j { i: Int -> aggregates[i] },
        )
    }

    private fun aggregate(items: List<CausalConstruction>): List<ConstructionAggregate> {
        val groups = linkedMapOf<String, MutableList<CausalConstruction>>()
        for (c in items) groups.getOrPut("${c.relation}\u0000${c.subject}\u0000${c.obj}") { mutableListOf() }.add(c)
        val out = ArrayList<ConstructionAggregate>(groups.size)
        for (group in groups.values) {
            val first = group.first()
            // Evidence identity is a set: re-reading the same line revises, never duplicates.
            val cids = group.map { it.evidenceCid }.distinctBy { it.value }.sortedBy { it.value }
            out += ConstructionAggregate(
                first.subject, first.relation, first.obj,
                polarityRollup = group.sumOf { if (it.polarity) 1 else -1 },
                evidence = cids.size j { i: Int -> cids[i] },
            )
        }
        return out
    }

    private suspend fun land(a: ConstructionAggregate) {
        val positive = a.polarityRollup.coerceAtLeast(0).toLong()
        val negative = (-a.polarityRollup).coerceAtLeast(0).toLong()
        val signal = SemanticSignal(a.angular, EvidenceCoord(positive, negative), RelationKind.CAUSALITY, a.identity.value)
        val leaves = Array(a.evidence.size) { i: Int -> a.evidence[i] }
        bag.intake.send(BeliefIntake.Mint(
            signal = signal,
            budget = BudgetCoord(1f, 1f, 1f),
            receiptCid = a.identity,
            evidenceBasis = EvidenceBasis.of(*leaves),
            gloss = "${a.subject} ==> ${a.obj}",
        ))
        kifSink("(causes ${kifAtom(a.subject)} ${kifAtom(a.obj)})")
        if (a.relation == "if_then") registerRule(a)
    }

    private fun registerRule(a: ConstructionAggregate) {
        val rule = ConstructionReteProduction(a)
        if (rete.productions.all().none { it.ruleId == rule.ruleId }) rete.register(rule)
    }

    private fun kifAtom(s: String): String = s.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
}

/** `if subject then object` lowered to a shallow auditable Rete production. */
class ConstructionReteProduction(private val aggregate: ConstructionAggregate) : ReteProduction {
    override val ruleId: String = "construction:${aggregate.identity.hex}"
    override val salience: Int = 50
    override val interests: Series<Join<String, Any?>> = 1 j { _: Int -> "concept" j aggregate.subject }

    override fun evaluate(net: ReteNetwork, partitionId: String, fire: (Activation) -> Unit) {
        val facts = net.workingMemory.query(BlackboardContext(partitionId), "concept" to aggregate.subject)
        for (fact in facts) {
            fire(Activation(
                activationId = "$ruleId:${fact.versionCid.hex}",
                ruleId = ruleId,
                ruleVersionCid = aggregate.identity,
                salience = salience,
                sequence = 0L,
                supportCids = listOf(fact.versionCid) + (0 until aggregate.evidence.size).map { aggregate.evidence[it] },
                bindings = mapOf("subject" to aggregate.subject, "consequent" to aggregate.obj),
            ))
        }
    }
}
