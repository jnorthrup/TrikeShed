package borg.trikeshed.memory

import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.narsese.AngularCodec
import borg.trikeshed.narsese.DerivationReceipt
import borg.trikeshed.narsese.Nal
import borg.trikeshed.narsese.RelationKind
import borg.trikeshed.narsese.TermIdentity
import borg.trikeshed.narsese.hamming
import borg.trikeshed.ontology.zipper.Crumb
import borg.trikeshed.ontology.zipper.OntoZipper
import borg.trikeshed.ontology.zipper.Plane
import borg.trikeshed.ontology.zipper.PlaneAdapters
import borg.trikeshed.ontology.zipper.PlaneNode
import borg.trikeshed.ontology.zipper.WalkBudget

/** The turn's goal as an angular coordinate in the skill space. */
@kotlin.jvm.JvmInline
value class GoalCoord(val angular: Long) {
    companion object {
        operator fun invoke(goalTerm: String, category: String? = null): GoalCoord = GoalCoord(
            AngularCodec.encode(
                relation = RelationKind.CAUSALITY,
                taxonomyKey = category?.let { "skills/$it" } ?: "skills",
                subjectTerm = goalTerm,
            ),
        )
    }
}

data class SkillPick(
    val card: SkillRegistry.SkillCard,
    val score: Float,
    /** How this pick was reached — the breadcrumb provenance. */
    val trail: Series<Crumb>,
)

/**
 * selectSkills — the backchain that replaces Hermes' whole-index prompt dump.
 * Bounded by the zipper's WalkBudget (AIKR); each chain contributes candidates
 * with its own evidence discount, merged by max-score, top-k. Every pick
 * carries its trail so "why was this offered" is answerable by receipt.
 *
 * Chains:
 *  1. BAG   — beliefs (context → skill) hamming-near the goal coordinate
 *  2. ISA   — generalize via the category lattice: category siblings, discounted
 *  3. TAX   — taxonomy surface: goal terms vs category names, coordinate distance
 *  4. CAUSAL— events proximate to recent session work (adapter-injected)
 */
fun selectSkills(
    goal: GoalCoord,
    registry: SkillRegistry,
    cards: Series<SkillRegistry.SkillCard>,
    planes: PlaneAdapters,
    k: Int = 5,
    nearDistance: Int = 16,
    budget: WalkBudget = WalkBudget(depth = 4, visits = 64),
): Series<SkillPick> {
    val byAngular = HashMap<Long, SkillRegistry.SkillCard>()
    val byName = HashMap<String, SkillRegistry.SkillCard>()
    for (c in cards.view) {
        byAngular[c.angular] = c
        if (c.name !in byName) byName[c.name] = c // first occurrence wins: firstOrNull contract
    }

    val best = HashMap<String, SkillPick>()
    fun offer(card: SkillRegistry.SkillCard, score: Float, trail: Series<Crumb>) {
        val cur = best[card.name]
        if (cur == null || cur.score < score) best[card.name] = SkillPick(card, score, trail)
    }

    val stitch = DerivationReceipt.observation(
        subject = TermIdentity(goal.angular),
        predicate = TermIdentity(0L),
        contextCid = ContentId.of("skill-select".encodeToByteArray()),
        outcomeCid = ContentId.of("goal:${goal.angular}".encodeToByteArray()),
        evidence = Nal.observe(true),
        evaluatorCid = ContentId.of("selectSkills".encodeToByteArray()),
    )

    // ── chain 1: BAG beliefs near the goal ────────────────────────────
    val bag = planes.bag
    if (bag != null) {
        val z = OntoZipper.onBag(goal.angular, planes, budget)
        val nearby = z.near(nearDistance)
        for (zi in nearby.view) {
            val angular = (planes.atlas.resolve(zi.focus.node) as? Long) ?: continue
            val card = byAngular[angular] ?: continue
            val belief = bag.snapshot().entries.firstOrNull { it.key.a == angular }?.value ?: continue
            val proximity = 1f - hamming(goal.angular, angular) / 64f
            offer(card, Nal.truthOf(belief.evidence).expectation() * proximity, zi.trail)
        }
    }

    // ── chain 2: ISA generalization — category siblings, discounted ───
    val lattice = registry.lattice()
    for (card in cards.view) {
        if (hamming(goal.angular, card.angular) > nearDistance) continue
        val supers = lattice.supertypes(registry.token(card.name), maxDepth = 2)
        for (sup in supers.view) {
            val siblings = lattice.directSubs(sup)
            for (sib in siblings.view) {
                val sibling = registry.nameOf(sib) ?: continue
                if (sibling == card.name) continue
                val sibCard = byName[sibling] ?: continue
                val z = OntoZipper.onBag(goal.angular, planes, budget)
                    .crossTo(Plane.ISA, sib.poolIdx.toLong(), via = stitch)
                offer(sibCard, 0.35f, z.trail)
            }
        }
    }

    // ── chain 3: taxonomy surface — goal vs category coordinate ───────
    for (card in cards.view) {
        val catCoord = GoalCoord(card.description.ifEmpty { card.name }, card.category)
        val d = hamming(goal.angular, catCoord.angular)
        if (d <= nearDistance + 8) {
            val z = OntoZipper.onPath("/${card.category}", planes, budget)
            offer(card, (1f - d / 64f) * 0.6f, z.trail)
        }
    }

    // ── chain 4: causal proximity (adapter-injected) ──────────────────
    if (planes.causalRank != null) {
        val z = OntoZipper.seed(PlaneNode(Plane.CAUSAL, planes.atlas.intern("recent")), planes, budget)
        val ranked = z.causally(k)
        for (r in ranked.view) {
            val workId = planes.atlas.resolve(r.focus.node) as? String ?: continue
            val card = byName[workId] ?: continue
            offer(card, 0.5f, r.trail)
        }
    }

    val picked = best.values.sortedWith(compareByDescending<SkillPick> { it.score }.thenBy { it.card.name }).take(k)
    return picked.size j { i: Int -> picked[i] }
}

/**
 * Render the selection as the frozen-snapshot skill block: top-k names + one-line
 * descriptions; the FULL body only for the focus pick, under [bodyBudget] chars.
 * Replaces Hermes' whole-index injection.
 */
fun renderSkillBlock(
    picks: Series<SkillPick>,
    focusBody: String?,
    bodyBudget: Int = 8000,
): String = buildString {
    append("## Skills (selected for this turn)\n")
    for (p in picks.view) {
        append("- ").append(p.card.name).append(" (").append(p.card.category).append("): ")
            .append(p.card.description.take(120)).append('\n')
    }
    if (focusBody != null && picks.size > 0) {
        append("\n### ").append(picks[0].card.name).append(" (focus)\n")
        append(focusBody.take(bodyBudget))
    }
}
