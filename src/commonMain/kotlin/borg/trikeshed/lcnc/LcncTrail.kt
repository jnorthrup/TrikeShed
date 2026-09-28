package borg.trikeshed.lcnc

import borg.trikeshed.isam.synchronizedLock
import kotlin.time.TimeSource

/**
 * The LCNC activity ring: every statement a walk touches is one event in packed columns (time,
 * kind|run, node), filled by [LcncRunner] and drained by readers at their own cursor. A reader that
 * falls more than [CAPACITY] behind is told how many events it lost: backlog and loss are the spike.
 *
 * Nodes intern once by their ring path (`ring/…/id`), so a program run twice lights the same nodes.
 */
class LcncTrail(capacity: Int = CAPACITY) {
    /** LINK: a relation between two nodes beside the tree — node is the source, note `<target ordinal>|<label>`. */
    enum class Kind { RUN, BEGIN, END, SKIP, RING, YIELD, TURN, FAIL, LINK }

    private val mask = capacity - 1
    private val times = LongArray(capacity)
    private val codes = LongArray(capacity)
    private val notes = arrayOfNulls<String>(capacity)
    private var head = 0L
    private val origin = TimeSource.Monotonic.markNow()
    /** This ring's identity: a reader's cursor and node ordinals mean something only under the epoch that issued them. */
    // Within 2^53: it travels as a JSON number and must survive a double (a browser's) exactly.
    val epoch: Long = kotlin.random.Random.nextLong(1L, 1L shl 53)

    private val nodeIndex = HashMap<String, Int>()
    private val nodeKeys = ArrayList<String>()
    private val nodeTypes = ArrayList<String>()
    private val nodeParents = ArrayList<Int>()
    private val runNames = ArrayList<String>()

    init { require(capacity > 0 && capacity and mask == 0) { "capacity must be a power of 2" } }

    /** Opens a run: its ordinal tags every event of the walk. */
    fun run(program: String): Int = synchronizedLock(this) {
        runNames.add(program)
        (runNames.size - 1).also { put(it, Kind.RUN, -1, program) }
    }

    /**
     * Interns the node at [path] (ring names outermost first) of [program] and returns its ordinal.
     * The program itself is the root row (type `program`); a ring's row parents its statements.
     */
    fun node(program: String, path: List<String>, id: String, type: String): Int = synchronizedLock(this) {
        val root = intern(program, "program", -1)
        var parent = root; var prefix = program
        for (ring in path) { prefix = "$prefix/$ring"; parent = nodeIndex[prefix] ?: intern(prefix, "scope", parent) }
        intern("$prefix/$id", type, parent)
    }

    private fun intern(key: String, type: String, parent: Int): Int = nodeIndex.getOrPut(key) {
        nodeKeys.add(key); nodeTypes.add(type); nodeParents.add(parent)
        nodeKeys.size - 1
    }

    /** True when a node with ring path [key] (`program/ring/…/id`) is interned. */
    fun known(key: String): Boolean = synchronizedLock(this) { key in nodeIndex }

    fun emit(run: Int, kind: Kind, node: Int, note: String? = null) = synchronizedLock(this) { put(run, kind, node, note) }

    /** A relation from [from] to [to] named [label]: what the tree alone cannot draw. */
    fun link(run: Int, from: Int, to: Int, label: String) = emit(run, Kind.LINK, from, "$to|$label")

    /** The most recently opened run: where a runner's own pointcuts land. */
    fun lastRun(): Int = synchronizedLock(this) { runNames.size - 1 }

    private fun put(run: Int, kind: Kind, node: Int, note: String?) {
        val slot = (head and mask.toLong()).toInt()
        times[slot] = origin.elapsedNow().inWholeNanoseconds
        codes[slot] = (run.toLong() shl 40) or (kind.ordinal.toLong() shl 32) or (node.toLong() and 0xffffffffL)
        notes[slot] = note?.take(NOTE)
        head++
    }

    /**
     * Drains from [cursor]: the events after it as columns, the node rows past [knownNodes], and
     * `dropped` — events the ring overwrote before this reader came back.
     */
    fun since(cursor: Long, knownNodes: Int, epoch: Long = this.epoch): Map<String, Any?> = synchronizedLock(this) {
        // A cursor issued by another ring (the process restarted) means nothing here, even when it happens
        // to be in range: the reader starts over and `reset` tells it to drop its node table.
        val reset = epoch != this.epoch || cursor > head || knownNodes > nodeKeys.size
        val start = if (reset) 0L else cursor
        val from = maxOf(start, head - (mask + 1), 0L)
        val n = (head - from).toInt()
        val t = ArrayList<Double>(n); val kind = ArrayList<Int>(n); val run = ArrayList<Int>(n)
        val node = ArrayList<Int>(n); val note = ArrayList<String?>(n)
        for (seq in from until head) {
            val slot = (seq and mask.toLong()).toInt(); val c = codes[slot]
            t.add(times[slot] / 1e6); run.add((c ushr 40).toInt()); kind.add(((c ushr 32) and 0xff).toInt())
            node.add((c and 0xffffffffL).toInt().let { if (it == -1) -1 else it }); note.add(notes[slot])
        }
        val k = if (reset) 0 else knownNodes.coerceIn(0, nodeKeys.size)
        linkedMapOf(
            "head" to head, "from" to from, "dropped" to (from - start).coerceAtLeast(0L), "reset" to reset, "epoch" to this.epoch,
            "now" to origin.elapsedNow().inWholeNanoseconds / 1e6,
            "t" to t, "kind" to kind, "run" to run, "node" to node, "note" to note,
            "nodesFrom" to k, "nodeKeys" to nodeKeys.subList(k, nodeKeys.size).toList(),
            "nodeTypes" to nodeTypes.subList(k, nodeTypes.size).toList(),
            "nodeParents" to nodeParents.subList(k, nodeParents.size).toList(),
            "runs" to runNames.toList(),
        )
    }

    companion object {
        /** A curated book emits a statement and its relations per clause: tens of thousands of events. */
        const val CAPACITY = 1 shl 20
        const val NOTE = 160
        /** The process's ring: every daemon run fills it; viewers drain it. */
        val live = LcncTrail()

        /** A short reading of a node's outputs: the first few ports and their sizes or values. */
        fun summary(outputs: Map<String, Any?>): String = outputs.entries.take(4).joinToString("  ") { (k, v) ->
            k + "=" + when (v) {
                is Collection<*> -> "[${v.size}]"
                is Map<*, *> -> "{${v.size}}"
                is String -> v.lineSequence().firstOrNull().orEmpty().take(48)
                else -> v.toString().take(24)
            }
        }
    }
}
