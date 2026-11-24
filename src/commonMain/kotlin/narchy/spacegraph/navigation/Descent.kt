package narchy.spacegraph.navigation

import borg.trikeshed.lib.*

// Port of upstream src/navigation/descent.ts at 125b369.
// Cross-scale descent as host policy: the registry walks policies; the host supplies kinds and relationships.

data class DescentNode(val id: String, val kind: String, val children: List<String>? = null)

data class DescentContext(val nodes: Map<String, DescentNode>)

/** Child ids to descend into, or null when the policy does not apply. */
class DescentPolicy(
    val id: String,
    /** Node kinds this policy descends; null means every kind. */
    val kinds: List<String>? = null,
    val children: (DescentNode, DescentContext) -> List<String>?,
)

/** A policy that follows the next kind in a host-defined ladder. */
fun kindDescent(id: String, kinds: List<String>): DescentPolicy =
    DescentPolicy(id, kinds) { node, context ->
        val next = kinds.getOrNull(kinds.indexOf(node.kind) + 1)
        if (next == null) null
        else (node.children ?: emptyList()).filter { context.nodes[it]?.kind == next }
    }

class DescentRegistry(policies: List<DescentPolicy> = emptyList()) {
    private val policies: MutableList<DescentPolicy> = policies.toMutableList()

    val ids: List<String> get() = policies.map { it.id }

    fun register(policy: DescentPolicy): DescentRegistry {
        policies.add(policy)
        return this
    }

    /** The first matching policy's children, limited to ids the host model actually contains. */
    fun childrenOf(node: DescentNode, context: DescentContext): List<String> {
        val policy = policies.firstOrNull { entry ->
            (entry.kinds == null || node.kind in entry.kinds) && entry.children(node, context) != null
        } ?: return emptyList()
        return (policy.children(node, context) ?: emptyList()).filter { context.nodes.containsKey(it) }
    }

    /** A root-inclusive path from one node to another, or null when no policy connects them. */
    fun path(from: String, to: String, context: DescentContext): List<String>? {
        val queue = ArrayDeque<List<String>>()
        queue.addLast(listOf(from))
        val seen = mutableSetOf(from)
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            val current = path.last()
            if (current == to) return path
            val node = context.nodes[current] ?: continue
            for (child in childrenOf(node, context)) {
                if (!seen.add(child)) continue
                queue.addLast(path + child)
            }
        }
        return null
    }
}
