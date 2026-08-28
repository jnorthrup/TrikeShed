package borg.trikeshed.memory.ace

import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.job.ContentId
import borg.trikeshed.lcnc.LcncNodeRunner
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.lib.size
import borg.trikeshed.lib.toSeries
import borg.trikeshed.parse.json.JsonSupport

/**
 * Step K's LCNC citizenship: the context-assembly nodes are ordinary LCNC
 * runners, registered like any other family (the host composes them into
 * ModuleContext.lcncRunners). Running `context.assemble` mints a
 * `context-receipt/<chainHead>` blackboard key — the blackboard page's chunk
 * pane reads REAL receipts, not a string literal.
 */
object AceContextNodes {

    /**
     * `context.fold` — bullets JSON `[{"id":1,"content":"..."}]` (+ optional prior
     * playbook text on `playbook?`) → the deterministic canonical playbook text.
     * `context.assemble` — the four frame payloads → the rolling cache-identity
     * chain; outputs chainHead + per-frame cids; lands the context receipt.
     */
    fun registry(blackboard: ConfixBlackboard? = null): Map<String, LcncNodeRunner> = mapOf(
        "context.fold" to LcncNodeRunner { _, inputs ->
            val bulletsJson = inputs["bullets"]?.toString() ?: "[]"
            val parsed = (runCatching { JsonSupport.parse(bulletsJson) }.getOrNull() as? List<*>).orEmpty()
            val deltas = parsed.mapNotNull { raw ->
                val m = raw as? Map<*, *> ?: return@mapNotNull null
                val id = (m["id"] as? Number)?.toInt()
                    ?: m["id"]?.toString()?.toDoubleOrNull()?.toInt() ?: return@mapNotNull null
                val content = m["content"]?.toString() ?: return@mapNotNull null
                BulletId(id) j ContentId.of(content.encodeToByteArray())
            }
            val contentByCid = parsed.mapNotNull { raw ->
                val m = raw as? Map<*, *> ?: return@mapNotNull null
                val content = m["content"]?.toString() ?: return@mapNotNull null
                ContentId.of(content.encodeToByteArray()).value to content.encodeToByteArray()
            }.toMap()
            val folded = AcePlaybookFold.fold(emptyList<PlaybookBullet>().toSeries(), deltas.toSeries())
            val bytes = AcePlaybookFold.playbookBytes(folded) { cid -> contentByCid[cid.value] }
            mapOf("playbook" to bytes.decodeToString())
        },

        "context.assemble" to LcncNodeRunner { node, inputs ->
            val program = AceContextProgram.preset(
                toolsSystem = (inputs["toolsSystem"]?.toString() ?: "").encodeToByteArray(),
                playbookBase = (inputs["playbook"]?.toString() ?: "").encodeToByteArray(),
                envelope = (inputs["envelope"]?.toString() ?: "").encodeToByteArray(),
                volatileTail = (inputs["tail"]?.toString() ?: "").encodeToByteArray(),
            )
            val toolNames = (node.params["tools"] ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val config = AceContextProgram.Config(
                model = node.params["model"] ?: "",
                tools = toolNames.toSeries(),
                effort = node.params["effort"] ?: "medium",
            )
            val chain = AceContextProgram.assemble(program, config)
            val cids = ArrayList<String>(chain.size)
            for (i in 0 until chain.size) cids.add(chain[i].cid.value)
            val head = cids.last()
            blackboard?.put(
                "context-receipt/${head.removePrefix("sha256:")}",
                mapOf(
                    "chainHead" to head,
                    "frames" to cids.size.toString(),
                    "cids" to JsonSupport.stringify(cids),
                    "configSalt" to config.salt().value,
                ),
                "ace",
            )
            mapOf("chain" to JsonSupport.stringify(cids), "chainHead" to head)
        },
    )
}
