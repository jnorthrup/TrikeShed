package borg.trikeshed.narsese

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.job.ContentId
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import kotlinx.coroutines.Job
import kotlin.coroutines.CoroutineContext

/**
 * CuratorImpulseElement — the LIVE training recipient for hermes curator
 * impulses, over the daemon's BeliefBag.
 *
 * The pure [CuratorImpulseRecipient] does assess/bank/signals as folds; this
 * element is the CCEK owner that runs the pipeline against replayed scenario
 * transcripts and lands the results:
 *
 *  1. [train] — replay scenarios with hindsight, assess matching impulses,
 *     bank the verdicts as SUMO-grounded KIF (the banked knowledge survives
 *     across calls in [knowledgeBank]), and mint the projected signals into
 *     the bag at a discounted budget. NEUTRAL verdicts mint nothing.
 *  2. **The bank is the memory.** [knowledgeBank] accumulates every assessed
 *     impulse as predicate-logic constructions; [queryBank] exposes the KB
 *     solver so the rete and future replays can draw on it.
 *  3. **Term registration.** Minted signals are registered with the rete
 *     element (when wired) so their term identity is live for later fires.
 *
 * Quota-free: no model call anywhere on this path.
 */
class CuratorImpulseElement(
    private val bag: BeliefBagElement,
    /** Optional rete element to register minted term identities with. */
    private val rete: CausalityReteElement? = null,
    /** Attention budget for minted impulse signals. */
    private val mintBudget: BudgetCoord = BudgetCoord(0.6f, 0.5f, 0.5f),
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<CuratorImpulseElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    private val evaluator = ContentId.of("curator-impulse".encodeToByteArray())

    /** The accumulated banked knowledge — SUMO spine + assessed impulses. */
    val knowledgeBank: KifKnowledgeBase = KifKnowledgeBase()

    init {
        // bootstrap the bank with the SUMO upper spine once
        for (expr in borg.trikeshed.kif.KifExpr.parseAll(
            borg.trikeshed.ontology.SumoOntology.emitUpperKif(),
        )) knowledgeBank.assert(expr)
    }

    override suspend fun open() {
        super.open()
        if (state == ElementState.OPEN) state = ElementState.ACTIVE
    }

    /**
     * Run one training pass: assess impulses against replayed scenarios, bank
     * the verdicts, mint the projected signals. Returns the landed
     * (angular → gloss) pairs for render captioning.
     */
    suspend fun train(
        impulses: Series<CuratorImpulse>,
        scenarios: Series<ReplayScenario>,
    ): List<Join<Long, String>> {
        if (state != ElementState.ACTIVE) return emptyList()
        val assessments = CuratorImpulseRecipient.assess(impulses, scenarios)
        if (assessments.size == 0) return emptyList()

        // bank the verdicts as predicate logic (accumulates across passes)
        val fresh = CuratorImpulseRecipient.bank(assessments)
        for (expr in fresh.asserts()) knowledgeBank.assert(expr)

        // mint the projected signals into the bag (paired with assessments so
        // term registration stays aligned — NEUTRAL is skipped exactly once)
        val sourceCid = evaluator.value
        val paired = CuratorImpulseRecipient.signalsWith(assessments, sourceCid)
        val landed = ArrayList<Join<Long, String>>()
        for (i in 0 until paired.size) {
            val assessment = paired[i].a
            val signal = paired[i].b
            val receipt = DerivationReceipt.observation(
                subject = TermIdentity(signal.angular),
                predicate = TermIdentity(signal.angular),
                contextCid = ContentId.of("curator-impulse".encodeToByteArray()),
                outcomeCid = ContentId.of(signal.subjectCid.encodeToByteArray()),
                evidence = signal.evidence,
                evaluatorCid = evaluator,
            )
            bag.intake.send(
                BeliefIntake.Mint(
                    signal.copy(provenanceCid = receipt.canonicalCid.value),
                    mintBudget,
                    receiptCid = receipt.canonicalCid,
                ),
            )
            // register term identity with the rete so later fires can chain
            rete?.register(signal.angular, assessment.impulse.term(), "scenario_${assessment.scenarioId}")
            landed.add(signal.angular j "${signal.relation} ${assessment.impulse.term()}")
        }
        return landed
    }

    /** Query the banked knowledge through the KB solver. */
    fun queryBank(kifPattern: String): List<Map<String, String>> =
        knowledgeBank.query(borg.trikeshed.kif.KifExpr.parse(kifPattern))
}
