package borg.trikeshed.forge.server

import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProduction
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.dag.KifTee
import borg.trikeshed.kif.KifExpr
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
import borg.trikeshed.lib.view
import borg.trikeshed.narsese.CausalityReteElement
import borg.trikeshed.ontology.SumoClassifier
import borg.trikeshed.ontology.SumoMask
import borg.trikeshed.litebike.JvmKanbanServer
import borg.trikeshed.parse.json.JsonSupport

/**
 * ReteWire — the READ surface of the one fact plane: what the [ReteNetwork]
 * holds right now, as JSON, as Turtle, and which productions watch it.
 *
 *  - `GET /api/rete/facts?partition=&field=&value=`  facts of one partition
 *                                  whose `field` equals `value`; `?key=` is
 *                                  shorthand for the reserved [PlaneFacts.KEY]
 *                                  field (and matches a pre-plane fact's
 *                                  localId, see [PlaneFacts.keyOf]); a `field`
 *                                  without a `value` matches facts that carry
 *                                  the field with a non-null value; only
 *                                  `partition` → every fact of that partition;
 *                                  no `partition` → every partition.
 *                                  Answer: `{count, facts:[{partition, id,
 *                                  versionCid, fields}]}`, ordered by
 *                                  (partition, localId) as [ReteNetwork.snapshot]
 *                                  orders them.
 *  - `GET /api/facts/rdf?partition=`  the same selection (partition, field,
 *                                  value, key all honoured) projected through
 *                                  [PlaneFacts.toTriples] and emitted as Turtle
 *                                  with [PlaneFacts.PREFIXES]; every partition
 *                                  when `partition` is omitted.
 *  - `GET /api/rete/productions`   the registered productions as
 *                                  `{count, productions:[{ruleId, salience,
 *                                  interests:["field=value"]}]}` — the row
 *                                  shape `POST /api/lcnc/rdf/align` already
 *                                  prints under its `productions` key
 *                                  ([LcncRdfWire]), so one reader serves both.
 *
 * Every read goes through [ReteNetwork.snapshot], which takes the network's
 * write lock, so a response never shows a half-applied op; the filter is then
 * a scan over that snapshot rather than [borg.trikeshed.dag.ReteWorkingMemory.query],
 * which would read the memory unserialized. A `value` arrives as query text,
 * so a field matches when it equals the text or renders to it
 * ([renderScalar]: numbers and booleans by `toString`, a [ContentId] by its
 * `sha256:` text) — `?field=mark&value=3` finds an `Int` 3.
 *
 * Nothing here is authored and nothing here writes: the JSON is the fact,
 * the Turtle is [PlaneFacts]'s projection of it, the production rows are the
 * registry. The live proof the merger brief asks for is
 * `curl '/api/rete/facts?partition=blackboard&key=probe/x'` after a
 * `POST /blackboard/assert`.
 */
class ReteWire(
    private val network: ReteNetwork,
    private val kifTee: KifTee? = null,
    private val causality: CausalityReteElement? = null,
    private val classifier: SumoClassifier? = null,
) {

    private enum class Route(val path: String) {
        FACTS("/api/rete/facts"), RDF("/api/facts/rdf"), PRODUCTIONS("/api/rete/productions"),
        CONNECTIONS("/api/rete/connections"), SUMO("/api/rete/sumo");

        companion object {
            private val byPath = entries.associateBy { it.path }
            fun of(path: String): Route? = byPath[path]
        }
    }

    suspend fun route(
        method: String,
        path: String,
        text: String,
        respond: (suspend (ByteArray) -> Unit)?,
    ): JvmKanbanServer.HttpResponse? {
        if (method != "GET") return null
        return when (Route.of(path.substringBefore('?'))) {
            Route.FACTS -> {
                val selection = Selection.of(query(path))
                val facts = selection.select(network.snapshot())
                json(
                    linkedMapOf(
                        "count" to facts.size,
                        "partition" to selection.partition,
                        "facts" to facts.map(::factRow),
                    ),
                )
            }

            Route.RDF -> {
                val selection = Selection.of(query(path))
                turtle(PlaneFacts.toTurtle(selection.select(network.snapshot())))
            }

            Route.PRODUCTIONS -> {
                val prods = network.productions.all()
                json(linkedMapOf("count" to prods.size, "productions" to prods.map(::productionRow)))
            }

            Route.CONNECTIONS -> {
                val q = query(path)
                val limit = q["limit"]?.toIntOrNull() ?: if ("limit" in q) -1 else 100
                val offset = q["offset"]?.toIntOrNull() ?: if ("offset" in q) -1 else 0
                if (limit !in 1..1000 || offset < 0) json(mapOf("error" to "limit must be 1..1000; offset must be non-negative"), 400)
                else json(connections(Selection.of(q), offset, limit))
            }

            Route.SUMO -> {
                val sumo = classifier ?: return json(mapOf("error" to "SUMO classifier is not attached"), 503)
                val q = query(path)
                val term = q["term"].orEmpty()
                val predicate = q["predicate"].orEmpty()
                val argument = q["argument"]?.toIntOrNull() ?: if ("argument" in q) -1 else 1
                if (argument < 1) return json(mapOf("error" to "argument must be a positive integer"), 400)
                json(linkedMapOf(
                    "term" to term, "known" to (sumo.termId(term) >= 0),
                    "classId" to sumo.classId(term)?.value,
                    "classes" to sumo.classesOf(term).view.toList(),
                    "subclasses" to sumo.subclassesOf(term).view.toList(),
                    "masks" to SumoMask.entries.associate { it.name to sumo.mask(term, it).toIntArray().toList() },
                    "isA" to q["class"]?.let { sumo.isA(term, it) },
                    "disjoint" to q["class"]?.let { sumo.disjoint(term, it) },
                    "domain" to sumo.domainOf(predicate, argument),
                    "domainOk" to sumo.domainOk(predicate, argument, term),
                    "range" to sumo.rangeOf(predicate),
                    "numberClasses" to q["literal"]?.let { sumo.numberClassesOf(it).view.toList() },
                    "literalDomainOk" to q["literal"]?.let { sumo.domainOkLiteral(predicate, argument, it) },
                ))
            }

            null -> null
        }
    }

    /**
     * Component-local runtime snapshots. Fact pages are bounded; all retained
     * admissions and all subclass index rows are included. No static call-graph
     * arrows or inferred production matches are added to the evidence.
     */
    private suspend fun connections(selection: Selection, offset: Int, limit: Int): Map<String, Any?> {
        val selected = selection.select(network.snapshot())
        val page = selected.drop(offset).take(limit)
        val trace = network.admissionSnapshot()
        val receipts = trace.receipts.filter { selection.partition == null || it.partitionId == selection.partition }
        val bank = kifTee?.bank
        val subclasses = bank?.subclassSnapshot()
        val semantic = causality?.snapshot()
        return linkedMapOf(
            "schema" to "trikeshed.rete-connections/v1",
            "scope" to linkedMapOf(
                "network" to "injected ReteNetwork",
                "consistency" to "component-local snapshots, not a cross-component transaction",
                "activationMeaning" to "refraction admission and immediate delivery only; not downstream action completion",
                "ontologyMeaning" to "subclass assertions in the injected KIF bank; corpus origin is not tracked",
                "excluded" to listOf("other ReteNetwork instances", "ReteAgent", "IsALattice indexes"),
                "semanticMeaning" to "observed offers to BeliefBag intake; residence is reported separately",
                "classifierMeaning" to "independent injected SUMO classifier; its preorder IDs are not KIF-bank IDs",
            ),
            "facts" to linkedMapOf(
                "matched" to selected.size, "offset" to offset, "limit" to limit,
                "nextOffset" to (offset + page.size).takeIf { it < selected.size },
                "rows" to page.map(::factRow),
            ),
            "productions" to network.productions.all().map(::productionRow),
            "trace" to linkedMapOf(
                "capacity" to trace.capacity, "admitted" to trace.admitted, "dropped" to trace.dropped,
                "filter" to "partition only; fact field/key filters do not filter admission history",
                "receipts" to receipts.map { receipt ->
                    val a = receipt.activation
                    linkedMapOf(
                        "ordinal" to receipt.ordinal, "productionId" to receipt.productionId,
                        "partition" to receipt.partitionId, "activationId" to a.activationId,
                        "ruleId" to a.ruleId, "ruleVersionCid" to a.ruleVersionCid.value,
                        "sequence" to a.sequence, "salience" to a.salience,
                        "supportCids" to a.supportCids.map { it.value }, "bindings" to a.bindings,
                        "delivery" to receipt.delivery,
                    )
                },
            ),
            "projections" to page.map { fact ->
                val projection = kifTee?.projection(fact.factId)
                linkedMapOf(
                    "partition" to fact.factId.partitionId, "id" to fact.factId.localId,
                    "versionCid" to fact.versionCid.value,
                    "tracked" to (projection != null),
                    "matchesFactSnapshot" to (projection?.let { it == PlaneFacts.toKif(fact) }),
                    "tuples" to projection?.map { expr ->
                        linkedMapOf(
                            "kif" to expr.toKifString(), "held" to bank?.contains(expr),
                            "atoms" to ((expr as? KifExpr.ListExpr)?.elements?.mapNotNull { (it as? KifExpr.Atom)?.token } ?: emptyList<String>()),
                        )
                    },
                )
            },
            "ontology" to subclasses?.let { snap ->
                linkedMapOf(
                    "revision" to snap.revision, "byteSize" to snap.byteSize, "containers" to snap.containers,
                    "directEdges" to snap.directEdges.map { listOf(it.first, it.second) },
                    "nodes" to snap.nodes.map { node ->
                        linkedMapOf("name" to node.name, "nodeIndex" to node.nodeIndex, "preorderId" to node.preorderId,
                            "ancestorIds" to node.ancestorIds, "descendantIds" to node.descendantIds)
                    },
                )
            },
            "semantic" to semantic?.let { snap ->
                val assertions = snap.assertions.view.associateBy { it.angular }
                linkedMapOf(
                    "offered" to snap.offered, "capacity" to snap.capacity, "dropped" to (snap.offered - snap.firings.size),
                    "rules" to snap.rules.view.map { r ->
                        linkedMapOf("ruleCid" to r.ruleCid.value, "antecedent" to r.antecedent, "consequent" to r.consequent,
                            "copula" to r.copula.name, "provenanceCid" to r.provenanceCid)
                    },
                    "assertions" to snap.assertions.view.map { a ->
                        linkedMapOf("angular" to a.angular.toString(), "subject" to a.subject, "object" to a.obj,
                            "relation" to a.relation.name, "positive" to a.evidence.positive, "negative" to a.evidence.negative)
                    },
                    "firings" to snap.firings.view.map { f ->
                        linkedMapOf("firingCid" to f.firingCid.value, "ruleCid" to f.sourceRuleCid.value,
                            "matchedAngular" to f.matched.angular.toString(), "matchedSubject" to f.matched.subject,
                            "consequentAngular" to f.consequentAngular.toString(), "antecedent" to f.rule.antecedent,
                            "consequent" to f.rule.consequent, "positive" to f.support.positive, "negative" to f.support.negative,
                            "floored" to f.floored, "dependent" to true,
                            "resident" to assertions.containsKey(f.consequentAngular))
                    },
                )
            },
            "classifier" to classifier?.let { sumo ->
                linkedMapOf(
                    "stats" to sumo.stats, "containers" to sumo.shapeHistogram(),
                    "terms" to sumo.terms.view.map { term ->
                        linkedMapOf("name" to term, "preorderId" to sumo.classId(term)?.value,
                            "masks" to SumoMask.entries.associate { it.name to sumo.mask(term, it).toIntArray().toList() })
                    },
                    "domains" to sumo.domainSlots.view.map { slot ->
                        val predicate = slot.a.substringBeforeLast('/')
                        val argument = slot.a.substringAfterLast('/').toInt()
                        linkedMapOf("predicate" to predicate, "argument" to argument, "class" to slot.b,
                            "subclass" to sumo.domainIsSubclass(predicate, argument))
                    },
                    "ranges" to sumo.rangeSlots.view.map { slot ->
                        linkedMapOf("predicate" to slot.a, "class" to slot.b, "subclass" to sumo.rangeIsSubclass(slot.a))
                    },
                )
            },
        )
    }

    // ── the selection ───────────────────────────────────────────────────

    /**
     * What a request asks for. `key` is folded into `field`/`value` so one
     * filter serves both spellings; a `key` without a [PlaneFacts.KEY] field on
     * the fact still matches through [PlaneFacts.keyOf] (localId fallback).
     */
    data class Selection(val partition: String?, val field: String?, val value: String?, val key: String?) {

        fun select(all: List<ReteStoredFact>): List<ReteStoredFact> = all.filter(::admits)

        fun admits(f: ReteStoredFact): Boolean {
            if (partition != null && f.factId.partitionId != partition) return false
            if (key != null && PlaneFacts.keyOf(f).second != key) return false
            if (field != null) {
                val v = f.fields[field] ?: return false
                if (value != null && !matches(v, value)) return false
            }
            return true
        }

        companion object {
            fun of(q: Map<String, String>): Selection = Selection(
                partition = q["partition"]?.takeIf { it.isNotEmpty() },
                field = q["field"]?.takeIf { it.isNotEmpty() },
                value = q["value"],
                key = q["key"]?.takeIf { it.isNotEmpty() },
            )
        }
    }

    companion object {
        /** True when a stored field value is the query text or renders to it. */
        fun matches(stored: Any, wanted: String): Boolean =
            stored == wanted || renderScalar(stored) == wanted

        /** A field value as the text a query would spell it: strings as-is, numbers/booleans by `toString`, a [ContentId] by its `sha256:` text, anything else by its canonical JSON. */
        fun renderScalar(v: Any): String = when (v) {
            is String -> v
            is Number, is Boolean -> v.toString()
            is ContentId -> v.value
            else -> PlaneFacts.canonicalJson(v)
        }

        /** One fact as the JSON row `/api/rete/facts` answers with. */
        fun factRow(f: ReteStoredFact): Map<String, Any?> = linkedMapOf(
            "partition" to f.factId.partitionId,
            "id" to f.factId.localId,
            "versionCid" to f.versionCid.value,
            "fields" to jsonable(f.fields),
        )

        /** One production as the row `/api/lcnc/rdf/align` prints — ruleId, salience, `field=value` interests. */
        fun productionRow(pr: ReteProduction): Map<String, Any?> {
            val ints = pr.interests
            return linkedMapOf(
                "ruleId" to pr.ruleId,
                "salience" to pr.salience,
                "interests" to (0 until ints.size).map { "${ints[it].a}=${ints[it].b}" },
            )
        }

        /**
         * A field map as [JsonSupport.stringify] should see it: a [ContentId]
         * becomes its `sha256:` text (its `toString` is the data-class form),
         * maps and lists recurse, JSON scalars pass through, anything else
         * prints through `toString`.
         */
        fun jsonable(value: Any?): Any? = when (value) {
            null, is String, is Number, is Boolean -> value
            is ContentId -> value.value
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>(value.size)
                for ((k, v) in value) out[k.toString()] = jsonable(v)
                out
            }
            is Iterable<*> -> value.map(::jsonable)
            is Array<*> -> value.map(::jsonable)
            else -> value.toString()
        }

        private fun query(path: String): Map<String, String> =
            borg.trikeshed.relaxfactory.CouchHttpSurface.parseQuery(path.substringAfter('?', ""))

        private fun turtle(body: String): JvmKanbanServer.HttpResponse =
            JvmKanbanServer.HttpResponse(200, body, contentType = "text/turtle; charset=utf-8")

        private fun json(value: Any?, status: Int = 200): JvmKanbanServer.HttpResponse =
            JvmKanbanServer.HttpResponse(status, JsonSupport.stringify(value))
    }
}
