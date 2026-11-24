package narchy.spacegraph.layout

// Port of upstream src/layout/scope.ts at 125b369.
// Recursive scopes. A subgraph is a first-class body in its parent's layout: the parent solves for a portal that
// represents the whole child, while the child solves its own interior with its own strategy. Identity is
// path-qualified, so a node keeps its name when it moves between scopes.
// This module owns the tree, its validation and its references. Geometry lives in ScopeLayout.kt.

import narchy.spacegraph.Vec3
import narchy.spacegraph.util.appendTo

enum class ScopeStrategy { FORCE, GRID, TREE }

enum class ScopeErrorCode {
    INVALID_ID,
    DUPLICATE_SCOPE,
    UNKNOWN_PARENT,
    CYCLE,
    DUPLICATE_NODE,
    UNKNOWN_PORTAL,
    UNKNOWN_ENDPOINT,
}

class ScopeError(val code: ScopeErrorCode, message: String) : Exception(message)

data class ScopeNode(
    val id: String,
    /** The footprint the solvers use for collision and bounds. */
    val size: Pair<Double, Double>? = null,
    /** Hierarchy depth, recorded in Z. */
    val depth: Int? = null,
    /** Opens a direct child scope here; this node becomes the child's portal. */
    val scope: String? = null,
    /** Grid strategy: the column this node belongs to. */
    val lane: String? = null,
    /** Grid strategy: an explicit row index. */
    val row: Int? = null,
    /** Tree strategy: the id of the parent node inside this scope. */
    val parent: String? = null,
)

data class ScopeEdge(
    val id: String,
    /** A local node id, a direct child scope id, or an absolute qualified path. */
    val source: String,
    val target: String,
    val kind: String? = null,
)

data class ScopeSpec(
    val id: String,
    val parent: String? = null,
    val nodes: List<ScopeNode>,
    val edges: List<ScopeEdge> = emptyList(),
    val strategy: ScopeStrategy? = null,
    /** Frame offset in parent space, applied after the portal is placed. */
    val offset: Vec3? = null,
    /** Content scale relative to the parent. */
    val scale: Double? = null,
)

class ScopeEntry(
    val spec: ScopeSpec,
    /** Path-qualified id, e.g. `session/turn-1`. */
    val path: String,
    val depth: Int,
    val parent: ScopeEntry?,
    val children: MutableList<ScopeEntry>,
    val nodes: List<ScopeNode>,
    val edges: List<ScopeEdge>,
)

class ScopeTree(
    val roots: List<ScopeEntry>,
    val byPath: Map<String, ScopeEntry>,
    /** Qualified node id -> the path of the scope that owns it. */
    val nodePaths: Map<String, String>,
)

private fun isSafeId(id: String): Boolean = id.isNotEmpty() && !id.contains('/')

/**
 * Validate and index a scope forest. Every error is raised here, at the boundary, so layout can assume the tree is
 * acyclic, ids are unique and every reference resolves.
 */
fun buildScopeTree(specs: List<ScopeSpec>): ScopeTree {
    val byId = LinkedHashMap<String, ScopeSpec>()

    for (spec in specs) {
        if (!isSafeId(spec.id)) {
            throw ScopeError(ScopeErrorCode.INVALID_ID, "scope id must be a non-empty string without '/': ${spec.id}")
        }
        if (byId.containsKey(spec.id)) {
            throw ScopeError(ScopeErrorCode.DUPLICATE_SCOPE, "duplicate scope id: ${spec.id}")
        }
        byId[spec.id] = spec
    }

    val state = HashMap<String, Int>()
    fun checkChain(id: String, chain: List<String>) {
        val seen = state[id]
        if (seen == 2) return
        if (seen == 1) {
            throw ScopeError(ScopeErrorCode.CYCLE, "scope cycle: ${(chain + id).joinToString(" -> ")}")
        }
        val spec = byId[id] ?: throw ScopeError(ScopeErrorCode.UNKNOWN_PARENT, "unknown parent scope: $id")
        state[id] = 1
        spec.parent?.let { checkChain(it, chain + id) }
        state[id] = 2
    }
    for (id in byId.keys) checkChain(id, emptyList())

    val childrenOf = LinkedHashMap<String, MutableList<ScopeSpec>>()
    val roots = mutableListOf<ScopeSpec>()
    for (spec in specs) {
        if (spec.parent == null) {
            roots.add(spec)
            continue
        }
        appendTo(childrenOf, spec.parent, spec)
    }

    val byPath = LinkedHashMap<String, ScopeEntry>()
    val nodePaths = LinkedHashMap<String, String>()

    fun build(spec: ScopeSpec, parent: ScopeEntry?): ScopeEntry {
        val path = if (parent != null) "${parent.path}/${spec.id}" else spec.id
        val seenNodes = HashSet<String>()

        for (node in spec.nodes) {
            if (!isSafeId(node.id)) {
                throw ScopeError(ScopeErrorCode.INVALID_ID, "node id must be a non-empty string without '/': ${node.id}")
            }
            if (!seenNodes.add(node.id)) {
                throw ScopeError(ScopeErrorCode.DUPLICATE_NODE, "duplicate node ${node.id} in scope $path")
            }
            nodePaths["$path/${node.id}"] = path
        }

        val entry = ScopeEntry(
            spec = spec,
            path = path,
            depth = if (parent != null) parent.depth + 1 else 0,
            parent = parent,
            children = mutableListOf(),
            nodes = spec.nodes.toList(),
            edges = spec.edges,
        )
        byPath[path] = entry

        entry.children.addAll((childrenOf[spec.id] ?: emptyList<ScopeSpec>()).map { build(it, entry) })

        for (node in spec.nodes) {
            val opened = node.scope ?: continue
            if (entry.children.none { it.spec.id == opened }) {
                throw ScopeError(ScopeErrorCode.UNKNOWN_PORTAL, "node $path/${node.id} opens unknown child scope $opened")
            }
        }
        return entry
    }

    val builtRoots = roots.map { build(it, null) }

    val tree = ScopeTree(builtRoots, byPath, nodePaths)
    fun validateEdges(entry: ScopeEntry) {
        for (edge in entry.edges) {
            resolveScopeRef(tree, entry.path, edge.source)
            resolveScopeRef(tree, entry.path, edge.target)
        }
        for (child in entry.children) validateEdges(child)
    }
    for (root in builtRoots) validateEdges(root)

    return tree
}

/** Resolve a local node id, a direct child scope id, or an absolute qualified path. */
fun resolveScopeRef(tree: ScopeTree, path: String, ref: String): String {
    if (ref.contains('/')) {
        if (tree.byPath.containsKey(ref) || tree.nodePaths.containsKey(ref)) return ref
        val relative = "$path/$ref"
        if (tree.byPath.containsKey(relative) || tree.nodePaths.containsKey(relative)) return relative
        throw ScopeError(ScopeErrorCode.UNKNOWN_ENDPOINT, "unresolved endpoint $ref in scope $path")
    }
    val local = "$path/$ref"
    if (tree.byPath.containsKey(local) || tree.nodePaths.containsKey(local)) return local
    throw ScopeError(ScopeErrorCode.UNKNOWN_ENDPOINT, "unresolved endpoint $ref in scope $path")
}

/** The path of the scope that owns a qualified node id, or the scope id itself for a frame. */
fun scopeOf(tree: ScopeTree, id: String): String? {
    val direct = tree.byPath[id]
    if (direct != null) return direct.path
    return tree.nodePaths[id]
}

/** A scope and its ancestors, nearest first. Accepts a qualified node id. */
fun scopeAncestors(tree: ScopeTree, id: String): List<ScopeEntry> {
    val path = tree.nodePaths[id] ?: id
    val ancestors = mutableListOf<ScopeEntry>()
    var entry = tree.byPath[path]
    while (entry != null) {
        ancestors.add(entry)
        entry = entry.parent
    }
    return ancestors
}

/** A scope and all of its descendants in tree order. */
fun scopeDescendants(tree: ScopeTree, id: String): List<ScopeEntry> {
    val entry = tree.byPath[id] ?: return emptyList()
    val result = mutableListOf<ScopeEntry>()
    fun visit(current: ScopeEntry) {
        result.add(current)
        for (child in current.children) visit(child)
    }
    visit(entry)
    return result
}
