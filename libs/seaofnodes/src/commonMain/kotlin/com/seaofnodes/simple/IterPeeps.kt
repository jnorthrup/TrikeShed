package com.seaofnodes.simple

import com.seaofnodes.simple.codegen.CodeGen
import com.seaofnodes.simple.node.ConstantNode
import com.seaofnodes.simple.node.Node
import com.seaofnodes.simple.node.StopNode
import com.seaofnodes.simple.print.JSViewer
import com.seaofnodes.simple.util.Ary
import java.util.*
import java.util.function.Function

/**
 * The IterPeeps runs after parsing. It iterates the peepholes to a fixed point
 * so that no more peepholes apply.  This should be linear because peepholes rarely
 * (never?)  increase code size.  The graph should monotonically reduce in some
 * dimension, which is usually size.  It might also reduce in e.g. number of
 * MulNodes or Load/Store nodes, swapping out more "expensive" Nodes for cheaper
 * ones.
 * <br></br>
 * The theoretical overall worklist is mindless just grabbing the next thing and
 * doing it.  If the graph changes, put the neighbors on the worklist.
 * <br></br>
 * Lather, Rinse, Repeat until the worklist runs dry.
 *
 *
 * The main issues we have to deal with:
 *
 *
 *  * Nodes have uses; replacing some set of Nodes with another requires more graph
 * reworking.  Not rocket science, but it can be fiddly.  Its helpful to have a
 * small set of graph munging utilities, and the strong invariant that the graph
 * is stable and correct between peepholes.  In our case `Node.subsume` does
 * most of the munging, building on our prior stable Node utilities.
 *
 *  * Changing a Node also changes the graph "neighborhood".  The neighbors need to
 * be checked to see if THEY can also peephole, and so on.  After any peephole
 * or graph update we put a Nodes uses and defs on the worklist.
 *
 *  * Our strong invariant is that for all Nodes, either they are on the worklist
 * OR no peephole applies.  This invariant is easy to check, although expensive.
 * Basically the normal "iterate peepholes to a fixed point" is linear, and this
 * check is linear at each peephole step... so quadratic overall.  Its a useful
 * assert, but one we can disable once the overall algorithm is stable - and
 * then turn it back on again when some new set of peepholes is misbehaving.
 * The code for this is turned on in `IterPeeps.iterate` as `assert
 * progressOnList(stop);`
 *
 */
class IterPeeps(seed: Long) {
    val _work: WorkList<Node?>

    init {
        _work = WorkList<Node?>(seed)
    }

    fun <N : Node?> add(n: N?): N? {
        return _work.push(n) as N?
    }

    fun addAll(ary: Ary<Node?>) {
        _work.addAll(ary)
    }

    /**
     * Iterate peepholes to a fixed point
     */
    fun iterate(code: CodeGen) {
        assert(progressOnList(code, _work, true))
        var cnt = 0

        var n: Node?
        while ((_work.pop().also { n = it }) != null) {
            if (n!!.isDead()) continue
            cnt++ // Useful for debugging, searching which peephole broke things
            val x = n.peepholeOpt()
            if (x != null) {
                if (x.isDead()) continue
                // peepholeOpt can return brand-new nodes, needing an initial type set
                if (x._type == null) x.setType(x.compute())
                // Changes require neighbors onto the worklist
                if (x !== n || x !is ConstantNode) {
                    // All outputs of n (changing node) not x (prior existing node).
                    for (z in n._outputs) _work.push(z)
                    // Everybody gets a free "go again" in case they didn't get
                    // made in their final form.
                    _work.push(x)
                    // If the result is not self, revisit all inputs (because
                    // there's a new user), and replace in the graph.
                    if (x !== n) {
                        for (z in n._inputs) _work.push(z)
                        for (z in x._outputs) _work.push(z)
                        n.subsume(x)
                    }
                }
                // If there are distant neighbors, move to worklist
                n.moveDepsToWorklist()
                JSViewer.Companion.show() // Show again
                assert(
                    progressOnList(code, _work, true) // Very expensive assert
                )
            }
            if (n.isUnused() && n !is StopNode) n.kill() // Just plain dead
        }
    }

    /**
     * Classic WorkList, with a fast add/remove, dup removal, random pull.
     * The Node's nid is used to check membership in the worklist.
     */
    class WorkList<E : Node?> internal constructor(seed: Long) {
        private var _es: Array<Node?>
        private var _len: Int
        private val _on: BitSet // Bit set if Node._nid is on WorkList
        private val _R: Random // For randomizing pull from the WorkList
        private val _seed: Long

        /* Useful stat - how many nodes are processed in the post parse iterative opt */
        private var _totalWork: Long = 0

        constructor() : this(123)

        init {
            _es = arrayOfNulls<Node>(1)
            _len = 0
            _on = BitSet()
            _seed = seed
            _R = Random()
            _R.setSeed(_seed)
        }

        /**
         * Pushes a Node on the WorkList, ensuring no duplicates
         * If Node is null it will not be added.
         */
        fun push(x: E?): E? {
            if (x == null) return null
            val idx = x._nid
            if (!_on.get(idx)) {
                _on.set(idx)
                if (_len == _es.length) _es = Arrays.copyOf<Node?>(_es, _len shl 1)
                _es[_len++] = x
                _totalWork++
            }
            return x
        }

        fun addAll(ary: Ary<E?>) {
            for (n in ary) push(n)
        }

        /**
         * True if Node is on the WorkList
         */
        fun on(x: E?): Boolean {
            return _on.get(x!!._nid)
        }

        val isEmpty: Boolean
            get() = _len == 0

        /**
         * Removes a random Node from the WorkList; null if WorkList is empty
         */
        fun pop(): E? {
            if (_len == 0) return null
            val idx = _R.nextInt(_len)
            val x = _es[idx] as E?
            _es[idx] = _es[--_len] // Compress array
            _on.clear(x!!._nid)
            return x
        }

        fun clear() {
            _len = 0
            _on.clear()
            _R.setSeed(_seed)
            _totalWork = 0
        }
    }

    companion object {
        // Visit ALL nodes and confirm the invariant:
        //   Either you are on the _work worklist OR running `iter()` makes no progress.
        // This invariant ensures that no progress is missed, i.e., when the
        // worklist is empty we have indeed done all that can be done.  To help
        // with debugging, the {@code assert} is broken out in a place where it is easy to
        // stop if a change is found.
        // Also, the normal usage of `iter()` may attempt peepholes with distance
        // neighbors and these should fail, but will then try to add dependencies
        // {@link #Node.addDep} which is a side effect in an assert.  The {@link
        // #midAssert} is used to stop this side effect.
        // Pessimistic solver assert
        fun progressOnList(code: CodeGen, list: WorkList<Node?>, dir: Boolean): Boolean {
            code._midAssert = true
            val changed = code._stop.walk<Node?>(Function { n: Node? ->
                var m = n
                val nval = n!!.compute()
                if ((!n.iskeep() || n._nid <= 8) &&  // Types must be forwards, even if on worklist
                    (if (dir)
                        nval.isa(n._type) // Pesi: new value lifts over old
                    else
                        n._type.isa(nval) // Opto: new value falls over old
                            )
                ) {
                    if (list.on(n)) return@walk null
                    m = n.peepholeOpt()
                    if (m == null) return@walk null
                }
                System.err.println("BREAK HERE FOR BUG")
                m
            })
            code._midAssert = false
            return changed == null
        }
    }
}
