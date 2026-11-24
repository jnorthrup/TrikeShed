package borg.trikeshed.ontology

import borg.trikeshed.lib.*
import narchy.spacegraph.CirclePacking
import narchy.spacegraph.Disc
import kotlin.math.*

/**
 * SUMO's subclass hyperarchy: a class has several parents. Built from the class names and the flat (sub, sup)
 * subclass edges over them ([of] reads both from a [SumoTaxonomy]; [wire] is the same pair for the browser).
 *
 * [parents] keeps a class's parents in the order the corpus declares them and [children] its children in name order.
 * Each class also has one canonical parent ([tree]): the parent nearest a root, the first by name of equals, so that
 * neither the homes nor the paths depend on the order the corpus declares its forms in.
 *
 * Homing: every class has a home on the ground, a disc that is a function of the hyperarchy and of nothing else (not
 * of what else is loaded, nor of order, zoom or time). A class's home holds its own room (radius [ROOM], where things
 * typed by the class stand) and the homes of its canonical children, packed with the room ([CirclePacking]); the
 * roots' homes and the untyped room are packed the same way into the whole ground. So a child's home lies inside its
 * canonical parent's, and no two classes share a home. [home] of a class path also places a class the hyperarchy
 * does not hold (an induced class): inside the room of the class before it in the path, at the slot its name
 * hashes to ([induced]), so it takes a home without moving any other.
 */
class SumoHyperarchy(val names: Array<String>, val edges: IntArray) {
    val size: Int get() = names.size
    val byName = HashMap<String, Int>(names.size * 2).also { m -> for (i in names.indices) m[names[i]] = i }

    /** Direct parents, in the order the corpus declares them. */
    val parents: Array<IntArray>
    /** Direct children, in name order. */
    val children: Array<IntArray>
    /** The canonical parent: nearest a root, the first by name of equals; -1 for a root. */
    val tree: IntArray
    /** Steps from a root along [tree]. */
    val depth: IntArray
    /** Classes under no other, in name order; a cycle no root reaches is rooted at its first class. */
    val roots: IntArray
    /** Every class, each after its canonical parent. */
    val order: IntArray

    init {
        val n = names.size
        val up = Array(n) { LinkedHashSet<Int>() }; val down = Array(n) { HashSet<Int>() }
        for (e in 0 until edges.size step 2) { val a = edges[e]; val b = edges[e + 1]; if (a != b) { up[a].add(b); down[b].add(a) } }
        val alphabetical = compareBy<Int> { names[it] }
        parents = Array(n) { up[it].toIntArray() }
        children = Array(n) { down[it].sortedWith(alphabetical).toIntArray() }
        depth = IntArray(n) { -1 }; tree = IntArray(n) { -1 }; order = IntArray(n)
        val rooted = ArrayList<Int>()
        var head = 0; var tail = 0
        fun reach(r: Int) {
            rooted.add(r); depth[r] = 0; order[tail++] = r
            while (head < tail) { val v = order[head++]; for (c in children[v]) if (depth[c] < 0) { depth[c] = depth[v] + 1; order[tail++] = c } }
        }
        for (r in (0 until n).filter { parents[it].isEmpty() }.sortedWith(alphabetical)) reach(r)
        for (i in 0 until n) if (depth[i] < 0) reach(i)
        roots = rooted.toIntArray()
        for (c in 0 until n) if (depth[c] > 0) tree[c] = parents[c].filter { depth[it] == depth[c] - 1 }.minWith(alphabetical)
    }

    /** The class named [name], or -1. */
    fun index(name: String): Int = byName[name] ?: -1

    /** The canonical path of class [c], root first. */
    fun path(c: Int): Series<Int> {
        val out = ArrayList<Int>()
        var at = c
        while (at >= 0) { out.add(at); at = tree[at] }
        out.reverse()
        return out.toSeries()
    }

    /** The canonical children of [c]: the children whose homes its home packs. */
    fun treeChildren(c: Int): IntArray = children[c].filter { tree[it] == c }.toIntArray()

    /** The classes and edges the browser rebuilds this hyperarchy from: `{classes: [...], subclass: [sub, sup, ...]}`. */
    fun wire(): Map<String, Any?> = _m["classes" j names.asList(), "subclass" j edges.asList()]

    // ── homing ──

    val homeX = DoubleArray(size); val homeY = DoubleArray(size); val homeR = DoubleArray(size)
    val roomX = DoubleArray(size); val roomY = DoubleArray(size)
    /** The room where a thing with no class stands. */
    val untyped: Disc
    /** The whole ground: every root's home and the untyped room. */
    val ground: Disc

    init {
        val n = size
        val offX = DoubleArray(n); val offY = DoubleArray(n); val roomOffX = DoubleArray(n); val roomOffY = DoubleArray(n)
        /** Packs a room of radius [ROOM] first (front-chain packing sets it beside the largest home), then the homes of
         *  [members], which it sorts largest first; returns the enclosing radius and the centers, the room's at index 0. */
        fun pack(members: IntArray): Join<Double, Join<DoubleArray, DoubleArray>> {
            val sorted = members.sortedWith(compareByDescending<Int> { homeR[it] }.thenBy { names[it] })
            for (k in sorted.indices) members[k] = sorted[k]
            val r = DoubleArray(members.size + 1) { (if (it == 0) ROOM else homeR[members[it - 1]]) + GAP / 2 }
            val x = DoubleArray(r.size); val y = DoubleArray(r.size)
            return CirclePacking.pack(r, x, y) j (x j y)
        }
        // Bottom up: every canonical child comes after its parent in [order].
        for (k in n - 1 downTo 0) {
            val c = order[k]; val members = treeChildren(c)
            val (enclosing, at) = pack(members); val (x, y) = at
            roomOffX[c] = x[0]; roomOffY[c] = y[0]
            for (m in members.indices) { offX[members[m]] = x[m + 1]; offY[members[m]] = y[m + 1] }
            homeR[c] = enclosing + RIM
        }
        val top = roots.copyOf()
        val (whole, at) = pack(top); val (x, y) = at
        untyped = Disc(x[0], y[0], ROOM)
        for (m in top.indices) { offX[top[m]] = x[m + 1]; offY[top[m]] = y[m + 1] }
        ground = Disc(0.0, 0.0, whole + RIM)
        for (c in order) {
            val p = tree[c]
            homeX[c] = (if (p < 0) 0.0 else homeX[p]) + offX[c]; homeY[c] = (if (p < 0) 0.0 else homeY[p]) + offY[c]
            roomX[c] = homeX[c] + roomOffX[c]; roomY[c] = homeY[c] + roomOffY[c]
        }
    }

    /** The home of class [c]: its room and the homes of its canonical children. */
    fun home(c: Int): Disc = Disc(homeX[c], homeY[c], homeR[c])

    /** The room of class [c]: where things typed by it stand. */
    fun room(c: Int): Disc = Disc(roomX[c], roomY[c], ROOM)

    /**
     * The home of the last class of [path], root first. A class the hyperarchy holds has its own home whatever path
     * reached it; one it does not hold (induced) lies in the room of the class before it ([induced]). The empty path
     * is the untyped room, and so is the room of anything before the first class held.
     */
    fun home(path: Series<String>): Disc = walk(path).a

    /** The room of the last class of [path]: a held class's room, an induced class's whole home. */
    fun room(path: Series<String>): Disc = walk(path).b

    fun walk(path: Series<String>): Join<Disc, Disc> {
        var at = untyped; var inside = untyped
        for (name in path.view) {
            val c = index(name)
            if (c >= 0) { at = home(c); inside = room(c) } else { at = induced(inside, name); inside = at }
        }
        return at j inside
    }

    companion object {
        /** Radius of every class's room. */
        const val ROOM = 1.0
        /** Clear space between two packed homes. */
        const val GAP = .12
        /** Border of a home beyond what it packs. */
        const val RIM = .08
        /** Slots of a room's sunflower an induced class may hash to, how far out they reach, and an induced home's share of the room. */
        const val SLOTS = 61
        const val SPREAD = .8
        const val INDUCED = .09
        val GOLDEN = PI * (3 - sqrt(5.0))

        /** A class not held, named [name], inside [room]: at the sunflower slot its name hashes to. */
        fun induced(room: Disc, name: String): Disc {
            val k = (mix(name) ushr 8).toDouble() / (1 shl 24) * SLOTS
            val d = room.r * SPREAD * sqrt((k + .5) / SLOTS); val a = k * GOLDEN
            return Disc(room.x + d * cos(a), room.y + d * sin(a), room.r * INDUCED)
        }

        /** FNV-1a over the UTF-8 bytes, finished by MurmurHash3's fmix32. */
        fun mix(name: String): Int {
            var h = 0x811c9dc5.toInt()
            for (b in name.encodeToByteArray()) { h = h xor (b.toInt() and 0xff); h *= 0x01000193 }
            h = h xor (h ushr 16); h *= 0x85ebca6b.toInt(); h = h xor (h ushr 13); h *= 0xc2b2ae35.toInt(); h = h xor (h ushr 16)
            return h
        }

        /** The hyperarchy of [t]'s classes, in term order, over its subclass edges. */
        fun of(t: SumoTaxonomy): SumoHyperarchy {
            val classOf = IntArray(t.names.size) { -1 }
            val names = ArrayList<String>()
            for (i in t.names.indices) if (t.isClass[i]) { classOf[i] = names.size; names.add(t.names[i]) }
            return SumoHyperarchy(names.toTypedArray(), IntArray(t.subclassEdges.size) { classOf[t.subclassEdges[it]] })
        }

        /** Rebuilds from [wire]'s form: `classes` names and `subclass` index pairs. */
        fun of(wire: Map<*, *>): SumoHyperarchy {
            val classes = (wire["classes"] as List<*>).map { it.toString() }.toTypedArray()
            val pairs = wire["subclass"] as List<*>
            return SumoHyperarchy(classes, IntArray(pairs.size) { (pairs[it] as Number).toInt() })
        }
    }
}
