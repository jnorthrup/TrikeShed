package borg.trikeshed.narsese

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.Series2
import borg.trikeshed.lib.Twin
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.coroutines.CoroutineContext

/**
 * CausalityReteElement — the LIVE rete over the daemon's BeliefBag.
 *
 * The pure [CausalityRete] is data + folds; this element is the CCEK owner
 * that makes it run against the bag AS IT EXISTS:
 *
 *  1. **Term registry.** Bag signals carry CIDs, not terms, and the rete never
 *     fabricates term identity. [register] records `angular → (subject, obj)`
 *     for signals the caller minted with known terms; [fireLive] projects the
 *     snapshot through the registry and SKIPS unregistered signals — honesty
 *     over coverage.
 *  2. **Live fire.** [fireLive] snapshots the bag, projects to [ReteAssertion]s,
 *     fires the rete, and mints each firing back into the bag at a DISCOUNTED
 *     budget (attention haircut matching the evidence discount). The bag's own
 *     stochastic roulette decides whether the potential lands — the element
 *     proposes, never forces.
 *  3. **Eternal only.** The rete admits atemporal `==>`/`<=>` rules; temporal
 *     truths stay in the bag's ordinary signal flow.
 *
 * DRAINING semantics mirror TurnReviewElement: in-flight intakes complete via
 * the bag's serial channel; nothing is hard-cancelled.
 */
class CausalityReteElement(
    private val bag: BeliefBagElement,
    rules: Series<EternalRule>,
    /** Weak-rule evidence haircut offered by each firing. */
    discount: Float = 0.5f,
    /** Minimum-understanding floor in milli-evidence. */
    minSupport: Long = Nal.UNIT / 4,
    /** Attention budget for minted consequents (discounted to match evidence). */
    private val mintBudget: BudgetCoord = BudgetCoord(0.5f, 0.4f, 0.5f),
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<CausalityReteElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    private val rete: CausalityRete = CausalityRete(rules, discount, minSupport)
    private val evaluator = ContentId.of("causality-rete".encodeToByteArray())
    private val _firings = MutableSharedFlow<ReteFiring>(replay = 0, extraBufferCapacity = 1024)
    /** Every non-duplicate firing, for Curator/Forge explanation. */
    val firings: SharedFlow<ReteFiring> get() = _firings
    private val seenFirings = HashSet<ContentId>()

    // angular → (subject, obj) term registry; the caller owns registration.

    private val terms:MutableMap<Long, Twin<String>> =LinkedHashMap()

    /** The admitted eternal rules (temporal input was refused at admission). */
    val rules: Series<EternalRule> get() = rete.rules

    override suspend fun open() {
        super.open()
        if (state == ElementState.OPEN) state = ElementState.ACTIVE
    }

    /** Record the term identity of a signal the caller minted with known terms. */
    fun register(angular: Long, subject: String, obj: String) {
        this.terms[angular] = subject j obj
    }

    /**
     * Project the bag's live snapshot into rete assertions. Signals without a
     * registered term identity are skipped — the rete never guesses terms.
     */
    fun projectLive(): Series<ReteAssertion> {
        val snapshot = bag.snapshot()
        if (snapshot.isEmpty()) return emptySeriesOf()
        val out = ArrayList<ReteAssertion>()
        for ((_, signal) in snapshot) {
            val term: Twin<String> = terms[signal.angular] ?: continue
            val (subject, obj) = term
            out.add(
                ReteAssertion(
                    subject = subject,
                    obj = obj,
                    angular = signal.angular,
                    evidence = signal.evidence,
                    relation = signal.relation,
                )
            )
        }
        return out.toSeries()
    }

    /**
     * Fire the live rete against the bag as it exists and mint each firing's
     * discounted support back in. Returns the landed (angular → gloss) pairs so
     * a render layer can caption the minted consequents. Quota-free: no model
     * call anywhere on this path.
     */
    suspend fun fireLive(): Series2<Long, String> {
        if (state != ElementState.ACTIVE) return emptySeriesOf()
        val assertions = projectLive()
        if (assertions.size == 0) return emptySeriesOf()
        val firings = rete.fire(assertions)
        val landed = ArrayList<Join<Long, String>>()
        for (i in 0 until firings.size) {
            val firing = firings[i]
            if (!seenFirings.add(firing.firingCid)) continue
            _firings.tryEmit(firing)
            val consequentAngular = firing.consequentAngular
            val receipt = DerivationReceipt.observation(
                subject = TermIdentity(firing.matched.angular),
                predicate = TermIdentity(consequentAngular),
                contextCid = ContentId.of(firing.rule.antecedent.encodeToByteArray()),
                outcomeCid = ContentId.of(firing.rule.consequent.encodeToByteArray()),
                evidence = firing.support,
                evaluatorCid = evaluator,
            )
            bag.intake.send(
                BeliefIntake.Mint(
                    SemanticSignal(
                        angular = consequentAngular,
                        evidence = firing.support,
                        relation = RelationKind.CAUSALITY,
                        subjectCid = ContentId.of(firing.rule.antecedent.encodeToByteArray()).value,
                        objectCid = ContentId.of(firing.rule.consequent.encodeToByteArray()).value,
                        provenanceCid = receipt.canonicalCid.value,
                    ),
                    mintBudget,
                    receiptCid = receipt.canonicalCid,
                ),
            )
            // register the consequent under its own subject term so a later
            // rule may chain from it without this rule matching its own output
            register(consequentAngular, firing.rule.consequent, firing.rule.antecedent)
            landed.add(
                consequentAngular j
                    "${firing.rule.antecedent} ${firing.rule.copula.symbol} ${firing.rule.consequent}" +
                    if (firing.floored) " (floored)" else "",
            )
        }
        return landed.toSeries()
    }
}
