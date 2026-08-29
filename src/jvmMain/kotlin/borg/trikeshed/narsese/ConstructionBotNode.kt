package borg.trikeshed.narsese

import borg.trikeshed.jules.BrainClient
import borg.trikeshed.job.CasStore
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.parse.json.JsonSupport
import borg.trikeshed.dag.ReteNetwork
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * Production `read.construct` bot seat. The model proposes JSON tuples; the commonMain
 * ConstructionReadingLoop immediately claim-checks them against CAS and owns every
 * gate/fold/landing step. The one admitted slop is BrainClient's existing
 * List<Pair<role,text>> API boundary — no paired state escapes this adapter.
 */
object ConstructionBotNode {
    fun runner(
        brain: BrainClient,
        muxContext: CoroutineContext,
        cas: CasStore,
        bag: BeliefBagElement,
        rete: ReteNetwork,
        kifSink: (String) -> Unit = {},
    ): LcncNodeRunner = LcncNodeRunner { node, inputs ->
        val lines = parseLines(inputs["lines"], cas)
        require(lines.isNotEmpty()) { "read.construct requires lines" }
        val bot = ConstructionBot {
            val prompt = buildPrompt(lines)
            val raw = withContext(muxContext) {
                brain.chat(
                    messages = listOf(
                        "system" to "Extract only explicit causal constructions. Return JSON: {\"constructions\":[{\"subject\":string,\"relation\":\"causes|results_in|leads_to|because|therefore|if_then\",\"object\":string,\"polarity\":boolean,\"evidenceCid\":\"sha256:...\",\"dependency\":\"nsubj|dobj|nmod|acl|advcl:because|mark:if|advcl:if|cc:therefore|neg\"}]}. Never invent a CID or a relation absent from the cited line.",
                        "user" to prompt,
                    ),
                    // 1024 starved reasoning-style roster entries (e.g. gpt-oss-120b) before
                    // they ever emitted the requested JSON — confirmed live: the whole budget
                    // went to visible chain-of-thought ("We need to extract...") and the call
                    // never reached the answer. 4096 gives room for reasoning + the JSON.
                    maxTokens = node.params["maxTokens"]?.toIntOrNull() ?: 4096,
                    temperature = 0.0,
                    contextId = "read.construct:${node.id}",
                )
            }
            parseProposals(raw)
        }
        val loop = ConstructionReadingLoop(bot, cas, bag, rete, kifSink)
        val receipt = loop.read(lines.size j { i: Int -> lines[i] })
        mapOf(
            "accepted" to (0 until receipt.accepted.size).map { i -> constructionMap(receipt.accepted[i]) },
            "refused" to (0 until receipt.refused.size).map { i -> mapOf(
                "construction" to constructionMap(receipt.refused[i].construction),
                "reason" to receipt.refused[i].reason,
            ) },
            "aggregates" to (0 until receipt.aggregates.size).map { i -> mapOf(
                "subject" to receipt.aggregates[i].subject,
                "relation" to receipt.aggregates[i].relation,
                "object" to receipt.aggregates[i].obj,
                "count" to receipt.aggregates[i].count,
                "cid" to receipt.aggregates[i].identity.value,
            ) },
        )
    }

    private fun parseLines(value: Any?, cas: CasStore): List<ConstructionSourceLine> {
        val raw = value as? List<*> ?: listOfNotNull(value)
        val out = ArrayList<ConstructionSourceLine>(raw.size)
        for (item in raw) {
            when (item) {
                is String -> {
                    val cid = cas.put(item.encodeToByteArray())
                    out += ConstructionSourceLine(cid, item)
                }
                is Map<*, *> -> {
                    val text = item["text"]?.toString() ?: continue
                    val supplied = item["cid"]?.toString()
                    val cid = supplied?.let { runCatching { borg.trikeshed.job.ContentId(it) }.getOrNull() }
                        ?: cas.put(text.encodeToByteArray())
                    // Claim-check identity: a supplied CID must name these exact bytes.
                    require(cas.get(cid)?.contentEquals(text.encodeToByteArray()) == true) { "line cid does not name supplied text" }
                    out += ConstructionSourceLine(cid, text)
                }
            }
        }
        return out
    }

    private fun buildPrompt(lines: List<ConstructionSourceLine>): String = buildString {
        append("LINES (cite evidenceCid verbatim):\n")
        for (line in lines) append(line.cid.value).append('\t').append(line.text.replace('\n', ' ')).append('\n')
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseProposals(raw: String): borg.trikeshed.lib.Series<CausalConstruction> {
        val json = stripTrailingCommas(
            raw.substringAfter("```json", raw).substringAfter("```", raw).substringBeforeLast("```", raw).trim(),
        )
        val parsed = runCatching { JsonSupport.parse(json) }.getOrElse {
            error("read.construct bot JSON parse failed: ${it.message} — raw response: ${raw.take(2000)}")
        }
        val root = parsed as? Map<*, *>
            ?: error("read.construct bot returned non-object JSON (got ${parsed?.let { it::class.simpleName } ?: "null"}) — raw response: ${raw.take(2000)}")
        val rows = root["constructions"] as? List<*> ?: emptyList<Any?>()
        val out = ArrayList<CausalConstruction>()
        for (row in rows) {
            val m = row as? Map<*, *> ?: continue
            val subject = m["subject"]?.toString() ?: continue
            val relation = m["relation"]?.toString() ?: continue
            val obj = (m["object"] ?: m["obj"])?.toString() ?: continue
            val cid = runCatching { borg.trikeshed.job.ContentId(m["evidenceCid"]?.toString() ?: "") }.getOrNull() ?: continue
            out += CausalConstruction(
                subject, relation, obj,
                polarity = m["polarity"] as? Boolean ?: m["polarity"]?.toString()?.toBoolean() ?: true,
                evidenceCid = cid,
                dependency = m["dependency"]?.toString() ?: StanfordDependency.NSUBJ,
            )
        }
        return out.size j { i: Int -> out[i] }
    }

    /**
     * Small models frequently emit a trailing comma before `}`/`]` in
     * structured JSON output (no strict-JSON-mode enforcement upstream).
     * [borg.trikeshed.parse.json.Json]'s object parser requires every
     * comma-delimited segment to open on a quoted key, so an unguarded
     * trailing comma throws "malformed open quote" on the resulting empty
     * segment. Quote-aware: never touches a comma inside a string value.
     */
    internal fun stripTrailingCommas(json: String): String {
        val sb = StringBuilder(json.length)
        var inString = false
        var escaped = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (inString) {
                sb.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                i++
                continue
            }
            when (c) {
                '"' -> { inString = true; sb.append(c); i++ }
                ',' -> {
                    var k = i + 1
                    while (k < json.length && json[k].isWhitespace()) k++
                    // Drop the comma only when it is truly trailing (next token closes
                    // the enclosing object/array); otherwise keep it.
                    if (k < json.length && (json[k] == '}' || json[k] == ']')) i++ else { sb.append(c); i++ }
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun constructionMap(c: CausalConstruction): Map<String, Any?> = mapOf(
        "subject" to c.subject, "relation" to c.relation, "object" to c.obj,
        "polarity" to c.polarity, "evidenceCid" to c.evidenceCid.value, "dependency" to c.dependency,
    )
}
