package borg.trikeshed.hermes

import borg.trikeshed.cursor.BudgetCoord
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.graal.subvm.GraalBtrfsSupervisor
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.memory.GoalCoord
import borg.trikeshed.memory.SkillRegistry
import borg.trikeshed.memory.selectSkills
import borg.trikeshed.narsese.AngularCodec
import borg.trikeshed.narsese.BeliefBagElement
import borg.trikeshed.narsese.BeliefIntake
import borg.trikeshed.narsese.DerivationReceipt
import borg.trikeshed.narsese.Nal
import borg.trikeshed.narsese.RelationKind
import borg.trikeshed.narsese.SemanticSignal
import borg.trikeshed.narsese.TermIdentity
import borg.trikeshed.ontology.zipper.PlaneAdapters
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.vm.Teleported
import kotlinx.coroutines.runBlocking
import modelmux.ModelMux
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * THE PEN — the hosted model's entire tool surface: typed host.call delegates
 * across the guest waist. Deliberately unfamiliar (TrikeShed vocabulary —
 * planes, crumbs, leases — aliasing nothing to read_file/exec/fetch), minimal
 * (every verb widens the coordination surface), and TOTALLY receipted:
 *
 * Every crossing lands twice —
 *  1. a blackboard landing (`hermes/python/pen/<verb>/<seq>`) that the Rete
 *     tendon sees as a fact: model behavior IS working memory;
 *  2. a SemanticSignal about the tool use itself (CAUSALITY, subject =
 *     agent×verb, evidence = Nal.observe(outcome)) — the harness inductively
 *     models its models. Guest self-assertions are CLAMPED to unit evidence.
 *
 * Verbs (≤8, admission per verb):
 *  - mux_converse : ModelMux.chat — NEVER a KeyMux+Htx bypass; lease-gated,
 *                   typed refusal {verdict:"lease-exhausted"|"no-mux"}.
 *  - bag_recall   : hamming recall around a goal coordinate.
 *  - bag_assert   : mint a belief (evidence clamped to 1 unit, OBSERVATION receipt).
 *  - crumb_walk   : skill backchain; returns picks WITH their crumb trails.
 *  - skill_scribe : create/patch/archive a skill — confined to the skills root;
 *                   THERE IS NO DELETE VERB.
 */
class HermesPen(
    private val blackboard: ConfixBlackboard,
    private val bag: BeliefBagElement? = null,
    private val modelMux: ModelMux? = null,
    private val registry: SkillRegistry? = null,
    private val cards: Series<SkillRegistry.SkillCard>? = null,
    private val planes: PlaneAdapters? = null,
    private val skillsRoot: File? = null,
    /** Context carrying MuxReactorElement + Htx for the converse path. */
    private val muxContext: CoroutineContext = EmptyCoroutineContext,
    private val agentId: String = "graal-python",
) {
    private val seq = AtomicLong()
    private val evaluator = ContentId.of("hermes-pen".encodeToByteArray())

    fun install(guest: GraalBtrfsSupervisor) {
        guest.delegate("mux_converse") { args -> receipted("mux_converse", args) { muxConverse(args) } }
        guest.delegate("bag_recall") { args -> receipted("bag_recall", args) { bagRecall(args) } }
        guest.delegate("bag_assert") { args -> receipted("bag_assert", args) { bagAssert(args) } }
        guest.delegate("crumb_walk") { args -> receipted("crumb_walk", args) { crumbWalk(args) } }
        guest.delegate("skill_scribe") { args -> receipted("skill_scribe", args) { skillScribe(args) } }
    }

    // ── the double landing: blackboard fact + tool-use belief ─────────

    private fun receipted(verb: String, args: List<Teleported>, body: () -> Teleported): Teleported {
        val n = seq.incrementAndGet()
        val outcome = runCatching(body)
        blackboard.put(
            "hermes/python/pen/$verb/$n",
            mapOf(
                "verb" to verb,
                "agent" to agentId,
                "argShapes" to args.map { it::class.simpleName },
                "ok" to outcome.isSuccess,
                "error" to outcome.exceptionOrNull()?.message,
            ),
            agentId,
        )
        bag?.intake?.trySend(
            BeliefIntake.Mint(
                SemanticSignal(
                    angular = AngularCodec.encode(
                        relation = RelationKind.CAUSALITY,
                        taxonomyKey = "pen/$verb",
                        subjectTerm = "$agentId $verb",
                        objectTerm = verb,
                    ),
                    evidence = Nal.observe(outcome.isSuccess),
                    relation = RelationKind.CAUSALITY,
                    subjectCid = ContentId.of("$agentId:$verb".encodeToByteArray()).value,
                    provenanceCid = evaluator.value,
                ),
                BudgetCoord(0.6f, 0.4f, 0.5f),
                receiptCid = DerivationReceipt.observation(
                    subject = TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = agentId)),
                    predicate = TermIdentity(AngularCodec.encode(RelationKind.CAUSALITY, subjectTerm = verb)),
                    contextCid = ContentId.of("pen:$verb:$n".encodeToByteArray()),
                    outcomeCid = ContentId.of(outcome.isSuccess.toString().encodeToByteArray()),
                    evidence = Nal.observe(outcome.isSuccess),
                    evaluatorCid = evaluator,
                ).canonicalCid,
            ),
        )
        return outcome.getOrElse { refusal("error", it.message ?: it::class.simpleName.orEmpty()) }
    }

    /** Refusals are vocabulary, not exceptions — guest code handles them as data. */
    private fun refusal(verdict: String, detail: String = ""): Teleported =
        Teleported.Str(JsonSupport.stringify(mapOf("verdict" to verdict, "detail" to detail)))

    private fun str(args: List<Teleported>, i: Int): String? = (args.getOrNull(i) as? Teleported.Str)?.v

    // ── verbs ─────────────────────────────────────────────────────────

    private fun muxConverse(args: List<Teleported>): Teleported {
        val mux = modelMux ?: return refusal("no-mux", "model plane not leased to this pen")
        val modelId = str(args, 0) ?: return refusal("bad-args", "mux_converse(modelId, prompt)")
        val prompt = str(args, 1) ?: return refusal("bad-args", "mux_converse(modelId, prompt)")
        val result = runBlocking(muxContext) {
            mux.chat(modelId, 1 j { _: Int -> "user" j prompt })
        }
        return result.fold(
            onSuccess = { Teleported.Str(JsonSupport.stringify(mapOf("verdict" to "ok", "response" to it.toString()))) },
            onFailure = { refusal(if ("lease" in (it.message ?: "")) "lease-exhausted" else "mux-error", it.message ?: "") },
        )
    }

    private fun bagRecall(args: List<Teleported>): Teleported {
        val bag = bag ?: return refusal("no-bag")
        val goalTerm = str(args, 0) ?: return refusal("bad-args", "bag_recall(goalTerm, maxDistance?)")
        val maxDistance = (args.getOrNull(1) as? Teleported.Num)?.v?.toInt() ?: 16
        val centroid = AngularCodec.encode(RelationKind.CAUSALITY, taxonomyKey = "skills", subjectTerm = goalTerm)
        val found = bag.recallNear(centroid, maxDistance)
        val rows = (0 until minOf(found.size, 16)).map { i ->
            val s = found[i]
            mapOf(
                "angular" to s.angular.toString(),
                "relation" to s.relation.name,
                "expectation" to Nal.truthOf(s.evidence).expectation(),
                "subjectCid" to s.subjectCid,
            )
        }
        return Teleported.Str(JsonSupport.stringify(mapOf("verdict" to "ok", "beliefs" to rows)))
    }

    private fun bagAssert(args: List<Teleported>): Teleported {
        val bag = bag ?: return refusal("no-bag")
        val subjectTerm = str(args, 0) ?: return refusal("bad-args", "bag_assert(subject, object, success)")
        val objectTerm = str(args, 1)
        val success = (args.getOrNull(2) as? Teleported.Bool)?.v ?: true
        // guest evidence is CLAMPED to one unit — no self-asserted authority
        val angular = AngularCodec.encode(
            relation = RelationKind.CAUSALITY, taxonomyKey = "guest/$agentId",
            subjectTerm = subjectTerm, objectTerm = objectTerm,
        )
        bag.intake.trySend(
            BeliefIntake.Mint(
                SemanticSignal(
                    angular = angular,
                    evidence = Nal.observe(success),
                    relation = RelationKind.CAUSALITY,
                    subjectCid = ContentId.of(subjectTerm.encodeToByteArray()).value,
                    objectCid = objectTerm?.let { ContentId.of(it.encodeToByteArray()).value },
                    provenanceCid = evaluator.value,
                ),
                BudgetCoord(0.4f, 0.3f, 0.5f),
            ),
        )
        return Teleported.Str(JsonSupport.stringify(mapOf("verdict" to "ok", "angular" to angular.toString())))
    }

    private fun crumbWalk(args: List<Teleported>): Teleported {
        val registry = registry ?: return refusal("no-skills")
        val cards = cards ?: return refusal("no-skills")
        val planes = planes ?: return refusal("no-planes")
        val goalTerm = str(args, 0) ?: return refusal("bad-args", "crumb_walk(goalTerm, category?, k?)")
        val category = str(args, 1)
        val k = (args.getOrNull(2) as? Teleported.Num)?.v?.toInt() ?: 5
        val picks = selectSkills(GoalCoord(goalTerm, category), registry, cards, planes, k = k)
        val rows = (0 until picks.size).map { i ->
            val p = picks[i]
            mapOf(
                "name" to p.card.name,
                "category" to p.card.category,
                "description" to p.card.description,
                "score" to p.score,
                "trail" to (0 until p.trail.size).map { t -> p.trail[t].a.toString() },
            )
        }
        return Teleported.Str(JsonSupport.stringify(mapOf("verdict" to "ok", "picks" to rows)))
    }

    private fun skillScribe(args: List<Teleported>): Teleported {
        val root = skillsRoot ?: return refusal("no-scribe")
        val action = str(args, 0) ?: return refusal("bad-args", "skill_scribe(action, category, name, content?)")
        val category = str(args, 1) ?: return refusal("bad-args", "category required")
        val name = str(args, 2) ?: return refusal("bad-args", "name required")
        if (!name.matches(Regex("^[a-z0-9][a-z0-9._-]*$")) || !category.matches(Regex("^[a-z0-9][a-z0-9._-]*$"))) {
            return refusal("bad-name", "lowercase kebab identifiers only")
        }
        val dir = File(File(root, category), name)
        if (!dir.canonicalPath.startsWith(root.canonicalPath)) return refusal("confined", "path escapes the skills root")
        val skillMd = File(dir, "SKILL.md")
        return when (action) {
            "create", "patch" -> {
                val content = str(args, 3) ?: return refusal("bad-args", "content required for $action")
                if (content.length > 100_000) return refusal("too-large", "100k char cap")
                val before = skillMd.takeIf { it.isFile }?.readBytes()?.let { ContentId.of(it).hex }
                dir.mkdirs()
                skillMd.writeText(content)
                Teleported.Str(JsonSupport.stringify(mapOf(
                    "verdict" to "ok", "action" to action,
                    "before" to before, "after" to ContentId.of(content.encodeToByteArray()).hex,
                )))
            }
            "archive" -> {
                if (!skillMd.isFile) return refusal("missing", "$category/$name")
                val archived = File(dir, "SKILL.md.archived")
                skillMd.renameTo(archived)
                Teleported.Str(JsonSupport.stringify(mapOf("verdict" to "ok", "action" to "archive")))
            }
            else -> refusal("no-such-action", "create|patch|archive only — there is no delete")
        }
    }
}
