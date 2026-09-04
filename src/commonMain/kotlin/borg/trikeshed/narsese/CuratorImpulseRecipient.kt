package borg.trikeshed.narsese

import borg.trikeshed.kif.KifExpr
import borg.trikeshed.kif.KifKnowledgeBase
import borg.trikeshed.kif.kif
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.emptySeriesOf
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.ontology.SumoOntology

/**
 * One hermes-curator impulse awaiting hindsight assessment: a curation
 * action the curator proposed (consolidate, prune, adopt, patch, create)
 * against a named subject, with the rationale it gave at proposal time.
 */
enum class CuratorImpulseKind(val token: String) {
    CONSOLIDATE("consolidate"),
    PRUNE("prune"),
    ADOPT("adopt"),
    PATCH("patch"),
    CREATE("create"),
}

data class CuratorImpulse(
    val kind: CuratorImpulseKind,
    val subject: String,
    val rationale: String,
    /** ContentId of the proposal transcript, when the caller has one. */
    val proposalCid: String? = null,
) {
    /** Stable term identity for KIF/NAL projection. */
    fun term(): String = "impulse_${kind.token}_$subject"
}

/** One turn of a replayed scenario transcript. */
data class ReplayTurn(val role: String, val text: String)

/**
 * A replay scenario: one transcript replayed with hindsight against the
 * impulse whose subject it names. The verdict is READ from the transcript's
 * outcome markers, never guessed.
 */
data class ReplayScenario(
    val scenarioId: String,
    val impulseSubject: String,
    val turns: Series<ReplayTurn>,
)

/**
 * R6's second hindsight source: a GroupingPost document together with the resolution document
 * that was actually enacted. Relabel/split/merge support the curator's grouping impulse;
 * Reject refutes it. The resolution cid is the immutable observation/provenance anchor.
 */
data class GroupCoherenceEnactment(
    val post: borg.trikeshed.cas.GroupingReorientation.GroupingPost,
    val resolution: borg.trikeshed.cas.GroupingReorientation.GroupingResolution,
    val resolutionCid: String,
)

enum class AssessmentSource { TRANSCRIPT, GROUP_COHERENCE }

/** Hindsight verdict, derived only from explicit outcome markers or an enacted resolution. */
enum class HindsightVerdict {
    SUPPORTED,
    REFUTED,
    NEUTRAL,
}

/**
 * One assessed impulse: the hindsight verdict with observation-grade evidence
 * and the scenario that produced it.
 */
data class ImpulseAssessment(
    val impulse: CuratorImpulse,
    val verdict: HindsightVerdict,
    val evidence: EvidenceCoord,
    val scenarioId: String,
    val source: AssessmentSource = AssessmentSource.TRANSCRIPT,
    /** Present only for the group-coherence source; bank() uses these for explicit KIF. */
    val groupRing8: Int? = null,
    val resolutionAction: String? = null,
    val resolutionCid: String? = null,
)

/**
 * CuratorImpulseRecipient — the teaching recipient that matches hermes
 * curator impulses against hindsight-replayed scenario transcripts, banks
 * the results as SUMO-grounded predicate-logic (KIF) knowledge, and projects
 * them into Narsese bag signals.
 *
 * Pipeline (all pure; the daemon owns every side effect):
 *
 *  1. [assess] — replay each scenario's transcript with hindsight: the
 *     verdict comes ONLY from explicit outcome markers in the turns
 *     (`[PASS]`/`[SUPPORTED]` vs `[FAIL]`/`[REFUTED]`). No marker = NEUTRAL.
 *     Evidence is one machine observation (Nal.UNIT) per assessed scenario.
 *  2. [bank] — fold assessments into a [KifKnowledgeBase] bootstrapped with
 *     the SUMO upper spine: each impulse is an `(instance … Agent)` under the
 *     SUMO Agent category, each verdict a `(=> …)` / `(not …)` implication.
 *     This is the banked knowledge — predicate logic constructions, not lore.
 *  3. [signals] — project assessments into [SemanticSignal]s for the belief
 *     bag: SUPPORTED lands as positive evidence on the impulse association,
 *     REFUTED as negative (frequency < 0.5 → refutation front of resonate),
 *     NEUTRAL mints nothing (no evidence, no signal — honesty over volume).
 */
object CuratorImpulseRecipient {

    /** Outcome markers live in the marker oracles below — one source, no list slop. */

    /**
     * Replay scenarios with hindsight and assess each matching impulse.
     * An impulse is assessed once per scenario naming its subject; the LAST
     * marker in the transcript wins (later turns supersede earlier ones).
     */
    fun assess(
        impulses: Series<CuratorImpulse>,
        scenarios: Series<ReplayScenario>,
        groupings: Series<GroupCoherenceEnactment> = emptySeriesOf(),
    ): Series<ImpulseAssessment> {
        if ((impulses.size == 0 || scenarios.size == 0) && groupings.size == 0) return emptySeriesOf()
        val out = ArrayList<ImpulseAssessment>()

        // Source 1: transcript outcome markers, unchanged.
        for (i in 0 until impulses.size) {
            val impulse = impulses[i]
            for (s in 0 until scenarios.size) {
                val scenario = scenarios[s]
                if (scenario.impulseSubject != impulse.subject) continue
                val verdict = readVerdict(scenario)
                out.add(
                    ImpulseAssessment(
                        impulse = impulse,
                        verdict = verdict,
                        evidence = when (verdict) {
                            HindsightVerdict.SUPPORTED -> EvidenceCoord(Nal.UNIT, 0L)
                            HindsightVerdict.REFUTED -> EvidenceCoord(0L, Nal.UNIT)
                            HindsightVerdict.NEUTRAL -> EvidenceCoord.EMPTY
                        },
                        scenarioId = scenario.scenarioId,
                    ),
                )
            }
        }

        // R6 source 2: the GroupingPost + the resolution document actually enacted. This is
        // observation-grade evidence — no transcript marker and no guessed verdict. Reject is
        // explicit negative evidence; deterministic relabel/split/merge are positive evidence.
        for (g in 0 until groupings.size) {
            val enacted = groupings[g]
            val action = groupingAction(enacted.resolution)
            val supported = action != "reject"
            val ring8 = enacted.post.group.ring8
            val subject = "group-ring8-$ring8"
            out.add(
                ImpulseAssessment(
                    impulse = CuratorImpulse(
                        kind = CuratorImpulseKind.PATCH,
                        subject = subject,
                        rationale = "group coherence: ${enacted.post.currentLabel} → $action",
                        proposalCid = enacted.post.evidence.sampleMembers.firstOrNull()?.value,
                    ),
                    verdict = if (supported) HindsightVerdict.SUPPORTED else HindsightVerdict.REFUTED,
                    evidence = if (supported) EvidenceCoord(Nal.UNIT, 0L) else EvidenceCoord(0L, Nal.UNIT),
                    scenarioId = "grouping-${enacted.resolutionCid.substringAfter(':')}",
                    source = AssessmentSource.GROUP_COHERENCE,
                    groupRing8 = ring8,
                    resolutionAction = action,
                    resolutionCid = enacted.resolutionCid,
                ),
            )
        }
        return out.toSeries()
    }

    private fun groupingAction(resolution: borg.trikeshed.cas.GroupingReorientation.GroupingResolution): String =
        when (resolution) {
            is borg.trikeshed.cas.GroupingReorientation.GroupingResolution.Relabel -> "relabel"
            is borg.trikeshed.cas.GroupingReorientation.GroupingResolution.Split -> "split"
            is borg.trikeshed.cas.GroupingReorientation.GroupingResolution.Merge -> "merge"
            borg.trikeshed.cas.GroupingReorientation.GroupingResolution.Reject -> "reject"
        }

    private fun readVerdict(scenario: ReplayScenario): HindsightVerdict {
        var verdict = HindsightVerdict.NEUTRAL
        for (t in 0 until scenario.turns.size) {
            val text = scenario.turns[t].text.lowercase()
            when {
                hasSupportMarker(text) -> verdict = HindsightVerdict.SUPPORTED
                hasRefuteMarker(text) -> verdict = HindsightVerdict.REFUTED
            }
        }
        return verdict
    }

    /** Marker oracles exposed for [ScenarioTranscripts] — same discipline, one source. */
    internal fun hasSupportMarker(text: String): Boolean =
        text.indexOf("[pass]") >= 0 || text.indexOf("[supported]") >= 0 || text.indexOf("[keep]") >= 0

    internal fun hasRefuteMarker(text: String): Boolean =
        text.indexOf("[fail]") >= 0 || text.indexOf("[refuted]") >= 0 || text.indexOf("[drop]") >= 0

    /**
     * Bank assessments as SUMO-grounded KIF. The KB carries the SUMO upper
     * spine (subclass axioms) plus, per assessment:
     *
     *   (instance impulse_<kind>_<subject> Agent)
     *   (=> (verdict <term> SUPPORTED) (outcome <term> keep))      — supported
     *   (=> (verdict <term> REFUTED) (outcome <term> drop))        — refuted
     *
     * NEUTRAL assessments bank the instance axiom only — no verdict, no
     * implication. The bank is the knowledge the rete and future replays
     * draw on; it is predicate logic, not prose.
     */
    fun bank(
        assessments: Series<ImpulseAssessment>,
        /**
         * The ground theory to seed the fresh bank with. Default: the 13-category upper
         * spine. Delta (2026-09-04): a caller whose live bank already holds its theory
         * (CuratorImpulseElement, seeded once from the pinned SUMO corpus) passes an
         * empty list so a teaching pass banks only this pass's verdicts.
         */
        groundTheory: List<KifExpr> = KifExpr.parseAll(SumoOntology.emitUpperKif()),
        /** The class every assessed impulse is an instance of — `Agent` in the spine, `AutonomousAgent` in the corpus. */
        agentClass: String = SumoOntology.SumoCategory.Agent.kifName,
    ): KifKnowledgeBase {
        val kb = KifKnowledgeBase()
        for (expr in groundTheory) kb.assert(expr)
        for (i in 0 until assessments.size) {
            val a = assessments[i]
            val term = a.impulse.term()
            kb.assert(kif("instance", KifExpr.Atom(term), KifExpr.Atom(agentClass)))
            // R6: grouping posts/resolutions are documents already; bank the enacted stitch as
            // SUMO-grounded KIF beside transcript verdicts. The group is a SUMO Collection,
            // the curator impulse is its member/agent, and the resolution cid is immutable
            // provenance for the coherence observation.
            if (a.source == AssessmentSource.GROUP_COHERENCE && a.groupRing8 != null && a.resolutionAction != null) {
                val group = "group_ring8_${a.groupRing8}"
                val resolution = "resolution_${a.resolutionCid.orEmpty().substringAfter(':').replace('-', '_')}"
                kb.assert(kif("instance", KifExpr.Atom(group), KifExpr.Atom(SumoOntology.SumoCategory.Collection.kifName)))
                kb.assert(kif("member", KifExpr.Atom(term), KifExpr.Atom(group)))
                kb.assert(kif("groupCoherence", KifExpr.Atom(group), KifExpr.Atom(a.resolutionAction)))
                kb.assert(kif("enactedResolution", KifExpr.Atom(group), KifExpr.Atom(resolution)))
            }
            when (a.verdict) {
                HindsightVerdict.SUPPORTED -> kb.assert(
                    kif(
                        "=>",
                        kif("verdict", KifExpr.Atom(term), KifExpr.Atom("SUPPORTED")),
                        kif("outcome", KifExpr.Atom(term), KifExpr.Atom("keep")),
                    ),
                )
                HindsightVerdict.REFUTED -> kb.assert(
                    kif(
                        "=>",
                        kif("verdict", KifExpr.Atom(term), KifExpr.Atom("REFUTED")),
                        kif("outcome", KifExpr.Atom(term), KifExpr.Atom("drop")),
                    ),
                )
                HindsightVerdict.NEUTRAL -> Unit
            }
        }
        return kb
    }

    /**
     * Project assessments into bag signals. SUPPORTED → positive evidence on
     * the impulse association (MATCH); REFUTED → negative evidence
     * (CONTRADICTION relation, frequency < 0.5 → refutation front); NEUTRAL
     * mints nothing. Angular identity is the FNV of term + verdict copula,
     * stable across replays of the same impulse.
     */
    fun signals(assessments: Series<ImpulseAssessment>, sourceCid: String): Series<SemanticSignal> {
        val paired = signalsWith(assessments, sourceCid)
        val out = ArrayList<SemanticSignal>(paired.size)
        for (i in 0 until paired.size) out.add(paired[i].b)
        return out.toSeries()
    }

    /**
     * Like [signals] but keeps each signal paired with the assessment that
     * produced it — the element layer needs the correspondence to register
     * term identities. NEUTRAL assessments are skipped here, exactly once.
     */
    fun signalsWith(
        assessments: Series<ImpulseAssessment>,
        sourceCid: String,
    ): Series<borg.trikeshed.lib.Join<ImpulseAssessment, SemanticSignal>> {
        if (assessments.size == 0) return emptySeriesOf()
        val out = ArrayList<borg.trikeshed.lib.Join<ImpulseAssessment, SemanticSignal>>()
        for (i in 0 until assessments.size) {
            val a = assessments[i]
            if (a.verdict == HindsightVerdict.NEUTRAL) continue
            val relation = if (a.verdict == HindsightVerdict.REFUTED) RelationKind.CONTRADICTION else RelationKind.MATCH
            val triplet = KgTriplet(
                subject = a.impulse.term(),
                predicate = if (a.verdict == HindsightVerdict.REFUTED) "contradicts" else "supported-by",
                obj = "scenario_${a.scenarioId}",
                confidence = 1.0f,
            )
            out.add(
                a j SemanticSignal(
                    angular = AngularCodec.encode(
                        relation = relation,
                        taxonomyKey = "curator/${a.impulse.kind.token}",
                        subjectTerm = triplet.subject,
                        objectTerm = triplet.obj,
                    ),
                    evidence = a.evidence,
                    relation = relation,
                    subjectCid = a.impulse.proposalCid ?: sourceCid,
                    objectCid = null,
                    provenanceCid = sourceCid,
                ),
            )
        }
        return out.toSeries()
    }
}
