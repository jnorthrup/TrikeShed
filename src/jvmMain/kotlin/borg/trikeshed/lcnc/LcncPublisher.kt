package borg.trikeshed.lcnc

import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.util.oroboros.CouchAttachmentGateway
import kotlinx.coroutines.runBlocking

/**
 * ONE writer for everything LCNC on the blackboard ([LcncBlackboard]).
 *
 * The kanban module, the panel save route and the program loader all used to
 * compute the vocabulary and a program's typed cables their own way — the
 * module against the late-bound vocabulary (composites included), the save
 * route against the compiled table — so the same `lcnc/program/<name>` entry
 * meant different things depending on who wrote it last. This is the one
 * computation, and the one precedence: a preset owns its name, the user's
 * `panels/<name>` attachments follow.
 *
 * THE BOARD IS THE AUTHORITY. A source (preset, attachment) seeds an entry and
 * overwrites it only when the source itself changes (`sourceCid`); an entry
 * edited on the board keeps its `sourceCid` and is obeyed as edited; an entry
 * with no source at all is obeyed too, reconciled on first load so its cables
 * carry their types. Every put is guarded by a canonical (stringified)
 * comparison, so `/blackboard/facts` subscribers see a delta only when
 * something moved.
 *
 * THE PANELS PLANE rides the same call. When a [ReteNetwork] is given, every
 * program that passes [publishProgram] is also exploded ([PanelFacts]) into
 * the `panels` partition — one fact per program, node, cable (with its exact
 * type) and violation — by a [PanelFactBridge] that retracts what a republish
 * dropped and stays silent when nothing moved. The hook sits after the
 * blackboard put, so the board stays the authority and the facts are its
 * projection; a publisher without a network publishes exactly as before.
 */
class LcncPublisher(
    private val blackboard: ConfixBlackboard,
    /** Read late: the registry keeps growing after boot as modules attach. */
    private val runners: () -> Map<String, LcncNodeRunner>,
    private val attachments: CouchAttachmentGateway?,
    /** The daemon's one production network; null publishes to the blackboard only. */
    rete: ReteNetwork? = null,
) {
    /** The panels-plane bridge over [rete], or null when no network was given. */
    val panelFacts: PanelFactBridge? = rete?.let(::PanelFactBridge)

    fun isPreset(name: String): Boolean = name in LcncPresets.all()

    /** The stored-program corpus: the offered presets, then the user's own `panels/<name>` constructions. */
    fun storedCorpus(): Map<String, LcncProgram> {
        val out = LinkedHashMap<String, LcncProgram>()
        for ((name, doc) in LcncPresets.all()) {
            runCatching { LcncProgramConfix.fromJson(name, doc) }.onSuccess { out[name] = it }
        }
        val att = attachments ?: return out
        for (ref in runCatching { att.listAttachments("panels/") }.getOrDefault(emptyList())) {
            val name = ref.path.removePrefix("panels/")
            if (name in out) continue
            att.getAttachment(ref.path)?.let { (_, bytes) ->
                runCatching { LcncProgramConfix.fromJson(name, bytes.decodeToString()) }.onSuccess { out[name] = it }
            }
        }
        return out
    }

    /** One user program from its attachment, or null. */
    fun storedPanel(name: String): LcncProgram? {
        val att = attachments ?: return null
        val (_, bytes) = att.getAttachment("panels/$name") ?: return null
        return runCatching { LcncProgramConfix.fromJson(name, bytes.decodeToString()) }.getOrNull()
    }

    /** The SOURCE of a name — a preset first, then the user's attachment — or null. */
    fun source(name: String): LcncProgram? =
        LcncPresets.all()[name]?.let { runCatching { LcncProgramConfix.fromJson(name, it) }.getOrNull() } ?: storedPanel(name)

    /** The late-bound vocabulary: compiled contracts plus the corpus's composites. */
    fun vocabulary(corpus: Map<String, LcncProgram> = storedCorpus()): Map<String, LcncPortContract> =
        LcncVocabulary.resolve(corpus)

    class LateBound(
        val corpus: Map<String, LcncProgram>,
        val vocabulary: Map<String, LcncPortContract>,
        val bindings: List<LcncBinding>,
        val facts: LcncFacts,
    )

    /** Contracts, composites, bindings and tuples, resolved now — never cached at boot. */
    fun lateBound(): LateBound {
        val corpus = storedCorpus()
        val vocabulary = vocabulary(corpus)
        val compiled = LcncContracts.all().map { it.type }.toSet()
        val composites = vocabulary.filterKeys { it !in compiled }
        // ONE reflective act per runner: its class name is the provenance.
        val bindings = LcncWrappers.bindings(vocabulary.values, runners(), { it.javaClass.name }, composites)
        val facts = LcncFacts.of(vocabulary.values, corpus).learn(bindings)
        return LateBound(corpus, vocabulary, bindings, facts)
    }

    /** The payload the canvas reads: what `lcnc/vocabulary` holds. */
    fun vocabularyPayload(lb: LateBound = lateBound()): Map<String, Any?> {
        val facts = lb.facts
        val bindingOf = facts.bindings()
        val shapes = facts.shapes()
        return mapOf(
            "contracts" to lb.vocabulary.values.map { c -> mapOf(
                "type" to c.type, "title" to c.title,
                "inputs" to c.inputs, "outputs" to c.outputs,
                "inputKinds" to c.inputKinds, "outputKinds" to c.outputKinds,
                "cardinality" to c.cardinality.mapValues { it.value.name }, "functions" to c.functions,
                "params" to c.params.mapValues { p ->
                    mapOf(
                        "v" to p.value.v, "opts" to p.value.opts, "optsFrom" to p.value.optsFrom,
                        "ta" to p.value.ta, "ph" to p.value.ph,
                        "cols" to p.value.cols,
                    )
                },
                "source" to c.isSource, "sink" to c.isSink, "wide" to c.wide, "effect" to c.isEffect,
                "kindShapes" to c.kindShapes,
                "binding" to bindingOf[c.type]?.let { (how, by) -> mapOf("kind" to how, "provenance" to by) },
            ) },
            "kindHierarchy" to facts.hierarchy().map { (child, parent) ->
                mapOf("child" to child, "parent" to parent, "predicate" to "http://www.w3.org/2000/01/rdf-schema#subClassOf")
            },
            "kindAcceptance" to facts.acceptance(),
            "kindRefinements" to facts.literalPorts().flatMap { (type, port, declared) ->
                if (declared != "json") emptyList()
                else shapes.map { (k, keys) -> mapOf("nodeType" to type, "outputPort" to port, "kind" to k, "jsonArrayObjectRequiredKeys" to keys) }
            },
            "bindings" to lb.bindings.groupBy { it.kind.name.lowercase() }.mapValues { it.value.size },
        )
    }

    fun publishVocabulary(lb: LateBound = lateBound()): Map<String, Any?> {
        val payload = vocabularyPayload(lb)
        putIfChanged(LcncBlackboard.VOCABULARY, payload, "lcnc")
        return payload
    }

    /**
     * One program on the blackboard: document, cables typed against the
     * late-bound vocabulary, violations, and the cid of the source it came from.
     */
    fun publishProgram(
        name: String,
        program: LcncProgram,
        vocabulary: Map<String, LcncPortContract> = vocabulary(),
        sourceCid: String? = LcncBlackboard.cidOf(program),
    ): Map<String, Any?> {
        val entry = LcncBlackboard.programEntry(name, program, vocabulary, sourceCid)
        putIfChanged(LcncBlackboard.programKey(name), entry, "lcnc")
        // The panels plane: the entry, exploded, on the production network. The
        // bridge is idempotent (same entry => no ops) and retracts what vanished,
        // so it runs on every publish, not only on a board delta — a network handed
        // over after the board was seeded still ends up holding every program.
        // runBlocking: the network's ops are suspend behind its own mutex; the
        // hold is short and no observer may write back into it (ReteObserver doc).
        panelFacts?.let { bridge -> runBlocking { bridge.publish(name, program, entry, actor = "lcnc") } }
        return entry
    }

    /**
     * Every program the corpus holds, on the blackboard — seeded or refreshed
     * only where the SOURCE changed; a board-edited entry is left as edited.
     */
    fun publishPrograms(lb: LateBound = lateBound()) {
        for ((name, program) in lb.corpus) {
            val cid = LcncBlackboard.cidOf(program)
            val entry = blackboard.get(LcncBlackboard.programKey(name))
            if (entry == null || LcncBlackboard.sourceCidOf(entry) != cid) publishProgram(name, program, lb.vocabulary, cid)
        }
    }

    /** Vocabulary and every program, together — what open() and a panel save do. */
    fun publishAll(): LateBound {
        val lb = lateBound()
        publishVocabulary(lb)
        publishPrograms(lb)
        return lb
    }

    /** The board entry's document, as the Confix JSON text a canvas loads — a board-only program included. */
    fun boardDocumentJson(name: String): String? =
        LcncBlackboard.documentJsonOf(blackboard.get(LcncBlackboard.programKey(name)))

    /** The whole board entry — document, typed cables, violations, sourceCid — as the canvas SHOWS it. */
    fun boardEntry(name: String): Any? = blackboard.get(LcncBlackboard.programKey(name))

    /**
     * THE LOADER — the blackboard is what the run seam obeys.
     *  - A sourced name (preset, attachment) seeds its entry, and overwrites it
     *    only when the source's cid moved; a board edit keeps its `sourceCid`
     *    and stays.
     *  - An entry with no source is obeyed as is; if it arrived raw (no typed
     *    cables), it is reconciled first so the board never holds an untyped cable.
     */
    fun load(name: String): LcncProgram? {
        val key = LcncBlackboard.programKey(name)
        val source = source(name)
        if (source != null) {
            val cid = LcncBlackboard.cidOf(source)
            val entry = blackboard.get(key)
            if (entry == null || LcncBlackboard.sourceCidOf(entry) != cid) publishProgram(name, source, sourceCid = cid)
        }
        val entry = blackboard.get(key) ?: return null
        if (!LcncBlackboard.isReconciled(entry)) {
            val edited = LcncBlackboard.programOf(entry) ?: return null
            publishProgram(name, edited, sourceCid = LcncBlackboard.sourceCidOf(entry))
        }
        return LcncBlackboard.programOf(blackboard.get(key))
    }

    private fun putIfChanged(key: String, value: Any?, actor: String) {
        val existing = blackboard.get(key)
        val same = existing != null && runCatching { JsonSupport.stringify(existing) == JsonSupport.stringify(value) }.getOrDefault(false)
        if (!same) blackboard.put(key, value, actor)
    }
}
