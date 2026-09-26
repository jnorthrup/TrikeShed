package borg.trikeshed.web.pages

/** The fields of a board row the kanban tree and change diff read. */
class KanbanNode(
    val id: String,
    val parent: String?,
    val dependencies: List<String>,
    val order: Double,
    val status: String,
    val owner: String?,
    val revision: String,
)

/**
 * The tree of work on one board: `parent` is the row field the store keeps at submit; a board
 * that predates it still shows the family because the fan-out worker names children
 * `<parent>-m<i>` and lists them as the parent's dependencies.
 */
class KanbanTree(val byId: Map<String, KanbanNode>, idCompare: (String, String) -> Int) {
    val parentOf: LinkedHashMap<String, String> = LinkedHashMap()
    val childrenOf: LinkedHashMap<String, MutableList<KanbanNode>> = LinkedHashMap()

    init {
        for (it in byId.values) {
            val p = it.parent
            if (!p.isNullOrEmpty() && byId.containsKey(p) && p != it.id) parentOf[it.id] = p
        }
        for (it in byId.values) {
            for (d in it.dependencies) {
                if (!parentOf.containsKey(d) && byId.containsKey(d) && d != it.id && d.startsWith(it.id + "-m")) parentOf[d] = it.id
            }
        }
        for ((child, parent) in parentOf) childrenOf.getOrPut(parent) { mutableListOf() }.add(byId.getValue(child))
        for (kids in childrenOf.values) {
            kids.sortWith { a, b ->
                val d = a.order - b.order
                if (d != 0.0 && !d.isNaN()) (if (d < 0) -1 else 1) else idCompare(a.id, b.id)
            }
        }
    }

    fun rootOf(id: String): String {
        var cur = id
        var hops = 0
        while (parentOf.containsKey(cur) && hops++ < 32) cur = parentOf.getValue(cur)
        return cur
    }

    /**
     * Children follow their parent inside a lane (indented); every other card keeps its own order.
     * A child whose parent sits in another lane stays where its order puts it.
     */
    fun laneOrder(items: List<KanbanNode>): List<KanbanNode> {
        val lane = items.mapTo(HashSet()) { it.id }
        val out = ArrayList<KanbanNode>()
        val seen = HashSet<String>()
        fun emit(it: KanbanNode) {
            if (!seen.add(it.id)) return
            out.add(it)
            for (kid in childrenOf[it.id].orEmpty()) if (kid.id in lane) emit(kid)
        }
        for (it in items) {
            val parent = parentOf[it.id]
            if (parent != null && parent in lane) continue
            emit(it)
        }
        for (it in items) if (it.id !in seen) emit(it)
        return out
    }

    companion object {
        /** djb2 over the first UTF-16 unit of each code point, uint32, mod 360. */
        fun hue(id: String): Int {
            var h = 5381L
            var i = 0
            while (i < id.length) {
                val c = id[i]
                val shifted = (h.toInt() shl 5).toLong()
                h = (shifted + h + c.code) and 0xFFFFFFFFL
                i += if (c.isHighSurrogate() && i + 1 < id.length && id[i + 1].isLowSurrogate()) 2 else 1
            }
            return (h % 360).toInt()
        }
    }
}

/** Board-level decisions of the kanban page. */
object Kanban {
    val LANES: List<String> = listOf("triage", "todo", "ready", "running", "blocked", "review", "done")
    const val REFRESH_DEBOUNCE_MS = 150
    const val FLASH_MS = 700
    const val STAGGER_MS = 120
    const val STAGGER_SLOTS = 8
    const val TIMELINE_CAP = 40
    const val BURST_PER_SECOND = 12
    const val BURST_FLUSH_MS = 900

    /** new / moved / claimed / revised, or null when nothing a viewer sees moved. */
    fun changeKind(prev: KanbanNode?, next: KanbanNode): String? = when {
        prev == null -> "new"
        prev.status != next.status -> "moved"
        (prev.owner ?: "") != (next.owner ?: "") && !next.owner.isNullOrEmpty() -> "claimed"
        prev.revision != next.revision -> "revised"
        else -> null
    }

    fun escapeHtml(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** Whole when it fits, else head…tail so a fan-out child (`<parent>-m<i>`) keeps its suffix. */
    fun shortId(s: String): String = if (s.length <= 14) s else s.substring(0, 8) + "…" + s.substring(s.length - 4)
}
