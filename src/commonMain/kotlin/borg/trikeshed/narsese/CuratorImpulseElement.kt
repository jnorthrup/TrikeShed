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
 * CuratorImpulseElement — the LIVE teaching recipient for hermes curator
 * impulses, over the daemon's BeliefBag.
 *
 * The pure [CuratorImpulseRecipient] does assess/bank/signals as folds; this
 * element is the CCEK owner that runs the pipeline against replayed scenario
 * transcripts and lands the results:
 *
 *  1. [teach] — replay scenarios with hindsight, assess matching impulses,
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
    /** The accumulated banked knowledge — SUMO spine + assessed impulses (share ONE bank daemon-wide). */
    val knowledgeBank: KifKnowledgeBase = KifKnowledgeBase(),
    /**
     * Durability tee for taught verdicts, called with each newly banked axiom in canonical KIF
     * text. [knowledgeBank] is in-memory and dies with the process; without this hook everything
     * taught through [teach] is gone at the next boot, which is not "taught" in any useful sense.
     * The daemon points it at the same `kif-ledger/` couch plane the boot thaw re-asserts from,
     * so the curator's knowledge restores by the identical path council and legal.ingest use.
     * The SUMO spine is deliberately NOT tee'd — `init` re-bootstraps it on every boot, so
     * persisting it would only accumulate duplicates.
     */
    private val ledger: ((String) -> Unit)? = null,
    parentJob: Job? = null,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<CuratorImpulseElement>()

    override val key: CoroutineContext.Key<*> get() = Key

    private val evaluator = ContentId.of("curator-impulse".encodeToByteArray())

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
     * Process one teaching pass: assess curator impulses against transcript scenarios AND
     * enacted grouping resolutions, bank the verdicts, mint the projected signals. Returns
     * the landed (angular → gloss) pairs for render captioning.
     */
    suspend fun teach(
        impulses: Series<CuratorImpulse>,
        scenarios: Series<ReplayScenario>,
        groupings: Series<GroupCoherenceEnactment> = borg.trikeshed.lib.emptySeriesOf(),
    ): List<Join<Long, String>> {
        if (state != ElementState.ACTIVE) return emptyList()
        val assessments = CuratorImpulseRecipient.assess(impulses, scenarios, groupings)
        if (assessments.size == 0) return emptyList()

        // bank the verdicts as predicate logic (accumulates across passes)
        val fresh = CuratorImpulseRecipient.bank(assessments)
        // `fresh` is a whole bank, so its asserts() carry the SUMO spine as well as this pass's
        // verdicts. Teeing all of them would persist the ontology that `init` already
        // re-bootstraps on every boot. The delta against what the live bank ALREADY holds is
        // exactly the set that would otherwise be lost at restart — spine excluded for free,
        // and a re-teach of an unchanged axiom writes no ledger line either.
        val alreadyBanked = if (ledger == null) emptySet() else knowledgeBank.asserts().toSet()
        for (expr in fresh.asserts()) {
            knowledgeBank.assert(expr)
            // Tee AFTER the in-memory assert, and never let it throw: a durability sink that is
            // down degrades durability, it does not cost the live bank this axiom or the ones
            // after it. Guarded here rather than only at the daemon's sink so the invariant
            // holds for every caller that supplies a ledger.
            if (expr !in alreadyBanked) ledger?.let { sink -> runCatching { sink(expr.toKifString()) } }
        }

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
