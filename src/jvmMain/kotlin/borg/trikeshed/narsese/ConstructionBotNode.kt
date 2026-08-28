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
                    maxTokens = node.params["maxTokens"]?.toIntOrNull() ?: 1024,
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
        val json = raw.substringAfter("```json", raw).substringAfter("```", raw).substringBeforeLast("```", raw).trim()
        val root = JsonSupport.parse(json) as? Map<*, *> ?: error("read.construct bot returned non-object JSON")
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

    private fun constructionMap(c: CausalConstruction): Map<String, Any?> = mapOf(
        "subject" to c.subject, "relation" to c.relation, "object" to c.obj,
        "polarity" to c.polarity, "evidenceCid" to c.evidenceCid.value, "dependency" to c.dependency,
    )
}
