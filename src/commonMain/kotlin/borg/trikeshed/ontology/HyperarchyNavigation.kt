package borg.trikeshed.ontology

import borg.trikeshed.lib.*

/**
 * Moving through a [SumoHyperarchy] from the class in hand: up to a parent (every parent is offered, [parents]), down
 * to a child ([children]), across to the next or previous sibling under the parent it was reached by ([sibling]), and
 * back along the moves taken ([back]). The state is [path], root first, each class a child of the one before it; the
 * empty path is the whole ground. [names] is the path's URL form and [restore] reads it back.
 */
class HyperarchyNavigation(val hyperarchy: SumoHyperarchy) {
    var path: List<Int> = emptyList()
    /** The paths left by each move, latest last. */
    val trail = ArrayList<List<Int>>()

    /** The class in hand, or -1 on the whole ground. */
    val current: Int get() = path.lastOrNull() ?: -1

    /** The path's class names, root first. */
    val names: List<String> get() = path.map { hyperarchy.names[it] }

    /** Every parent of the class in hand, in the order the corpus declares them. */
    fun parents(): IntArray = if (current < 0) IntArray(0) else hyperarchy.parents[current]

    /** The children of the class in hand in name order; on the whole ground, the roots. */
    fun children(): IntArray = if (current < 0) hyperarchy.roots else hyperarchy.children[current]

    /** The children of the parent the class in hand was reached by (the roots for a root), in name order. */
    fun siblings(): IntArray = when {
        path.size >= 2 -> hyperarchy.children[path[path.size - 2]]
        path.size == 1 -> hyperarchy.roots
        else -> IntArray(0)
    }

    /** Up to [parent], one of [parents]: back along the path when it is the parent the class was reached by, else to its canonical path. */
    fun up(parent: Int): Boolean {
        if (parent !in parents()) return false
        move(if (path.size >= 2 && path[path.size - 2] == parent) path.dropLast(1) else canonical(parent))
        return true
    }

    /** Down to [child], one of [children]. */
    fun down(child: Int): Boolean {
        if (child !in children()) return false
        move(path + child)
        return true
    }

    /** [step] places along [siblings] (wrapping), under the same parent. */
    fun sibling(step: Int): Boolean {
        val s = siblings(); val i = s.indexOf(current)
        if (i < 0 || s.size < 2) return false
        move(path.dropLast(1) + s[((i + step) % s.size + s.size) % s.size])
        return true
    }

    /** To class [c] by its canonical path (a class picked on the ground or found by name). */
    fun goto(c: Int) { move(if (c < 0) emptyList() else canonical(c)) }

    /** Undoes the last move. */
    fun back(): Boolean {
        if (trail.isEmpty()) return false
        path = trail.removeAt(trail.size - 1)
        return true
    }

    /** Takes the path [names] (a URL's), forgetting the moves; false, and nothing changed, unless it is a path of the hyperarchy. */
    fun restore(names: List<String>): Boolean {
        val p = of(hyperarchy, names) ?: return false
        path = p; trail.clear()
        return true
    }

    fun canonical(c: Int): List<Int> = hyperarchy.path(c).view.toList()

    fun move(next: List<Int>) { trail.add(path); path = next }

    companion object {
        /** The path named by [names], root first, each a child of the one before; null unless the hyperarchy has it. */
        fun of(hyperarchy: SumoHyperarchy, names: List<String>): List<Int>? {
            val p = names.map { hyperarchy.index(it).takeIf { i -> i >= 0 } ?: return null }
            if (p.isNotEmpty() && p[0] !in hyperarchy.roots) return null
            for (k in 1 until p.size) if (p[k] !in hyperarchy.children[p[k - 1]]) return null
            return p
        }
    }
}
