package borg.trikeshed.lcnc

import borg.trikeshed.lib.Series
import kotlinx.coroutines.currentCoroutineContext
import borg.trikeshed.lib.get
import borg.trikeshed.lib.size

/**
 * The read side of the stored prompt set, as a seam a runner can hold in
 * commonMain: the daemon binds it to `PromptStore` (jvmMain), a test binds it
 * to [InMemoryPromptReads]. `prompt.save` is the write and lives with the store.
 */
interface PromptReads {
    suspend fun get(name: String): PromptDocument?
    suspend fun byCid(cid: String): PromptDocument?
    fun heads(): List<PromptHead>
}

/** Map-backed prompt reads — the zero-thread test seam; also what a seed-only daemon holds. */
class InMemoryPromptReads(
    private val clock: () -> Long = { 0L },
) : PromptReads {
    private val heads = linkedMapOf<String, PromptDocument>()
    private val versions = linkedMapOf<String, PromptDocument>()
    private val savedAt = linkedMapOf<String, Long>()

    /** Install [doc] as the head of its name and remember the version by cid; returns the cid. */
    fun put(doc: PromptDocument): String {
        heads[doc.name] = doc
        versions[doc.cid] = doc
        savedAt[doc.name] = clock()
        return doc.cid
    }

    override suspend fun get(name: String): PromptDocument? = heads[name]
    override suspend fun byCid(cid: String): PromptDocument? = versions[cid]
    override fun heads(): List<PromptHead> = heads.values.map {
        PromptHead(it.name, it.cid, it.previousCid, it.role, it.variables, it.tags, it.text.length, savedAt[it.name] ?: 0L)
    }
}

/**
 * The prompt legos that read or transform — pure over [PromptReads], so they
 * run wherever commonMain runs (the JVM daemon, the JS bundle, the native canary).
 *
 *   prompt.get     name (wire or param) → the head version's text, cid, name, role
 *   prompt.render  template + args      → the text with every `{{variable}}` bound
 *   prompt.list    → the heads, as the picklist `prompt.get` fills from
 *
 * A wire-carried input wins over the param of the same name (the `SubVmLegos.inputStrings` rule).
 */
object PromptNodes {
    const val GET = "prompt.get"
    const val RENDER = "prompt.render"
    const val LIST = "prompt.list"
    const val SAVE = "prompt.save"

    fun servedTypes(): Set<String> = setOf(GET, RENDER, LIST)

    /**
     * Which stored prompt versions a run READ: every `prompt.get` in [programs]
     * (rings included) paired with the name and cid its recorded output carries.
     * The receipt records this beside `programVersions`, so a run names its
     * prompts the way it names its program.
     */
    fun promptVersionsOf(programs: Collection<LcncProgram>, outputs: Map<String, Map<String, Any?>>): Map<String, String> {
        val ids = linkedSetOf<String>()
        fun walk(nodes: Series<LcncNode>) {
            for (i in 0 until nodes.size) {
                val n = nodes[i]
                if (n.type == GET) ids.add(n.id)
                walk(n.children)
            }
        }
        programs.forEach { walk(it.nodes) }
        val out = linkedMapOf<String, String>()
        for (id in ids) {
            val o = outputs[id] ?: outputs.entries.firstOrNull { it.key.endsWith("/$id") }?.value ?: continue
            val name = o["name"]?.toString() ?: continue
            val cid = o["cid"]?.toString() ?: continue
            out[name] = cid
        }
        return out
    }

    fun registry(reads: PromptReads): Map<String, LcncNodeRunner> = mapOf(
        GET to boundLcnc(PromptReadsKey(reads)) { service, node, inputs ->
            val name = (inputs["name"] ?: inputs["name?"])?.toString()?.takeIf { it.isNotBlank() }
                ?: node.params["name"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("prompt.get: no prompt name — wire one in or set the name param")
            val doc = service.value.get(name) ?: throw IllegalArgumentException("prompt.get: no stored prompt named '$name'")
            currentCoroutineContext()[LcncConsumedLedger]?.consumed(LcncConsumedLedger.PROMPT, doc.name, doc.cid)
            mapOf("text" to doc.text, "cid" to doc.cid, "name" to doc.name, "role" to doc.role)
        },
        RENDER to LcncNodeRunner { node, inputs ->
            val template = (inputs["template"] ?: node.params["template"])?.toString()
                ?: throw IllegalArgumentException("prompt.render: no template")
            @Suppress("UNCHECKED_CAST")
            val args = (inputs["args"] ?: inputs["args?"]) as? Map<String, Any?> ?: emptyMap()
            mapOf("text" to PromptTemplate.render(template, args), "variables" to PromptTemplate.variables(template))
        },
        LIST to boundLcnc(PromptReadsKey(reads)) { service, _, _ ->
            val heads = service.value.heads()
            mapOf("prompts" to heads.map { it.toMap() }, "count" to heads.size)
        },
    )
}
