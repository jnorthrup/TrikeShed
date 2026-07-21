with open("src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagCausalGraph.kt", "r") as f:
    text = f.read()

import re

new_class = """
/**
 * CAS-backed causal graph logic.
 * Every causal node is a Confix doc `{kind: "causal-node", causalKey: "...", deps: [CID...], payload: {...}}`.
 */
class CasBackedCausalGraph(val casStore: CasStore) {
    var rootCid: ContentId? = null
        private set

    fun snapshotRoot(cid: ContentId) {
        rootCid = cid
    }

    fun submitNode(
        causalKey: String,
        deps: List<ContentId>,
        payload: String
    ): ContentId {
        val depsJson = deps.joinToString(",") { "\\"${it.value}\\"" }
        val docJson = \"\"\"{"kind":"causal-node", "causalKey":"$causalKey", "deps":[$depsJson], "payload":$payload}\"\"\"
        val newCid = casStore.put(docJson.encodeToByteArray())
        snapshotRoot(newCid) // COW: Update root on every edit
        return newCid
    }

    fun traverse(startCid: ContentId): List<ContentId> {
        val result = mutableListOf<ContentId>()
        val visited = mutableSetOf<ContentId>()

        fun recurse(cid: ContentId) {
            if (!visited.add(cid)) return
            result.add(cid)

            try {
                borg.trikeshed.job.project(cid, casStore, borg.trikeshed.job.CausalNode)
                
                val bytes = casStore.get(cid) ?: return
                val syntax = if (bytes.isNotEmpty() && bytes[0].toInt().toChar() in setOf('{', '[', '"')) borg.trikeshed.parse.confix.Syntax.JSON else borg.trikeshed.parse.confix.Syntax.CBOR
                val doc = borg.trikeshed.parse.confix.confixDoc(bytes, syntax)
                val depsRaw = doc.value("deps")

                if (depsRaw is Iterable<*>) {
                    depsRaw.forEach {
                        if (it is String) {
                            recurse(ContentId.of(it.encodeToByteArray()))
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        recurse(startCid)
        return result
    }
}
"""

if "class CasBackedCausalGraph" in text:
    text = re.sub(r'class CasBackedCausalGraph.*', new_class.strip(), text, flags=re.DOTALL)
else:
    text += new_class

with open("src/commonMain/kotlin/borg/trikeshed/dag/BlackboardDagCausalGraph.kt", "w") as f:
    f.write(text)

