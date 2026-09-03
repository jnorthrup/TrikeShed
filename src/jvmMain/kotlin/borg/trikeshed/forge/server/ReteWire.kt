package borg.trikeshed.forge.server

import borg.trikeshed.dag.PlaneFacts
import borg.trikeshed.dag.ReteNetwork
import borg.trikeshed.dag.ReteProduction
import borg.trikeshed.dag.ReteStoredFact
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size
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
class ReteWire(private val network: ReteNetwork) {

    suspend fun route(
        method: String,
        path: String,
        text: String,
        respond: (suspend (ByteArray) -> Unit)?,
    ): JvmKanbanServer.HttpResponse? {
        if (method != "GET") return null
        val p = path.substringBefore('?')
        return when {
            p == "/api/rete/facts" -> {
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

            p == "/api/facts/rdf" -> {
                val selection = Selection.of(query(path))
                turtle(PlaneFacts.toTurtle(selection.select(network.snapshot())))
            }

            p == "/api/rete/productions" -> {
                val prods = network.productions.all()
                json(linkedMapOf("count" to prods.size, "productions" to prods.map(::productionRow)))
            }

            else -> null
        }
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
