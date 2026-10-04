package narchy.spacegraph

import borg.trikeshed.collections.DoubleColumn
import borg.trikeshed.collections.IntColumn
import borg.trikeshed.collections.SegmentIndex
import borg.trikeshed.landscape.LandscapeMomentum
import borg.trikeshed.landscape.LandscapeNavigation
import borg.trikeshed.lib.*
import narchy.spacegraph.graphics.spi.*
import kotlin.math.*

/**
 * The curator scope as a law library in perspective. Each project is a shelf unit with its ladder, the
 * units stepping back in depth; each book stands on its shelf as a circle packed with its sections, each
 * section a circle of its statements; the shared SUMO concept pool is its own unit, a circle of class
 * circles holding concept stars sized by how much of the text rests on them. A book opens toward the
 * camera and fans its sections out as pages carrying what curation read; it closes back onto its shelf.
 * Relations are thin bundles routed through the hierarchy (Holten's hierarchical edge bundling): between
 * shelves as aggregates, per statement only for the node in hand, past the snap depth, or while fresh.
 *
 * Level of detail is one rule: a circle splits into its children once its screen radius reaches the
 * median book's at the snap zoom [snapPercent]; circles under a pixel are carried by their container.
 *
 * Platform adapters feed [drain] with `/api/lcnc/trail` values, [shelve] with each project's documents
 * and [read] with `/api/curation/section` values for [wanted]; they route input to [pan]/[wheel]/[pick]/
 * [select]/[turn] and draw [frame]'s plan. The camera follows a run only while it is producing and the
 * operator has not touched the camera since that production began; a finished run never pulls it.
 */
class CuratorScope(var viewport: Viewport = Viewport(1, 1)) {
    /** True while the operator has pinned the camera; follow resumes only on [follow]. */
    var pinned = false
    /** The run being followed; -1 follows whichever run moved last. */
    var followRun = -1
    var selected = -1
    var hovered = -1
    /** Snap depth on the [zoomPercent] scale: at this zoom the median book splits into its sections. */
    var snapPercent = 50.0
    /** The current zoom on the detail scale (log of [LandscapeNavigation.minZoom]..[LandscapeNavigation.detailZoom], 0..100). */
    val zoomPercent: Double get() = percentOf(zoom)
    val snap: Double get() = zoomOf(snapPercent)

    // ── node table (ordinals match the daemon's ring) ──
    private val keys = ArrayList<String>()
    private val types = ArrayList<String>()
    private val parents = IntColumn()
    private val born = DoubleColumn(); private val lastBegin = DoubleColumn(); private val lastEnd = DoubleColumn()
    private val activeSince = DoubleColumn(); private val spent = DoubleColumn(); private val runs = IntColumn()
    private val lastKind = IntColumn(); private val lastNote = ArrayList<String?>()
    private var laidOut = 0
    private var fitted = false
    private var runNames: List<String> = emptyList()

    // ── recent events (local time) ──
    private val cap = 1 shl 16
    private val evT = DoubleArray(cap); private val evKind = IntArray(cap); private val evRun = IntArray(cap)
    private val evNode = IntArray(cap); private val evNote = arrayOfNulls<String>(cap)
    private var evHead = 0L

    // ── relations: statement → concept by role or force, section → section by citation or conflict ──
    private val linkFrom = IntColumn(); private val linkTo = IntColumn(); private val linkLabel = ArrayList<String>()
    private val linkAt = DoubleColumn()
    /** Per node, how many relations touch it: a concept's degree is how much of the text rests on it. */
    private val degree = IntColumn()
    /** Sections on either side of a conflict. */
    private val conflicted = HashSet<Int>()
    /** Between two top circles (book or pool, low ordinal first): counts by [CITES], [CONFLICT], [SHARED], [READS]. */
    private val bundles = HashMap<Long, IntArray>()
    /** Statement text → the book that first stated it; a second book stating it shares it. */
    private val statedIn = HashMap<String, Int>()

    var cursor = 0L; private set
    /** The ring epoch [cursor] and node ordinals belong to; 0 until the first drain. */
    var epoch = 0L; private set
    var dropped = 0L; private set
    var lastDrained = 0; private set
    private var lastRun = -1
    /** Local time of the latest event, and of the first event of the production burst it belongs to. */
    private var lastEventAt = -1e18
    private var burstFrom = -1e18

    // ── camera: (cx, cy) on the z = 0 plane, [zoom] its pixels per world unit ──
    private var cx = 0.0; private var cy = 0.0; private var cz = 0.0; private var zoom = 1.0
    private var touched = -1e18
    private var now = 0.0

    val nodes: Int get() = keys.size

    /** Folds one `/api/lcnc/trail` drain in, stamping server times onto the local clock [localNow]. */
    fun drain(value: Map<*, *>, localNow: Double) {
        now = localNow
        if (value["reset"] == true) clear((value["epoch"] as? Number)?.toLong() != epoch)
        epoch = (value["epoch"] as? Number)?.toLong() ?: epoch
        val offset = localNow - num(value["now"])
        val from = num(value["nodesFrom"]).toInt()
        val nk = value["nodeKeys"] as? List<*> ?: emptyList<Any>()
        val nt = value["nodeTypes"] as? List<*> ?: emptyList<Any>()
        val np = value["nodeParents"] as? List<*> ?: emptyList<Any>()
        for (i in nk.indices) if (from + i == keys.size) {
            keys.add(nk[i].toString()); types.add(nt[i].toString()); parents.add(num(np[i]).toInt())
            keyIndex[keys[keys.size - 1]] = keys.size - 1
            born.add(localNow); lastBegin.add(-1e18); lastEnd.add(-1e18); activeSince.add(-1.0); spent.add(0.0)
            runs.add(0); lastKind.add(-1); lastNote.add(null); degree.add(0)
            val at = keys.size - 1
            if (types[at] != "book.statement" && types[at] != "program") names.put(nameKey(name(at), at), at.toLong())
        }
        (value["runs"] as? List<*>)?.let { r -> runNames = r.map { it.toString() } }
        val t = value["t"] as? List<*> ?: emptyList<Any>()
        val kind = value["kind"] as? List<*> ?: emptyList<Any>()
        val run = value["run"] as? List<*> ?: emptyList<Any>()
        val node = value["node"] as? List<*> ?: emptyList<Any>()
        val note = value["note"] as? List<*> ?: emptyList<Any>()
        for (i in t.indices) {
            val at = num(t[i]) + offset; val k = num(kind[i]).toInt(); val r = num(run[i]).toInt(); val n = num(node[i]).toInt()
            val s = (evHead % cap).toInt()
            evT[s] = at; evKind[s] = k; evRun[s] = r; evNode[s] = n; evNote[s] = note[i]?.toString(); evHead++
            lastRun = r
            if (at - lastEventAt > LIVE_MS) burstFrom = at
            if (at > lastEventAt) lastEventAt = at
            if (n !in 0 until keys.size) continue
            if (k == LINK) {
                val note0 = note[i]?.toString() ?: continue
                val to = note0.substringBefore('|').toIntOrNull()?.takeIf { it in 0 until keys.size } ?: continue
                val label = note0.substringAfter('|')
                linkFrom.add(n); linkTo.add(to); linkLabel.add(label); linkAt.add(at)
                degree[n] = degree[n] + 1; degree[to] = degree[to] + 1
                if (lastBegin[to] < at) lastBegin[to] = at
                val a = top(n); val b = top(to)
                when {
                    label.startsWith("conflict") -> { conflicted.add(n); conflicted.add(to); if (a != b) bundle(a, b)[CONFLICT]++ }
                    label == "cites" -> if (a != b) bundle(a, b)[CITES]++
                    types[to] == "concept" && a != b -> bundle(a, b)[READS]++
                }
                continue
            }
            lastKind[n] = k
            note[i]?.let { text ->
                if (lastNote[n] == null && types[n] == "book.statement") {
                    val b = top(n); val first = statedIn.getOrPut(text.toString()) { b }
                    if (first != b) bundle(first, b)[SHARED]++
                }
                lastNote[n] = text.toString()
            }
            when (k) {
                BEGIN -> { lastBegin[n] = at; activeSince[n] = at }
                END -> { lastEnd[n] = at; if (activeSince[n] >= 0) spent[n] = spent[n] + (at - activeSince[n]); activeSince[n] = -1.0; runs[n] = runs[n] + 1 }
                FAIL -> { lastEnd[n] = at; activeSince[n] = -1.0 }
                else -> lastBegin[n] = at
            }
            // A reading lights the circles holding it, so a deep production shows up at every level of detail.
            var p = parents[n]; var guard = 0
            while (p >= 0 && guard++ < 64) { if (lastBegin[p] < at) lastBegin[p] = at; p = parents[p] }
        }
        cursor = (value["head"] as? Number)?.toLong() ?: cursor
        dropped += (value["dropped"] as? Number)?.toLong() ?: 0L
        lastDrained = t.size
        if (t.isNotEmpty() || nk.isNotEmpty()) count()
        if (keys.size != laidOut) layout()
    }

    /** Statements by force (must, must-not, may, presumed), then statements, concepts, sections: counted per drain, not per frame. */
    private val tally = IntArray(7)
    /** Categories masked out of the scope, one bit per tally ([FORCES] ordinals, [CONCEPTS], [SECTIONS], [RELATIONS]). */
    var hidden = 0
    private val toggles = ArrayList<Join<Int, Rect>>()
    /** Looms opened to their strands, by key (`b` bundle predicate × 2 + concept pool, `r` relation label), where each stood last frame, their click targets and bands. */
    private val loomsOpen = HashSet<String>(); private val loomAt = HashMap<String, Vec3>()
    private val loomHits = ArrayList<Join<Rect, () -> Unit>>(); private val loomRects = ArrayList<Rect>()
    /** Two nodes a loom row asked the camera to frame together. */
    private var both = IntArray(0)

    /** Flips the category whose tally is under ([sx], [sy]), or closes the open book at its close box; false when neither is there. */
    fun toggle(sx: Double, sy: Double): Boolean {
        closer?.let { if (it.contains(Vec3(sx, sy))) { selected = -1; close(); return true } }
        for (t in toggles) if (t.b.contains(Vec3(sx, sy))) { hidden = hidden xor (1 shl t.a); return true }
        for (h in loomHits) if (h.a.contains(Vec3(sx, sy))) { h.b.invoke(); return true }
        return false
    }
    private var closer: Rect? = null

    /** Whether node [i]'s category is shown. */
    private fun visible(i: Int): Boolean = when (types[i]) {
        "book.statement" -> FORCES.indexOf(force(i)).let { it < 0 || hidden and (1 shl it) == 0 }
        "concept" -> hidden and (1 shl CONCEPTS) == 0
        "book.section" -> hidden and (1 shl SECTIONS) == 0
        else -> true
    }
    private fun count() {
        tally.fill(0)
        for (i in keys.indices) when (types[i]) {
            "book.statement" -> { tally[4]++; FORCES.indexOf(force(i)).let { if (it >= 0) tally[it]++ } }
            "concept" -> tally[5]++
            "book.section" -> tally[6]++
        }
    }

    /** Forgets every node and event: the daemon's ring restarted ([renewed]: a new epoch), so ordinals mean new
     *  nodes. Only a new epoch re-frames the library; a replay under the same epoch keeps the camera. */
    private fun clear(renewed: Boolean) {
        keys.clear(); keyIndex.clear(); types.clear(); lastNote.clear(); linkLabel.clear(); conflicted.clear(); bundles.clear(); statedIn.clear()
        names = SegmentIndex(); target = -1
        readings.clear(); asked.clear(); pageLines.clear()
        for (c in listOf(born, lastBegin, lastEnd, activeSince, spent, linkAt)) c.reset()
        for (c in listOf(parents, runs, lastKind, degree, linkFrom, linkTo)) c.reset()

        evHead = 0; cursor = 0; laidOut = 0; if (renewed) fitted = false; lastRun = -1; lastEventAt = -1e18; burstFrom = -1e18; selected = -1; hovered = -1; runNames = emptyList()
        open = -1; closing = -1; page = 0
    }

    /** The top circle a node sits in: its book, or the concept pool; the node itself when it has no parent. */
    private fun top(i: Int): Int {
        var n = i; var guard = 0
        while (guard++ < 64 && parents[n] >= 0 && types[parents[n]] != "program") n = parents[n]
        return if (parents[n] >= 0 && isPool(parents[n])) parents[n] else n
    }

    // ── the finding aids: a name index, the ordered contents, a triple-pattern query, and goto ──
    /** Every named node (book, section, class, concept) keyed by [nameKey]: name order, then ring order. */
    private var names = SegmentIndex()
    /** The node the camera is easing onto after [goto]; -1 when none. */
    private var target = -1

    private fun name(i: Int): String = if (isBook(i)) bookName(i) else keys[i].substringAfterLast('/')

    /** Up to [limit] named nodes whose name starts with [prefix] (case folded), in name order. */
    fun find(prefix: String, limit: Int = 60): List<Map<String, Any?>> {
        val q = prefix.trim().lowercase(); if (q.isEmpty()) return emptyList()
        val lo = nameKey(q, 0) and ORDINAL.inv(); val hi = lo or ((1L shl ((NAME_CHARS - min(q.length, NAME_CHARS)) * 6 + ORDINAL_BITS)) - 1)
        val out = ArrayList<Map<String, Any?>>()
        for (at in names.range(lo, hi, limit * 8)) {
            val i = at.toInt(); if (i !in keys.indices || !name(i).lowercase().startsWith(q)) continue
            out.add(entry(i)); if (out.size == limit) break
        }
        return out
    }

    /** A node as a row of the finding aids: ordinal, name, kind, where it stands, and how much rests on it. */
    private fun entry(i: Int): Map<String, Any?> = mapOf("node" to i, "name" to name(i), "type" to types[i],
        "in" to parents[i].takeIf { it >= 0 && types[it] != "program" }?.let(::name), "count" to if (types[i] == "concept") degree[i] else kidCount(i),
        "note" to lastNote[i])

    /**
     * The ordered contents under [i] (-1: the library): books by shelf then the concept pool; a book's
     * sections in reading order; a class's concepts; a section's statements. Each row is an [entry].
     */
    fun contents(i: Int = -1): List<Map<String, Any?>> {
        if (i < 0) return units.flatMap { u -> u.discs.map(::entry) }
        if (i !in keys.indices || laidOut == 0 || i + 1 >= kidStart.size) return emptyList()
        return (kidStart[i] until kidStart[i + 1]).map { entry(kidList[it]) }
    }

    /**
     * Statements matching every pattern of [query], in reading order. Patterns are `?s <p> <o>` joined by
     * ` . `: `<p>` is `subject`, a force (`must`, `must-not`, `may`, `presumed`), `force:action` (action
     * contains), or `?p` for any; `<o>` is a concept name prefix, the name prefix of the SUMO class the concept
     * is filed under (its parent, `concepts/<class>`), or `?o` for any. Each pattern marks a bitset over
     * statements; the answer is their intersection, read in ordinal order.
     */
    fun query(query: String, limit: Int = 200): List<Map<String, Any?>> {
        val patterns = query.split(" . ").map { it.trim() }.filter { it.isNotEmpty() }.map { it.split(Regex("\\s+")) }.filter { it.size == 3 }
        if (patterns.isEmpty()) return emptyList()
        var hit: BooleanArray? = null
        for ((_, p, o) in patterns) {
            val mark = BooleanArray(keys.size)
            val force = p.substringBefore(':'); val action = p.substringAfter(':', "")
            val prefix = o.lowercase()
            for (l in 0 until linkFrom.n) {
                val s = linkFrom[l]; val c = linkTo[l]
                if (types[s] != "book.statement" || types[c] != "concept") continue
                val label = linkLabel[l]
                val pOk = when {
                    p.startsWith("?") -> true
                    p == "subject" -> label == "subject"
                    else -> label.substringBefore(' ') == force && (action.isEmpty() || label.substringAfter(' ').contains(action))
                }
                if (pOk && (o.startsWith("?") || name(c).lowercase().startsWith(prefix)
                        || parents[c] >= 0 && name(parents[c]).lowercase().startsWith(prefix))) mark[s] = true
            }
            hit = hit?.also { h -> for (k in h.indices) h[k] = h[k] && mark[k] } ?: mark
        }
        val out = ArrayList<Map<String, Any?>>()
        hit!!.forEachIndexed { k, on -> if (on && out.size < limit) out.add(entry(k)) }
        return out
    }

    /** Takes the camera to [i]: a statement or section opens its book at that page, anything else is framed. */
    fun goto(i: Int) {
        if (i !in keys.indices) return
        pinned = true; touched = now
        when (types[i]) {
            "book.statement" -> { select(parents[i]); selected = i }
            "book.section" -> select(i)
            else -> { if (!isBook(i)) close(); select(i); target = if (isBook(i)) -1 else i; aim = true }
        }
    }

    /** The node whose ring key is [key] (`book.curate/<book>/<section>`, `concepts/<class>/<lemma>`), or -1. */
    fun nodeOf(key: String): Int = keyIndex[key] ?: -1

    /** Ring key → node, filled as nodes arrive. */
    private val keyIndex = HashMap<String, Int>()
    private fun bundle(a: Int, b: Int): IntArray = bundles.getOrPut(min(a, b).toLong() shl 32 or max(a, b).toLong()) { IntArray(4) }
    private fun isBook(i: Int) = types[i] == "scope" && parents[i] >= 0 && keys[parents[i]] == "book.curate"
    private fun isPool(i: Int) = types[i] == "program" && keys[i] == "concepts"

    // ── shelving: project → its documents (the books on its unit) ──
    private val shelfOf = HashMap<String, String>()

    /** Shelves [docs] (book names; design documents are not books) on [project]'s unit; a change re-lays
     *  the library, and re-frames it while the operator has not yet taken the camera. */
    fun shelve(project: String, docs: List<String>) {
        var moved = false
        for (d in docs) if (!d.startsWith("_design/") && shelfOf.put(d, project) != project) moved = true
        if (moved) { laidOut = -1; if (touched < 0) fitted = false }
    }

    /** The deontic force a statement carries, read from its note: must / must-not / may / presumed. */
    private fun force(i: Int): String? = lastNote[i]?.split(" · ")?.getOrNull(1)
    private fun forceColor(f: String?) = when (f) {
        "must" -> Rgba(236, 110, 80); "must-not" -> Rgba(230, 70, 120); "may" -> Rgba(110, 200, 140)
        "presumed" -> Rgba(150, 160, 180); else -> Rgba(206, 214, 226)
    }

    // ── layout: circles packed bottom-up, then top circles stood on their units' shelves ──
    /** Per node: its container circle ([TOP] for a book or the pool, [UNPLACED] outside the library). */
    private var disc = IntArray(0)
    /** Per node: its center, relative to its container's (absolute for a top circle), and its radius. */
    private var ox = DoubleArray(0); private var oy = DoubleArray(0); private var oz = DoubleArray(0); private var rad = DoubleArray(0)
    private var kidStart = IntArray(1); private var kidList = IntArray(0)
    /** A shelving bay (one per project, one for the concept pool): its extent (x0..x1, 0..h, z..z+d), shelf board tops, and the top circles it holds. */
    private class Bay(val name: String, val discs: IntArray) {
        var x0 = 0.0; var x1 = 0.0; var h = 0.0; var z = 0.0; var d = 0.0; var post = 0.0; var board = 0.0
        val boards = ArrayList<Double>()
    }
    private val units = ArrayList<Bay>()
    private var unitOf = HashMap<Int, Int>()
    /** The median book's radius and its sections' mean radius: the reference the snap depth speaks of. */
    private var refR = 100.0
    private var refKid = 10.0
    /** Per node: the mean radius of the circles packed in it. */
    private var meanKid = DoubleArray(0)
    private var maxConcept = 1
    private var apex = Vec3.ZERO

    private fun kidCount(i: Int) = kidStart[i + 1] - kidStart[i]

    private fun layout() {
        val n = keys.size
        kidStart = IntArray(n + 1)
        for (i in 0 until n) { val p = parents[i]; if (p >= 0) kidStart[p + 1]++ }
        for (i in 0 until n) kidStart[i + 1] += kidStart[i]
        kidList = IntArray(kidStart[n]); val fill = kidStart.copyOf()
        for (i in 0 until n) { val p = parents[i]; if (p >= 0) kidList[fill[p]++] = i }
        disc = IntArray(n) { UNPLACED }; meanKid = DoubleArray(n); ox = DoubleArray(n); oy = DoubleArray(n); oz = DoubleArray(n); rad = DoubleArray(n)
        /** Packs [members] (radii already in [rad]) inside [container], centers relative to it. */
        fun pack(container: Int, members: IntArray) {
            val r = DoubleArray(members.size) { rad[members[it]] + PAD }
            val x = DoubleArray(members.size); val y = DoubleArray(members.size)
            val rim = CirclePacking.pack(r, x, y)
            for ((j, m) in members.withIndex()) { ox[m] = x[j]; oy[m] = y[j]; disc[m] = container }
            rad[container] = max(STAR * 1.5, rim + PAD)
            meanKid[container] = if (members.isEmpty()) 0.0 else members.sumOf { rad[it] } / members.size
        }
        fun kids(i: Int) = IntArray(kidCount(i)) { kidList[kidStart[i] + it] }
        val tops = ArrayList<Int>()
        maxConcept = 1
        for (i in 0 until n) {
            if (isBook(i)) {
                val sections = kids(i).filter { types[it] == "book.section" }.toIntArray()
                for (s in sections) {
                    val st = kids(s)
                    for (k in st) rad[k] = STAR * sqrt(1.0 + degree[k])
                    pack(s, st)
                }
                pack(i, sections); tops.add(i)
            } else if (isPool(i)) {
                val classes = kids(i).filter { types[it] == "scope" }.toIntArray()
                for (c in classes) {
                    val atoms = kids(c)
                    for (a in atoms) { rad[a] = STAR * sqrt(1.0 + degree[a]); maxConcept = max(maxConcept, degree[a]) }
                    pack(c, atoms)
                }
                pack(i, classes); tops.add(i)
            }
        }
        for (t in tops) disc[t] = TOP
        tops.filter(::isBook).sortedBy { rad[it] }.let { m -> if (m.isNotEmpty()) { refR = rad[m[m.size / 2]]; refKid = meanKid[m[m.size / 2]] } }
        // Units: one per project (books not yet shelved share one), the concept pool last.
        val byUnit = LinkedHashMap<String, MutableList<Int>>()
        for (t in tops.filter(::isBook).sortedBy { shelfOf[bookName(it)] ?: "\uFFFF" })
            byUnit.getOrPut(shelfOf[bookName(t)] ?: "unshelved") { ArrayList() }.add(t)
        tops.filter(::isPool).forEach { byUnit.getOrPut("concepts") { ArrayList() }.add(it) }
        units.clear(); unitOf = HashMap()
        val maxR = tops.maxOfOrNull { rad[it] } ?: 100.0
        var left = 0.0
        for ((name, discs) in byUnit) {
            val u = Bay(name, discs.toIntArray()); val k = units.size
            val big = discs.maxOf { rad[it] }
            u.post = big * .05 + 2; u.board = big * .03 + 1.5; u.d = big * .9
            val gap = big * .15 + PAD * 4
            u.x0 = left; u.z = -k * maxR * 2.2
            val ladder = u.post * 4
            var y = u.board; var width = 0.0
            u.boards.add(u.board)
            for (row in discs.chunked(ROW)) {
                val rowH = row.maxOf { rad[it] } * 2 + gap
                var x = u.x0 + ladder + u.post + gap
                for (b in row) { ox[b] = x + rad[b]; oy[b] = y + rad[b]; oz[b] = u.z + u.d * .5; x += rad[b] * 2 + gap; unitOf[b] = k }
                width = max(width, x - u.x0)
                y += rowH + u.board; u.boards.add(y)
            }
            u.x1 = u.x0 + width + u.post; u.h = y
            units.add(u); left = u.x1 + maxR * .6
        }
        apex = Vec3(left / 2, (units.maxOfOrNull { it.h } ?: 0.0) * 1.35, units.map { it.z }.average().takeIf { it.isFinite() } ?: 0.0)
        laidOut = keys.size
        if (!fitted && tops.isNotEmpty()) { fitted = true; fitAll() }
    }

    private fun bookName(i: Int) = keys[i].removePrefix("book.curate/")

    /** A node's world center (a book lifts toward the camera while open), or null outside the library. */
    private fun center(i: Int): Vec3? {
        val c = disc[i]
        if (c == UNPLACED) return null
        if (c == TOP) return Vec3(ox[i], oy[i], oz[i] + rad[i] * OPEN_LIFT * openness(i))
        val p = center(c) ?: return null
        return Vec3(p.x + ox[i], p.y + oy[i], p.z)
    }

    private fun fitAll() {
        if (units.isEmpty()) return
        val b = frameOf(units.flatMap { it.discs.toList() }); cx = b[0]; cy = b[1]; zoom = b[2]; cz = b[3]
    }

    /** Center, zoom and focal plane that frame [ids] (and the open book's pages when it is among them):
     *  [cx, cy, zoom, cz], the plane at the region's nearest depth. */
    private fun frameOf(ids: List<Int>): DoubleArray {
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE; var near = -Double.MAX_VALUE
        fun cover(c: Vec3, r: Double) { x0 = min(x0, c.x - r); x1 = max(x1, c.x + r); y0 = min(y0, c.y - r); y1 = max(y1, c.y + r); near = max(near, c.z) }
        for (i in ids) { val c = center(i) ?: continue; cover(c, rad[i]); if (i == open) for (q in pageQuads(i)) for (p in q.b) cover(p, 0.0) }
        if (x0 > x1) return doubleArrayOf(cx, cy, zoom, cz)
        val w = x1 - x0 + PAD * 8; val h = y1 - y0 + PAD * 8
        val z = min(viewport.width / w, max(1.0, viewport.height - HUD) / h)
        return doubleArrayOf((x0 + x1) / 2, (y0 + y1) / 2, z.coerceIn(LandscapeNavigation.minZoom, DEEPEST), near)
    }

    // ── an opened book: lifted toward the camera, its sections fanned out as pages ──
    /** The open book, the one closing (animating back onto its shelf), and the section on the right-hand page. */
    var open = -1; private set
    private var closing = -1
    var page = 0; private set
    private var openedAt = -1e18; private var closedAt = -1e18
    private var leaves = IntArray(0)
    /** While true the camera eases once onto the open book (or the production, after [follow]); it stops on
     *  arrival or at operator input. */
    private var aim = false

    private fun openness(i: Int): Double {
        val t = when (i) { open -> (now - openedAt) / OPEN_MS; closing -> 1 - (now - closedAt) / OPEN_MS; else -> return 0.0 }
        val u = t.coerceIn(0.0, 1.0); return 1 - (1 - u).pow(3)
    }

    /** Selects [i]: a book opens, a section opens its book at that page; anything else is only selected. */
    fun select(i: Int) {
        selected = i
        if (i !in keys.indices) return
        val book = when { isBook(i) -> i; types[i] == "book.section" && parents[i] >= 0 && isBook(parents[i]) -> parents[i]; else -> return }
        if (book != open) {
            if (open >= 0) { closing = open; closedAt = now }
            open = book; openedAt = now
            leaves = IntArray(kidCount(book)) { kidList[kidStart[book] + it] }.filter { types[it] == "book.section" }.toIntArray()
            page = 0
        }
        if (i != book) page = leaves.indexOf(i).coerceAtLeast(0)
        aim = true
    }

    /** Closes the open book back onto its shelf. */
    fun close() { if (open < 0) return; closing = open; closedAt = now; open = -1; aim = false }

    /** Turns [spreads] two-page spreads forward (negative: back). */
    fun turn(spreads: Int) { if (open >= 0 && leaves.isNotEmpty()) page = (page + 2 * spreads).coerceIn(0, leaves.size - 1) }

    // ── what curation read, per section key: the `/api/curation/section` value as page lines ──
    private class PageLine(val text: String, val color: Rgba, val size: Double, val mono: Boolean)
    private val readings = HashMap<String, List<PageLine>>()
    private val asked = HashSet<String>()
    private val pageLines = HashMap<Int, List<PageLine>>()

    /** Section keys the open spread (and the next spread either way) needs read; each is asked once. */
    fun wanted(): List<String> {
        if (open < 0) return emptyList()
        return (page - 3..page + 2).mapNotNull { leaves.getOrNull(it)?.let { s -> keys[s] } }.filter { asked.add(it) }
    }

    /** Folds a `/api/curation/section` value for section [key] into its page. */
    fun read(key: String, v: Map<*, *>) {
        fun list(x: Any?) = x as? List<*> ?: emptyList<Any?>()
        fun map(x: Any?) = x as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val out = ArrayList<PageLine>()
        if (v.containsKey("error")) { out.add(PageLine(v["error"].toString(), Rgba(140, 60, 60), 1.0, false)); readings[key] = out; return }
        out.add(PageLine("${v["heading"]}", INK, 1.5, false))
        out.add(PageLine("section ${num(v["ordinal"]).toInt()} of ${num(v["of"]).toInt()} · ${v["book"]}", FADED, .8, false))
        val classes = list(v["classes"]).joinToString("  ") { "${map(it)["class"]} ${num(map(it)["n"]).toInt()}" }
        if (classes.isNotEmpty()) out.add(PageLine(classes, CLASS_INK, .8, true))
        for (s0 in list(v["sentences"])) {
            val s = map(s0)
            out.add(PageLine(s["text"].toString(), INK, 1.0, false))
            val concepts = list(s["concepts"]).joinToString("  ") { c -> map(c).let { "${it["lemma"]}‹${it["class"] ?: "?"}›" } }
            if (concepts.isNotEmpty()) out.add(PageLine(concepts, CLASS_INK, .8, true))
            for (st0 in list(s["statements"])) {
                val st = map(st0)
                out.add(PageLine(listOfNotNull(st["bearer"], st["force"]?.toString()?.uppercase(), st["action"], st["object"], st["condition"]).joinToString(" · "),
                    dim(forceColor(st["force"]?.toString())).let { Rgba(it.red - 20, it.green - 20, it.blue - 20) }, .85, true))
            }
        }
        readings[key] = out
    }

    /** The lines a page shows: what curation read, or until it arrives the statements the trail carried. */
    private fun lines(s: Int): List<PageLine> = readings[keys[s]] ?: pageLines.getOrPut(s) {
        listOf(PageLine(keys[s].substringAfterLast('/'), INK, 1.5, false), PageLine("reading…", FADED, .8, false)) +
            IntArray(kidCount(s)) { kidList[kidStart[s] + it] }.map { k -> PageLine(lastNote[k] ?: "", dim(forceColor(force(k))), .85, true) }
    }

    /** The open (or closing) book's page quads, back to front: (section, world corners). */
    private fun pageQuads(b: Int): List<Join<Int, List<Vec3>>> {
        val o = openness(b); if (o <= 0 || leaves.isEmpty() || b != open) return emptyList()
        val c = center(b) ?: return emptyList()
        val w = rad[b] * o; val h = rad[b] * 1.35 * o
        val pivot = Vec3(c.x, c.y - h / 2, c.z + rad[b] * .02)
        val out = ArrayList<Join<Int, List<Vec3>>>()
        fun leaf(s: Int, d: Int, right: Boolean) {
            val a = min(FAN_MAX, d * FAN_STEP) * PI / 180 * o
            val sign = if (right) 1.0 else -1.0
            val z = pivot.z - d * rad[b] * .004
            out.add(s j listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0).map { (u, v) ->
                val x = sign * u * w; val y = v * h
                Vec3(pivot.x + x * cos(a) + sign * y * sin(a), pivot.y - sign * x * sin(a) + y * cos(a), z)
            })
        }
        for (d in FAN downTo 1) { leaves.getOrNull(page + d)?.let { leaf(it, d, true) }; leaves.getOrNull(page - 1 - d)?.let { leaf(it, d, false) } }
        leaves.getOrNull(page - 1)?.let { leaf(it, 0, false) }
        leaves.getOrNull(page)?.let { leaf(it, 0, true) }
        return out
    }

    // ── camera: a perspective eye over the focal plane z = [cz] whose [zoom] is its pixels per world unit there;
    //    the spatial surfaces' own navigation — LandscapeNavigation's wheel step and zoom clamps,
    //    LandscapeMomentum's flick and wheel glide. Operator input takes the camera for [hold] ms. ──
    val momentum = LandscapeMomentum()

    /** Pixels per world unit at unit distance: the lens of [GraphCamera]'s field of view on this viewport. */
    private fun focal() = viewport.height / (2 * tan(GraphCamera().fieldOfView * PI / 360))

    private fun percentOf(z: Double) = 100 * ln(z / LandscapeNavigation.minZoom) / ln(LandscapeNavigation.detailZoom / LandscapeNavigation.minZoom)
    private fun zoomOf(p: Double) = LandscapeNavigation.minZoom * (LandscapeNavigation.detailZoom / LandscapeNavigation.minZoom).pow(p / 100)

    private fun camera(z: Double = zoom): GraphCamera {
        val d = focal() / z
        return GraphCamera(center = Vec3(cx, cy, cz), zoom = 1.0, mode = CameraMode.PERSPECTIVE,
            position = Vec3(cx, cy + d * TILT, cz + d), near = d * .02, far = d * 4 + cz + libraryDepth())
    }
    private fun libraryDepth() = units.maxOfOrNull { -it.z + it.d } ?: 0.0

    /** Moves the focal plane to depth [z] without moving the eye, so the view is unchanged and zoom then
     *  steps relative to what lies at that depth (a lifted page, a far bay). */
    private fun refocus(z: Double) {
        val d = focal() / zoom; val ez = cz + d; val d1 = ez - z
        if (!(d1 > focal() / DEEPEST) || !(d1 < focal() / LandscapeNavigation.minZoom)) return
        cy += (d - d1) * TILT; cz = z; zoom = focal() / d1
    }

    fun pan(dx: Double, dy: Double) { cx -= dx / zoom; cy += dy / zoom; touched = now; aim = false }

    /** Zooms by [factor] at screen ([sx], [sy]) within LandscapeNavigation's clamps; returns (before, after). */
    fun zoom(factor: Double, sx: Double, sy: Double): DoubleArray {
        val before = zoom
        val target = (zoom * factor).coerceIn(LandscapeNavigation.minZoom, DEEPEST)
        val p = Vec3(sx, sy)
        val a = camera().unproject(p, viewport, cz); val b = camera(target).unproject(p, viewport, cz)
        if (a != null && b != null) { cx += a.x - b.x; cy += a.y - b.y }
        zoom = target; touched = now; aim = false
        return doubleArrayOf(before, zoom)
    }

    fun press(sx: Double, sy: Double, t: Double) { now = t; momentum.press(sx, sy, t); touched = now }
    fun drag(dx: Double, dy: Double, sx: Double, sy: Double, t: Double) { now = t; pan(dx, dy); momentum.drag(sx, sy, t) }
    fun release(t: Double) { now = t; momentum.release(t); touched = now }
    /** A wheel event: [deltaY] in [deltaMode] units, stepped by LandscapeNavigation and left to glide. */
    fun wheel(deltaY: Double, deltaMode: Int, sx: Double, sy: Double, t: Double) {
        now = t
        if (!momentum.live) pick(sx, sy).takeIf { it >= 0 }?.let(::center)?.let { refocus(it.z) }
        val f = LandscapeNavigation.wheelFactor(LandscapeMomentum.wheelPixels(deltaY, deltaMode, viewport.height.toDouble()))
        val (before, after) = zoom(f, sx, sy).let { it[0] to it[1] }
        momentum.wheel(sx, sy, f, before, after, DEEPEST, t)
    }

    /** Applies one glide step; the hold restarts while the glide moves. */
    private fun glide() {
        if (!momentum.live) return
        val g = momentum.frame(now) ?: return
        if (g.dx != 0.0 || g.dy != 0.0) pan(g.dx, g.dy)
        if (g.factor != 1.0) { val r = zoom(g.factor, g.ax, g.ay); momentum.landed(g, r[0], r[1], DEEPEST) }
    }
    /** Returns the camera to the production, closing any open book: it eases there once and keeps following while it produces. */
    fun follow() { pinned = false; touched = -1e18; close(); aim = true }
    fun pin() { pinned = true; touched = now }
    /** True while the followed production is live and the operator has not taken the camera since it began. */
    fun following(): Boolean = !pinned && open < 0 && now - lastEventAt < LIVE_MS && touched < burstFrom

    // ── what the last frame drew, for picking: circles (screen center, radius) and pages (screen quads) ──
    private var drawnId = IntArray(4096); private var drawnX = DoubleArray(4096); private var drawnY = DoubleArray(4096); private var drawnR = DoubleArray(4096)
    private var drawn = 0
    private val drawnPages = ArrayList<Join<Int, Rect>>()
    private fun mark(i: Int, p: Vec3, r: Double) {
        if (drawn == drawnId.size) { drawnId = drawnId.copyOf(drawn * 2); drawnX = drawnX.copyOf(drawn * 2); drawnY = drawnY.copyOf(drawn * 2); drawnR = drawnR.copyOf(drawn * 2) }
        drawnId[drawn] = i; drawnX[drawn] = p.x; drawnY[drawn] = p.y; drawnR[drawn] = r; drawn++
    }

    /** The node under screen point ([sx], [sy]): a page of the open book, else the smallest circle drawn there, or -1. */
    fun pick(sx: Double, sy: Double): Int {
        for (q in drawnPages.asReversed()) if (q.b.contains(Vec3(sx, sy))) return q.a
        var best = -1; var bestR = Double.MAX_VALUE
        for (k in 0 until drawn) {
            val r = max(drawnR[k], 4.0)
            if (hypot(drawnX[k] - sx, drawnY[k] - sy) <= r && drawnR[k] < bestR) { best = drawnId[k]; bestR = drawnR[k] }
        }
        return best
    }

    /** The run the camera follows: the pinned [followRun], else the last run that moved. */
    private fun followed() = if (followRun >= 0) followRun else lastRun

    /** The production's recent path: the last [n] nodes the followed run began or ended, oldest first. */
    private fun trail(n: Int): List<Int> {
        val run = followed(); val out = ArrayList<Int>(); var seq = evHead - 1
        while (seq >= 0 && seq >= evHead - cap && out.size < n) {
            val s = (seq % cap).toInt()
            if (evRun[s] == run && (evKind[s] == END || evKind[s] == BEGIN || evKind[s] == RING) && evNode[s] >= 0 &&
                (out.isEmpty() || out.last() != evNode[s])) out.add(evNode[s])
            seq--
        }
        return out.asReversed()
    }

    /** Ancestors of [i] up to its top circle, [i] first. */
    private fun chain(i: Int): List<Int> {
        val out = ArrayList<Int>(4); var n = i
        while (n >= 0 && disc[n] != UNPLACED) { out.add(n); if (disc[n] == TOP) break; n = disc[n] }
        return out
    }

    /** The top of a shelf unit, where bundles between shelves gather. */
    private fun unitTop(u: Bay) = Vec3((u.x0 + u.x1) / 2, u.h, u.z + u.d / 2)

    /** Holten's hierarchical route from [a] to [b]: up through their containers to the least common one
     *  (or across shelf tops and the library's apex), straightened by [BUNDLE]. World control points. */
    private fun route(a: Int, b: Int): List<Vec3> {
        val ca = chain(a); val cb = chain(b)
        if (ca.isEmpty() || cb.isEmpty()) return emptyList()
        val pts = ArrayList<Vec3>()
        if (ca.last() == cb.last()) {
            var ia = ca.size - 1; var ib = cb.size - 1
            while (ia > 0 && ib > 0 && ca[ia - 1] == cb[ib - 1]) { ia--; ib-- }
            for (k in 0..ia) pts.add(center(ca[k])!!)
            for (k in ib - 1 downTo 0) pts.add(center(cb[k])!!)
        } else {
            for (n in ca) pts.add(center(n)!!)
            val ua = unitOf[ca.last()]; val ub = unitOf[cb.last()]
            if (ua != null) pts.add(unitTop(units[ua]))
            if (ua != ub) { pts.add(apex); if (ub != null) pts.add(unitTop(units[ub])) }
            for (n in cb.asReversed()) pts.add(center(n)!!)
        }
        if (pts.size < 3) return pts
        val p0 = pts.first(); val pn = pts.last()
        return pts.mapIndexed { k, p -> p * BUNDLE + p0.lerp(pn, k / (pts.size - 1.0)) * (1 - BUNDLE) }
    }

    /** A clamped uniform cubic B-spline through [ctl], projected; points behind the eye are dropped. */
    private fun spline(ctl: List<Vec3>, cam: GraphCamera): List<Vec3> {
        if (ctl.size < 2) return emptyList()
        val c = listOf(ctl.first(), ctl.first()) + ctl + listOf(ctl.last(), ctl.last())
        val out = ArrayList<Vec3>()
        for (s in 0 until c.size - 3) for (k in 0 until SPLINE) {
            val t = k / SPLINE.toDouble(); val t2 = t * t; val t3 = t2 * t
            val b0 = (1 - t).pow(3) / 6; val b1 = (3 * t3 - 6 * t2 + 4) / 6; val b2 = (-3 * t3 + 3 * t2 + 3 * t + 1) / 6; val b3 = t3 / 6
            cam.project(c[s] * b0 + c[s + 1] * b1 + c[s + 2] * b2 + c[s + 3] * b3, viewport)?.let(out::add)
        }
        cam.project(ctl.last(), viewport)?.let(out::add)
        return out
    }

    /** Advances the camera to [localNow] and plans the frame. */
    fun frame(localNow: Double): FramePlan {
        now = localNow
        glide()
        if (closing >= 0 && openness(closing) <= 0) closing = -1
        if (!aim) { target = -1; both = IntArray(0) }
        if (units.isNotEmpty() && (aim || following())) {
            val focus = if (open >= 0) listOf(open) else if (both.isNotEmpty()) both.toList() else if (target >= 0) listOf(target) else trail(FOLLOW).map { if (types[it] == "book.statement") parents[it] else it }.filter { disc.getOrElse(it) { UNPLACED } != UNPLACED }
                .ifEmpty { if (aim) units.flatMap { it.discs.toList() } else emptyList() }
            if (focus.isNotEmpty()) {
                val b = frameOf(focus)
                val k = 1 - exp(-(EASE))
                cx += (b[0] - cx) * k; cy += (b[1] - cy) * k; zoom *= (b[2] / zoom).pow(k); cz += (b[3] - cz) * k
                if (aim && abs(ln(b[2] / zoom)) < .01 && hypot(b[0] - cx, b[1] - cy) * zoom < 1 && abs(b[3] - cz) * zoom < 1 && (open < 0 || openness(open) >= 1)) aim = false
            } else aim = false
        }
        val cam = camera(); val eye = cam.position; val f = focal()
        val vp = viewport.bounds
        val items = ArrayList<Join<Double, DrawItem>>()
        val over = ArrayList<DrawItem>()
        val labels = ArrayList<DrawItem>()
        /** Loom bands: over every path of the scene, open pages included, under all text. */
        val plates = ArrayList<DrawItem>()
        drawn = 0; drawnPages.clear()
        /** Children show once they would be as large on screen as a section is when the median book snaps. */
        val split = max(MIN_SPLIT_PX, refKid * snap)
        val shown = HashSet<Int>()
        /** Screen cells a circle's label already covers: a circle is labelled whenever its label lands on free cells. */
        val cols = (vp.width / CELL).toInt() + 1; val rows = (vp.height / CELL).toInt() + 1
        val taken = BooleanArray(cols * rows)
        fun claim(b: Rect): Boolean {
            val c0 = (b.x / CELL).toInt(); val c1 = (b.right / CELL).toInt(); val r0 = (b.y / CELL).toInt(); val r1 = (b.bottom / CELL).toInt()
            if (c1 < 0 || r1 < 0 || c0 >= cols || r0 >= rows) return false
            for (y in max(0, r0)..min(rows - 1, r1)) for (x in max(0, c0)..min(cols - 1, c1)) if (taken[y * cols + x]) return false
            for (y in max(0, r0)..min(rows - 1, r1)) for (x in max(0, c0)..min(cols - 1, c1)) taken[y * cols + x] = true
            return true
        }
        /** A label beside circle ([p], [r]) when it is legible and its box is free; [force] labels regardless. */
        fun beside(id: String, text: String, p: Vec3, r: Double, color: Rgba, size: Double, force: Boolean = false, mono: Boolean = false) {
            if (r >= INSIDE_R) {
                // A circle this large holds its text: wrapped inside it, sized to it.
                val s = (r / 7).coerceIn(size, 28.0); val w = r * 1.5
                val per = max(8, (w / (s * (if (mono) .6 else .52))).toInt())
                val rows = ArrayList<String>(); val row = StringBuilder()
                for (word in text.split(' ')) {
                    if (row.isNotEmpty() && row.length + 1 + word.length > per) { rows.add(row.toString()); row.clear() }
                    if (row.isNotEmpty()) row.append(' ')
                    row.append(word)
                }
                if (row.isNotEmpty()) rows.add(row.toString())
                var y = p.y - (rows.size - 1) * s * .65 + s * .35
                claim(Rect(p.x - r, p.y - r, 2 * r, 2 * r))
                for ((k, row) in rows.withIndex()) { labels.add(DrawItem.Text("$id-$k", row, Vec3(p.x - w / 2, y), color, s, maxWidth = w,
                    font = if (mono) "monospace" else "sans-serif", clip = Rect(p.x - r, p.y - r, 2 * r, 2 * r))); y += s * 1.3 }
                return
            }
            val w = min(LABEL_W, text.length * size * (if (mono) .6 else .56)); val at = Vec3(p.x + r + 3, p.y + size * .35)
            if (force || claim(Rect(at.x, at.y - size, w, size * 1.3)))
                labels.add(DrawItem.Text(id, text, at, color, size, maxWidth = LABEL_W, font = if (mono) "monospace" else "sans-serif"))
        }

        // Shelf units: posts, boards, back and ladder as lit solids; faces turned away are not drawn.
        val light = Vec3(-.35, .8, .5).normalized()
        for ((k, u) in units.withIndex()) {
            val wood = if (u.name == "concepts") Rgba(52, 64, 86) else Rgba(92, 64, 40)
            val ladder = u.post * 4
            val solids = ArrayList<Bounds3>()
            solids.add(Bounds3(Vec3(u.x0, 0.0, u.z), Vec3(u.x1, u.h, u.z + u.post * .4)))
            solids.add(Bounds3(Vec3(u.x0 + ladder, 0.0, u.z), Vec3(u.x0 + ladder + u.post, u.h, u.z + u.d)))
            solids.add(Bounds3(Vec3(u.x1 - u.post, 0.0, u.z), Vec3(u.x1, u.h, u.z + u.d)))
            for (y in u.boards) solids.add(Bounds3(Vec3(u.x0 + ladder, y - u.board, u.z), Vec3(u.x1, y, u.z + u.d)))
            val rail = u.post * .5; val front = u.z + u.d + u.post * 2
            solids.add(Bounds3(Vec3(u.x0, 0.0, front), Vec3(u.x0 + rail, u.h + ladder, front + rail)))
            solids.add(Bounds3(Vec3(u.x0 + ladder - rail, 0.0, front), Vec3(u.x0 + ladder, u.h + ladder, front + rail)))
            val rungs = max(2, (u.h / (ladder * .9)).toInt())
            for (r in 1..rungs) { val y = r * (u.h + ladder) / (rungs + 1); solids.add(Bounds3(Vec3(u.x0 + rail, y, front), Vec3(u.x0 + ladder - rail, y + rail * .6, front + rail))) }
            for ((si, b) in solids.withIndex()) for (fi in 0..5) {
                val n = ExtrudedSceneProjection.faceNormals[fi]
                val fc = b.center + Vec3(n.x * b.size.x, n.y * b.size.y, n.z * b.size.z) * .5
                if (n.dot(eye - fc) <= 0) continue
                val p = ExtrudedSceneProjection.face(b, fi, cam, viewport) ?: continue
                val span = Rect(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x } - p.minOf { it.x }, p.maxOf { it.y } - p.minOf { it.y })
                if (span.intersection(vp) == null || (span.width < .5 && span.height < .5)) continue
                val shade = .45 + .55 * max(0.0, n.dot(light))
                items.add(p.sumOf { it.z } / 4 j DrawItem.Path("shelf-$k-$si-$fi", s_[PathPart.Move(p[0]), PathPart.Line(p[1]), PathPart.Line(p[2]), PathPart.Line(p[3]), PathPart.Close],
                    fill = Rgba((wood.red * shade).roundToInt(), (wood.green * shade).roundToInt(), (wood.blue * shade).roundToInt())))
            }
            val title = if (u.name == "concepts") "SUMO concepts · ${u.discs.sumOf { kidCount(it) }} classes" else "${u.name} · ${u.discs.size} books"
            val tl = cam.project(Vec3(u.x0 + ladder, u.h + u.board * 4, u.z + u.d), viewport); val tr = cam.project(Vec3(u.x1, u.h, u.z + u.d), viewport)
            if (tl != null && tr != null) {
                val size = ((tr.x - tl.x) / 18).coerceIn(10.0, 22.0)
                if (title.length * size * .56 <= tr.x - tl.x && vp.contains(Vec3(tl.x.coerceIn(0.0, vp.width), tl.y)))
                    labels.add(DrawItem.Text("unit-$k", title, tl, Rgba(240, 214, 150), size, weight = 600))
            }
        }

        // Circles: a top circle and, where it is large enough on screen, its children, recursively.
        val maxDeg = max(1, maxConcept)
        fun circle(i: Int, c: Vec3, key: Double) {
            val p = cam.project(c, viewport)
            if (p == null) {
                // Deep in, a large circle's center can fall behind the tilted eye while its children fill the view:
                // walk on into the children that reach the view instead of dropping the whole circle.
                if (kidCount(i) == 0 || hypot(c.x - cx, c.y - cy) > rad[i] + hypot(vp.width, vp.height) / zoom) return
                for (k in kidStart[i] until kidStart[i + 1]) { val q = kidList[k]; if (disc[q] == i) circle(q, Vec3(c.x + ox[q], c.y + oy[q], c.z), key) }
                return
            }
            val r = rad[i] * f / p.z * growth(i)
            if (p.x + r < 0 || p.x - r > vp.width || p.y + r < 0 || p.y - r > vp.height || r < MIN_PX) return
            val type = types[i]
            if ((type == "concept" || type == "book.statement") && !visible(i)) return
            shown.add(i); mark(i, p, r)
            val heat = heat(i)
            val hot = i == hovered || i == selected
            val kids = kidCount(i)
            val splits = kids > 0 && meanKid[i] * f / p.z >= split && i != open
            val sides = (r * .8).toInt().coerceIn(8, 40)
            when (type) {
                "concept" -> {
                    val w = degree[i].toDouble() / maxDeg
                    val tone = mix(Rgba(120, 160, 210), Rgba(255, 244, 200), max(heat, sqrt(w)))
                    if (heat > .05) items.add(key - .003 j DrawItem.Path("glow-$i", disc(p, r * (1.8 + heat), 14), fill = Rgba(tone.red, tone.green, tone.blue, .22 * heat)))
                    items.add(key - .002 j DrawItem.Path("c-$i", outline(i, p, r), fill = tone, stroke = if (hot) Rgba(255, 255, 255) else null, width = 1.0))
                    if (r >= LABEL_R || hot) {
                        val text = keys[i].substringAfterLast('/')
                        beside("cl-$i", if (hot) "$text ×${degree[i]}" else text, p, r, Rgba(200, 222, 246, if (hot) 1.0 else .8), (r * 1.4).coerceIn(9.0, 13.0), hot)
                    }
                    return
                }
                "book.statement" -> {
                    items.add(key - .002 j DrawItem.Path("s-$i", disc(p, r, 10), fill = mix(dim(forceColor(force(i))), forceColor(force(i)), .6 + .4 * heat),
                        stroke = if (hot) Rgba(255, 255, 255) else null, width = 1.0))
                    if (r >= LABEL_R || hot) lastNote[i]?.let { beside("sl-$i", it, p, r, INK_LIGHT, (r / 2).coerceIn(9.0, 12.0), hot, mono = true) }
                    return
                }
            }
            val book = isBook(i); val pool = isPool(i); val section = type == "book.section"
            // A masked section draws nothing of its own; its statements still stand where it would.
            val bare = section && !visible(i)
            val base = when { book -> Rgba(96, 72, 44); pool -> Rgba(34, 52, 78); section -> Rgba(70, 60, 44); else -> Rgba(40, 62, 92) }
            val lit = mix(base, Rgba(240, 214, 150), heat * .7)
            val rim = when { hot -> Rgba(255, 255, 255); activeSince[i] >= 0 -> Rgba(255, 220, 120); i in conflicted -> Rgba(230, 80, 90, .8); else -> Rgba(lit.red + 30, lit.green + 30, lit.blue + 30, .55) }
            if (!bare) items.add(key j DrawItem.Path("d-$i", disc(p, r, sides), fill = Rgba(lit.red, lit.green, lit.blue, if (splits) .28 else .8), stroke = rim, width = if (hot) 1.6 else .8))
            if (!bare && activeSince[i] >= 0) items.add(key - .001 j DrawItem.Path("pulse-$i", disc(p, r * (1.1 + .08 * sin((now - activeSince[i]) / 90.0)), sides), stroke = Rgba(255, 240, 200, .9), width = 1.4))
            if (!splits && section && kids > 0 && r >= 3) {
                // An aggregate section: its law at a glance, statements by force as a band across it (shown forces only).
                var x0 = p.x - r * .7
                val all = IntArray(kids) { kidList[kidStart[i] + it] }
                for ((fi, fc) in FORCES.withIndex()) {
                    if (hidden and (1 shl fi) != 0) continue
                    val share = all.count { force(it) == fc }.toDouble() / kids; if (share <= 0) continue
                    items.add(key - .001 j DrawItem.Path("f-$i-$fc", box(Rect(x0, p.y - r * .08, r * 1.4 * share, max(1.0, r * .16))), fill = forceColor(fc)))
                    x0 += r * 1.4 * share
                }
            }
            val name = when { book -> bookName(i).removeSuffix(".pdf"); pool -> "concepts"; else -> keys[i].substringAfterLast('/') }
            val count = when { book -> "$kids §"; pool -> "$kids classes"; section -> "$kids st"; else -> "$kids" }
            val text = if (splits || r < 30) name else "$name · $count"
            val size = (if (book || pool) r / 7 else r / 5).coerceIn(if (book || pool) 11.0 else 9.0, if (book || pool) 20.0 else 14.0)
            // A book's or the pool's title always shows: inside when it fits, else on the rim above it.
            val inside = text.length * size * .56 <= 2 * r
            val y = if (splits || !inside) p.y - r - 4 else p.y + size * .35
            val at = Vec3(p.x - min(r, text.length * size * .28), y); val w = max(2 * r, if (book || pool) 240.0 else LABEL_W)
            // Inside titles and a book's or the pool's title always print; a section's rim title prints where it lands free.
            if (!bare && (inside || hot || book || pool || (r >= LABEL_R && claim(Rect(at.x, y - size, min(w, text.length * size * .56), size * 1.3))))) {
                if (inside || hot || book || pool) claim(Rect(at.x, y - size, min(w, text.length * size * .56), size * 1.3))
                labels.add(DrawItem.Text("dl-$i", text, at, if (book || pool) Rgba(240, 214, 150) else Rgba(226, 230, 236, .9),
                    size, maxWidth = w, weight = if (book || pool) 600 else 400))
            }
            if (splits) for (k in kidStart[i] until kidStart[i + 1]) { val q = kidList[k]; if (disc[q] == i) circle(q, Vec3(c.x + ox[q], c.y + oy[q], c.z), key) }
        }
        for (u in units) for (t in u.discs) {
            val c = center(t) ?: continue
            val d = cam.project(c, viewport)?.z ?: (focal() / zoom)
            circle(t, c, d)
        }

        // The open book's pages: fanned leaves, and the spread readable once it is large enough.
        val b = open
        var spread: Rect? = null
        if (b >= 0) for ((q, pageAt) in pageQuads(b).withIndex()) {
            val s = pageAt.a
            val p0 = pageAt.b.map { cam.project(it, viewport) }
            if (p0.any { it == null }) continue
            val p = p0.filterNotNull()
            val depth = p.sumOf { it.z } / 4 - q * 1e-6
            val flat = s == leaves.getOrNull(page) || s == leaves.getOrNull(page - 1)
            val paper = if (flat) PAPER else mix(PAPER, Rgba(120, 110, 90), .45)
            items.add(depth j DrawItem.Path("pg-$s", s_[PathPart.Move(p[0]), PathPart.Line(p[1]), PathPart.Line(p[2]), PathPart.Line(p[3]), PathPart.Close],
                fill = paper, stroke = if (s == hovered || s == selected) Rgba(255, 255, 255) else if (s in conflicted) Rgba(200, 60, 70, .8) else Rgba(90, 80, 60, .6), width = .8))
            val sheet = Rect(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x } - p.minOf { it.x }, p.maxOf { it.y } - p.minOf { it.y })
            drawnPages.add(s j sheet)
            if (flat) spread = spread?.let { u -> Rect(min(u.x, sheet.x), min(u.y, sheet.y), max(u.right, sheet.right) - min(u.x, sheet.x), max(u.bottom, sheet.bottom) - min(u.y, sheet.y)) } ?: sheet
            if (!flat || sheet.intersection(vp) == null) continue
            // Text: sized to the page, wrapped to its width; below a legible size the lines are greeked as bars.
            val size = sheet.height / PAGE_LINES; val pad = sheet.width * .06
            var y = sheet.y + pad + size
            val clip = sheet
            for (line in lines(s)) {
                val ls = size * line.size
                val per = max(8, ((sheet.width - 2 * pad) / (ls * (if (line.mono) .6 else .5))).toInt())
                for (row in line.text.chunked(per)) {
                    if (y > sheet.bottom - pad) break
                    if (ls >= 6) labels.add(DrawItem.Text("pt-$s-${y.toInt()}", row, Vec3(sheet.x + pad, y), line.color, ls, font = if (line.mono) "monospace" else "serif",
                        maxWidth = sheet.width - 2 * pad, clip = clip, weight = if (line.size > 1.2) 700 else 400))
                    else over.add(DrawItem.Path("pb-$s-${y.toInt()}", box(Rect(sheet.x + pad, y - ls * .6, (sheet.width - 2 * pad) * row.length / per, max(.6, ls * .5))),
                        fill = Rgba(line.color.red, line.color.green, line.color.blue, .5)))
                    y += ls * 1.35
                }
            }
        }

        // The open book's close: a box at the spread's top-right corner, kept on screen.
        closer = null
        spread?.let { u ->
            val s = 22.0
            val x = min(u.right + 6, vp.width - s - 6).coerceAtLeast(6.0); val y = max(u.y - s - 6, 6.0)
            val r = Rect(x, y, s, s); closer = r
            over.add(DrawItem.Path("close-box", box(r), fill = Rgba(30, 26, 20, .85), stroke = Rgba(240, 214, 150), width = 1.2))
            over.add(DrawItem.Path("close-x1", s_[PathPart.Move(Vec3(x + 6, y + 6)), PathPart.Line(Vec3(x + s - 6, y + s - 6))], stroke = Rgba(240, 214, 150), width = 1.6))
            over.add(DrawItem.Path("close-x2", s_[PathPart.Move(Vec3(x + s - 6, y + 6)), PathPart.Line(Vec3(x + 6, y + s - 6))], stroke = Rgba(240, 214, 150), width = 1.6))
        }

        // Bundles between shelves: citations, conflicts, shared statements and readings as thin strands, woven through looms.
        val relations = hidden and (1 shl RELATIONS) == 0
        // Per-relation strands: for the node in hand, among what is split open on screen, or while fresh; one loom per predicate.
        val focus = if (hovered >= 0) hovered else selected
        var strands = 0
        class Links(val tone: Rgba) { val says = ArrayList<String>(); val ls = IntColumn(); val pts = ArrayList<List<Vec3>>(); val alpha = ArrayList<Double>(); var sx = 0.0; var sy = 0.0 }
        val byLabel = LinkedHashMap<String, Links>()
        for (l in (if (relations) linkFrom.n - 1 else -1) downTo 0) {
            if (strands >= LINKS) break
            val a0 = linkFrom[l]; val b0 = linkTo[l]
            if (!visible(a0) || !visible(b0)) continue
            val fresh = exp(-(now - linkAt[l]) / GLOW_MS)
            val focused = focus >= 0 && (a0 == focus || b0 == focus || parents[a0] == focus || parents[b0] == focus)
            val visible = a0 in shown && b0 in shown
            if (!focused && !visible && fresh < .35) continue
            val ctl = route(a0, b0); if (ctl.size < 2) continue
            val pts = spline(ctl, cam); if (pts.size < 3) continue
            strands++
            val label = linkLabel[l]
            val tone = when { label.startsWith("conflict") -> BUNDLE_TONES[CONFLICT]; label == "cites" -> BUNDLE_TONES[CITES]; label == "subject" -> Rgba(120, 190, 240)
                else -> forceColor(label.substringBefore(' ')) }
            // One loom per predicate: conflicts, cites, subject, or the force a statement's modality names.
            val predicate = when { label.startsWith("conflict") -> BUNDLE_NAMES[CONFLICT]; label == "cites" -> BUNDLE_NAMES[CITES]; else -> label.substringBefore(' ').substringBefore(':') }
            val g = byLabel.getOrPut(predicate) { Links(tone) }; g.says.add(label)
            val m = pts[pts.size / 2]
            g.ls.add(l); g.pts.add(pts); g.alpha.add(if (focused) .55 else (.08 + .5 * fresh)); g.sx += m.x; g.sy += m.y
        }
        val links = byLabel.map { (label, g) ->
            Warp("r$label", label, g.tone, Vec3(g.sx / g.ls.n, g.sy / g.ls.n)).also { w ->
                for (k in 0 until g.ls.n) { w.threads.add(g.pts[k]); w.from.add(linkFrom[g.ls[k]]); w.to.add(linkTo[g.ls[k]]); w.weight.add(1); w.alpha.add(g.alpha[k]); w.says.add(g.says[k]) }
            }
        }
        if (relations) {
            loom(cam, over, plates, labels, links)
            // Scene text under a loom band is dropped, not overprinted: text rides its own layer over every path.
            labels.removeAll { t -> t is DrawItem.Text && !t.entityId.startsWith("lt-") && !t.entityId.startsWith("ln-") &&
                Rect(t.position.x, t.position.y - t.size, min(t.maxWidth ?: 1e9, t.text.length * t.size * .56), t.size * 1.3).let { b -> loomRects.any { b.intersection(it) != null } } }
        } else { loomHits.clear(); loomRects.clear() }
        // The trail: the followed run's path, older segments fading.
        val path = trail(TRAIL).mapNotNull { n -> center(n)?.let { cam.project(it, viewport) } }
        for (k in 1 until path.size) {
            val age = (path.size - k).toDouble() / path.size
            over.add(DrawItem.Path("trail-$k", s_[PathPart.Move(path[k - 1]), PathPart.Line(path[k])],
                stroke = Rgba(255, 214, 120, (0.7 * (1 - age)).coerceIn(.06, .7)), width = 1.2))
        }
        spikes(over, labels)
        return FramePlan(viewport, (items.sortedByDescending { it.a }.map { it.b } + over + plates + labels).toSeries(), Rgba(11, 14, 20))
    }

    // ── the looms: every strand drawn between two places passes a [WireLoom] band, one per predicate ──
    /** One loom's warp: its strands (projected, with their ends and weight), where it stands, and what its rows read. */
    private class Warp(val key: String, val title: String, val tone: Rgba, val anchor: Vec3) {
        val threads = ArrayList<List<Vec3>>(); val from = IntColumn(); val to = IntColumn(); val says = ArrayList<String>()
        val weight = IntColumn(); val alpha = ArrayList<Double>()
    }

    /**
     * Bundles between shelves gather one loom per predicate and per what their far end is (another book, or the
     * concept pool), standing at the count-weighted mean of their routes; the per-relation strands of the node in
     * hand (or fresh) gather one loom per predicate, at the mean of their screen middles. All looms relax apart
     * together; one label per loom replaces a label per strand; opened, a loom lists its strands' ends, heaviest
     * first, and a row zooms onto both.
     */
    private fun loom(cam: GraphCamera, strands: MutableList<DrawItem>, plates: MutableList<DrawItem>, labels: MutableList<DrawItem>, links: List<Warp>) {
        val warps = ArrayList<Warp>()
        class Sum(val kind: Int, val pool: Boolean) { val pairs = ArrayList<Long>(); val routes = ArrayList<List<Vec3>>(); var wx = 0.0; var wy = 0.0; var wz = 0.0; var w = 0.0 }
        val sums = LinkedHashMap<Int, Sum>()
        for ((pair, n) in bundles) {
            val a = (pair ushr 32).toInt(); val b = (pair and 0xffffffffL).toInt()
            val ctl = route(a, b); if (ctl.size < 2) continue
            val mid = ctl[ctl.size / 2].lerp(ctl[(ctl.size - 1) / 2], .5)
            for (kind in 0 until 4) {
                if (n[kind] == 0) continue
                val pool = isPool(a) || isPool(b)
                val u = sums.getOrPut(kind * 2 + if (pool) 1 else 0) { Sum(kind, pool) }
                val wt = ln(1.0 + n[kind]); u.pairs.add(pair); u.routes.add(ctl); u.wx += mid.x * wt; u.wy += mid.y * wt; u.wz += mid.z * wt; u.w += wt
            }
        }
        for ((key, u) in sums) {
            val at = cam.project(Vec3(u.wx / u.w, u.wy / u.w, u.wz / u.w), viewport) ?: continue
            val w = Warp("b$key", BUNDLE_NAMES[u.kind] + if (u.pool) " · concepts" else "", BUNDLE_TONES[u.kind], at)
            for ((k, pair) in u.pairs.withIndex()) {
                val pts = spline(u.routes[k], cam); if (pts.size < 3) continue
                val n = bundles[pair]!![u.kind]
                w.threads.add(pts); w.from.add((pair ushr 32).toInt()); w.to.add((pair and 0xffffffffL).toInt()); w.weight.add(n); w.says.add("")
                w.alpha.add((.12 + .06 * ln(1.0 + n)).coerceAtMost(.5))
            }
            if (w.threads.isNotEmpty()) warps.add(w)
        }
        warps.addAll(links.filter { it.threads.isNotEmpty() })
        loomHits.clear(); loomRects.clear()
        if (warps.isEmpty()) return
        // A closed loom is one line; an opened one a row per strand, each strand pinned to its row.
        warps.sortBy { it.key }
        val sizes = warps.map { Vec3(if (it.key in loomsOpen) LOOM_OPEN_W else LOOM_W, LOOM_ROW * (1 + if (it.key in loomsOpen) min(it.threads.size, LOOM_ROWS) else 0)) }
        // Eased from where each loom stood last frame, so a loom glides to its place rather than jumping to it.
        val centers = WireLoom.relax(warps.map { it.anchor }, sizes, warps.map { loomAt[it.key] }).mapIndexed { q, c ->
            loomAt[warps[q].key]?.let { p -> p.lerp(c, LOOM_EASE) } ?: c }
        for ((q, w) in warps.withIndex()) {
            val c = centers[q]; loomAt[w.key] = c
            val band = Rect(c.x - sizes[q].x / 2, c.y - sizes[q].y / 2, sizes[q].x, sizes[q].y); loomRects.add(band)
            val tone = w.tone; val ink = Rgba(tone.red, tone.green, tone.blue, .85)
            val open = w.key in loomsOpen
            val order = w.threads.indices.sortedByDescending { w.weight[it] }
            // Opened, the heaviest strands take a row each under the header; the rest pass the header row.
            val rows = if (open) IntArray(w.threads.size).also { r -> order.forEachIndexed { k, t -> r[t] = if (k < LOOM_ROWS) k + 1 else 0 } } else null
            val gap = if (open) LOOM_ROW else LOOM_ROW / max(1, w.threads.size)
            for ((t, flow) in WireLoom.weave(w.threads, if (open) band else Rect(band.x, band.y, band.width, LOOM_ROW), gap, rows).withIndex()) {
                val n = w.weight[t]
                strands.add(DrawItem.Path("lw-${w.key}-$t", flow.size j { k: Int -> if (k == 0) PathPart.Move(flow[0]) else PathPart.Line(flow[k]) },
                    stroke = Rgba(tone.red, tone.green, tone.blue, w.alpha[t]), width = (.6 + .25 * ln(1.0 + n)).coerceAtMost(2.2)))
            }
            plates.add(DrawItem.Path("loom-${w.key}", box(band), fill = Rgba(14, 18, 26, if (open) .88 else .7), stroke = Rgba(tone.red, tone.green, tone.blue, .6), width = .8))
            fun text(id: String, s: String, y: Double, n: Int?, color: Rgba) {
                labels.add(DrawItem.Text("lt-$id", s, Vec3(band.x + 4, y + LOOM_ROW - 4), color, 10.0, font = "monospace", maxWidth = band.width - 48))
                if (n != null) "$n".let { labels.add(DrawItem.Text("ln-$id", it, Vec3(band.x + band.width - it.length * 6.0 - 4, y + LOOM_ROW - 4), color, 10.0, font = "monospace")) }
            }
            var total = 0; for (t in w.threads.indices) total += w.weight[t]
            text(w.key, (if (open) "▾ " else "▸ ") + w.title + " · ${w.threads.size}", band.y, total, ink)
            val key = w.key
            loomHits.add(Rect(band.x, band.y, band.width, LOOM_ROW) j { if (!loomsOpen.remove(key)) loomsOpen.add(key) })
            if (!open) continue
            for ((k, t) in order.take(LOOM_ROWS).withIndex()) {
                val a = w.from[t]; val b = w.to[t]; val y = band.y + (k + 1) * LOOM_ROW
                val says = w.says[t].takeIf { it.isNotEmpty() && it != w.title }?.let { "$it: " }.orEmpty()
                text("$key-$t", "$says${name(a).take(20)} ⇄ ${name(b).take(20)}", y, w.weight[t].takeIf { it > 1 }, Rgba(200, 208, 220, .9))
                loomHits.add(Rect(band.x, y, band.width, LOOM_ROW) j { selected = -1; close(); both = intArrayOf(a, b); aim = true })
            }
        }
    }

    // ── the fill/drain sparkline: events per 250 ms over 30 s, a spike is a bin over 3× the mean ──
    private fun spikes(items: MutableList<DrawItem>, labels: MutableList<DrawItem>) {
        val bins = IntArray(BINS); var seq = evHead - 1
        while (seq >= 0 && seq >= evHead - cap) {
            val s = (seq % cap).toInt(); val b = ((now - evT[s]) / BIN_MS).toInt()
            if (b >= BINS) break; if (b >= 0) bins[BINS - 1 - b]++; seq--
        }
        val mean = bins.average().coerceAtLeast(.5); val peak = max(1, bins.max())
        val x0 = 14.0; val y0 = viewport.height - 16.0; val w = 3.0
        for (i in 0 until BINS) {
            val h = 40.0 * bins[i] / peak; if (h <= 0) continue
            val x = x0 + i * w
            items.add(DrawItem.Path("spike-$i", s_[PathPart.Move(Vec3(x, y0)), PathPart.Line(Vec3(x + w - 1, y0)),
                PathPart.Line(Vec3(x + w - 1, y0 - h)), PathPart.Line(Vec3(x, y0 - h)), PathPart.Close],
                fill = if (bins[i] > 3 * mean) Rgba(236, 96, 88) else Rgba(96, 170, 150)))
        }
        val rate = bins.takeLast(4).sum()
        // The law so far: statements by force, concepts, relations — the numbers a reader watches climb.
        val (must, mustNot, may, presumed) = tally.let { listOf(it[0], it[1], it[2], it[3]) }
        val statements = tally[4]; val concepts = tally[5]; val sections = tally[6]
        toggles.clear()
        if (statements > 0) {
            val yT = 28.0; var xT = 14.0
            // Each count is also its category's switch: the box before its name masks that category in the scope.
            fun cell(label: String, bit: Int, n: Int, c: Rgba) {
                val on = hidden and (1 shl bit) == 0
                val w = max(70.0, 14.0 * "$n".length + 30)
                labels.add(DrawItem.Text("tally-$label", "$n", Vec3(xT, yT), if (on) c else Rgba(c.red, c.green, c.blue, .3), 22.0, weight = 700))
                items.add(DrawItem.Path("tally-b-$label", box(Rect(xT, yT + 5, 10.0, 10.0)), fill = if (on) c else null, stroke = c, width = 1.0))
                labels.add(DrawItem.Text("tally-l-$label", label, Vec3(xT + 14, yT + 14), Rgba(150, 160, 176, if (on) 1.0 else .5), 10.0))
                toggles.add(bit j Rect(xT, yT - 22, w - 8, 42.0))
                xT += w
            }
            cell("sections", SECTIONS, sections, Rgba(233, 226, 206)); cell("must", 0, must, forceColor("must")); cell("must not", 1, mustNot, forceColor("must-not"))
            cell("may", 2, may, forceColor("may")); cell("presumed", 3, presumed, forceColor("presumed"))
            cell("concepts", CONCEPTS, concepts, Rgba(140, 210, 255)); cell("relations", RELATIONS, linkFrom.n, Rgba(240, 190, 110))
        }
        labels.add(DrawItem.Text("hud", "ring ${cursor}  ·  ${rate}/s  ·  drained $lastDrained  ·  lost $dropped  ·  " +
            (if (pinned) "pinned — F follows" else if (following()) "following ${runNames.getOrNull(followed()) ?: "—"}" else if (now - lastEventAt < LIVE_MS) "yours — F follows" else "idle — F frames the library"),
            Vec3(x0 + BINS * w + 12, y0 - 4), Rgba(170, 182, 196), 12.0, font = "monospace"))
    }

    /** What the illustrative panel shows for the node in view: hovered, the open book's right-hand page, selected, or the production's head. */
    fun panel(): Map<String, Any?>? {
        val i = when { hovered >= 0 && hovered != open -> hovered; open >= 0 && leaves.isNotEmpty() -> leaves[page]; selected >= 0 -> selected; else -> trail(1).firstOrNull() ?: -1 }
        if (i !in keys.indices) return null
        val m = CuratorMetaphors.of(types[i])
        val story = ArrayList<String>(); var seq = evHead - 1
        while (seq >= 0 && seq >= evHead - cap && story.size < 8) {
            val s = (seq % cap).toInt()
            if (evNode[s] == i) story.add("${runNames.getOrNull(evRun[s]) ?: "run ${evRun[s]}"}: ${verbOf(m, evKind[s])}" + (evNote[s]?.let { " — $it" } ?: ""))
            seq--
        }
        val relations = ArrayList<String>()
        for (l in linkFrom.n - 1 downTo 0) {
            if (relations.size >= 40) break
            if (linkFrom[l] == i) relations.add("→ ${linkLabel[l]} → ${keys[linkTo[l]].substringAfterLast('/')}")
            else if (linkTo[l] == i) relations.add("${lastNote[linkFrom[l]] ?: keys[linkFrom[l]].substringAfterLast('/')}  [${linkLabel[l]}]")
        }
        return linkedMapOf("key" to keys[i], "type" to types[i], "metaphor" to m.name, "shape" to m.shape.name, "relations" to relations,
            "degree" to degree[i], "glyph" to glyph(i), "glyphColor" to tone(i).css,
            "color" to m.color.css, "gloss" to m.gloss, "verb" to verb(i), "runs" to runs[i],
            "meanMs" to if (runs[i] > 0) spent[i] / runs[i] else null, "active" to (activeSince[i] >= 0), "story" to story)
    }

    private fun verb(i: Int) = verbOf(CuratorMetaphors.of(types[i]), lastKind[i])
    private fun verbOf(m: CuratorMetaphors.Metaphor, kind: Int) = when (kind) {
        BEGIN -> m.begin; END -> m.end; RING -> "opens a bough"; YIELD -> "bears fruit"; TURN -> "turns again"
        SKIP -> "sleeps (unfed)"; FAIL -> "withers"; else -> m.end
    }

    private fun growth(i: Int) = ((now - born[i]) / GROW_MS).coerceIn(0.0, 1.0).let { 1 - (1 - it).pow(3) }
    /** 1 at the moment of work, fading to 0 over [GLOW_MS]; held at 1 while running. */
    private fun heat(i: Int) = if (activeSince[i] >= 0) 1.0 else exp(-(now - max(lastBegin[i], lastEnd[i])) / GLOW_MS)

    private fun box(r: Rect): Series<PathPart> = s_[PathPart.Move(Vec3(r.x, r.y)), PathPart.Line(Vec3(r.right, r.y)),
        PathPart.Line(Vec3(r.right, r.bottom)), PathPart.Line(Vec3(r.x, r.bottom)), PathPart.Close]

    private fun disc(c: Vec3, r: Double, n: Int): Series<PathPart> = (n + 1) j { k: Int ->
        if (k == n) PathPart.Close else Vec3(c.x + cos(k * 2 * PI / n) * r, c.y + sin(k * 2 * PI / n) * r).let { if (k == 0) PathPart.Move(it) else PathPart.Line(it) }
    }

    /** Node [i]'s outline as the scope draws it, centered on ([c]) at radius [r]. */
    private fun outline(i: Int, c: Vec3, r: Double): Series<PathPart> =
        if (types[i] == "concept") shape(CuratorMetaphors.Shape.STAR, c, r) else disc(c, r, (r * .8).toInt().coerceIn(8, 40))

    /** Node [i]'s resting colour in the scope: a concept's star by degree, a statement by its force, else its disc. */
    private fun tone(i: Int): Rgba = when {
        types[i] == "concept" -> mix(Rgba(120, 160, 210), Rgba(255, 244, 200), sqrt(degree[i].toDouble() / max(1, maxConcept)))
        types[i] == "book.statement" -> forceColor(force(i))
        isBook(i) -> Rgba(96, 72, 44); isPool(i) -> Rgba(34, 52, 78)
        types[i] == "book.section" -> Rgba(70, 60, 44)
        else -> Rgba(40, 62, 92)
    }

    /** The legend glyph for node [i]: its [outline] as an SVG path in a 44-unit box. */
    private fun glyph(i: Int): String {
        val out = StringBuilder()
        val parts = outline(i, Vec3(22.0, 22.0), 20.0)
        for (k in 0 until parts.a) when (val p = parts.b(k)) {
            is PathPart.Move -> out.append('M').append(p.p.x.roundToInt()).append(' ').append(p.p.y.roundToInt())
            is PathPart.Line -> out.append('L').append(p.p.x.roundToInt()).append(' ').append(p.p.y.roundToInt())
            else -> out.append('Z')
        }
        return out.toString()
    }

    /** Star-shaped outlines around their center, fanned from it, so the triangle provider needs no tessellator. */
    private fun shape(s: CuratorMetaphors.Shape, c: Vec3, r: Double): Series<PathPart> {
        val ring: List<Vec3> = when (s) {
            CuratorMetaphors.Shape.STAR -> (0 until 8).map { k -> val a = -PI / 2 + k * PI / 4; val q = if (k % 2 == 0) r else r * .38; Vec3(c.x + cos(a) * q, c.y + sin(a) * q) }
            else -> (0 until 14).map { k -> val a = k * 2 * PI / 14; Vec3(c.x + cos(a) * r, c.y + sin(a) * r) }
        }
        val pts = listOf(c) + ring + ring.first()
        return (pts.size + 1) j { k: Int -> if (k == pts.size) PathPart.Close else if (k == 0) PathPart.Move(pts[0]) else PathPart.Line(pts[k]) }
    }

    private fun mix(a: Rgba, b: Rgba, t: Double): Rgba { val u = t.coerceIn(0.0, 1.0)
        return Rgba((a.red + (b.red - a.red) * u).roundToInt(), (a.green + (b.green - a.green) * u).roundToInt(),
            (a.blue + (b.blue - a.blue) * u).roundToInt(), a.alpha + (b.alpha - a.alpha) * u) }
    private fun dim(c: Rgba) = Rgba(c.red / 3 + 20, c.green / 3 + 22, c.blue / 3 + 26)
    private fun num(v: Any?) = (v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull() ?: 0.0

    companion object {
        // LcncTrail.Kind ordinals.
        const val RUN = 0; const val BEGIN = 1; const val END = 2; const val SKIP = 3; const val RING = 4
        const val YIELD = 5; const val TURN = 6; const val FAIL = 7; const val LINK = 8
        /** [disc] markers: a book or the concept pool stands on a shelf; a node outside the library is not placed. */
        const val TOP = -1; const val UNPLACED = -2
        /** Bundle kinds between top circles. */
        const val CITES = 0; const val CONFLICT = 1; const val SHARED = 2; const val READS = 3
        val BUNDLE_NAMES = listOf("cites", "conflicts", "shared", "reads")
        val BUNDLE_TONES = listOf(Rgba(110, 200, 230), Rgba(240, 90, 100), Rgba(240, 200, 110), Rgba(120, 150, 220))
        val FORCES = listOf("must", "must-not", "may", "presumed")
        /** Mask bits beside the force ordinals 0..3: a set bit hides that category in the scope. */
        const val CONCEPTS = 5; const val SECTIONS = 6; const val RELATIONS = 7
        /** Name index key: the first [NAME_CHARS] characters at 6 bits each over the ordinal's [ORDINAL_BITS]. */
        const val NAME_CHARS = 7; const val ORDINAL_BITS = 21; const val ORDINAL = (1L shl ORDINAL_BITS) - 1
        private const val ALPHABET = " 0123456789abcdefghijklmnopqrstuvwxyz"

        /** [name] folded to a sortable key: 6-bit symbols (space, digits, letters in order; anything else sorts last). */
        fun nameKey(name: String, ordinal: Int): Long {
            var k = 0L
            for (j in 0 until NAME_CHARS) {
                val c = name.getOrNull(j)?.lowercaseChar()
                val v = if (c == null) 0 else ALPHABET.indexOf(c).let { if (it < 0) 63 else it + 1 }
                k = (k shl 6) or v.toLong()
            }
            return (k shl ORDINAL_BITS) or (ordinal.toLong() and ORDINAL)
        }
        /** A loom band: width closed and opened, and one row's height. */
        const val LOOM_W = 170.0; const val LOOM_OPEN_W = 300.0; const val LOOM_ROW = 14.0; const val LOOM_ROWS = 24; const val LOOM_EASE = .2
        /** Relation strands drawn per frame, newest first. */
        const val LINKS = 1500
        /** World radius of a leaf star before its degree; packing gap between circles. */
        const val STAR = 2.0; const val PAD = 1.0
        /** Books per shelf. */
        const val ROW = 4
        /** Screen radius under which a circle is carried by its container. */
        const val MIN_PX = .6
        /** Deepest zoom: a statement's circle reaches several pixels and its note reads beside it. */
        const val DEEPEST = LandscapeNavigation.detailZoom * 16
        /** A circle this large on screen is labelled when its label lands on free cells ([CELL] px occupancy); labels run at most [LABEL_W] px. */
        const val LABEL_R = 2.0; const val CELL = 6.0; const val LABEL_W = 220.0
        /** A statement or concept circle this large on screen holds its text inside. */
        const val INSIDE_R = 60.0
        /** However low the snap depth, children under this mean screen radius stay aggregated in their container. */
        const val MIN_SPLIT_PX = 2.0
        /** An open book rises toward the camera by this share of its radius. */
        const val OPEN_LIFT = .6; const val OPEN_MS = 450.0
        /** Leaves fanned either side of the spread, the angle between them, and the fan's limit (degrees). */
        const val FAN = 12; const val FAN_STEP = 5.0; const val FAN_MAX = 80.0
        /** Lines a page holds at body size. */
        const val PAGE_LINES = 46.0
        /** Holten's bundling strength; spline samples per control span. */
        const val BUNDLE = .85; const val SPLINE = 6
        /** The eye stands above the plane it looks at by this share of its distance. */
        const val TILT = .18
        const val HUD = 70.0
        const val GROW_MS = 700.0; const val GLOW_MS = 2400.0; const val EASE = .08
        /** A run is producing while its latest event is younger than this. */
        const val LIVE_MS = 4000.0
        const val FOLLOW = 10; const val TRAIL = 48; const val BINS = 120; const val BIN_MS = 250.0
        val PAPER = Rgba(236, 228, 206); val INK = Rgba(40, 34, 28); val FADED = Rgba(120, 110, 96); val CLASS_INK = Rgba(40, 80, 130)
        val INK_LIGHT = Rgba(236, 230, 214)
    }
}
