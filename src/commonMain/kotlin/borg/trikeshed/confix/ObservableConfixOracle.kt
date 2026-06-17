package borg.trikeshed.confix

import borg.trikeshed.cursor.*
import borg.trikeshed.mutable.CowSeriesHandle
import borg.trikeshed.lib.*

// ── ObservableConfixOracle ─────────────────────────────────────────
//
// Wraps [ConfixOracleService] with a per-listener subscription chain.
// Each xvm ConstantPool gets its own ConfixOracleFacade via getFacade(),
// which gets its own ObservableConfixOracle.
//
// Collapsed architecture:
//   getTypedefChain()  — single entry, replaces dead getLattice()
//   isA()              — unchanged, used by calculateRelation()
//   setListener()       — edge subscription → fires onTypeDefChanged()
//
// Subscription chain:
//   addSource() → oracle.build() → _edges bumps → listener fires →
//   xvm JIT refetches lattice or calls getTypedefChain()

class ObservableConfixOracle(
    /** When true, isA() returns true for completely unclaimed type pairs (both unknown → Class). */
    var defaultUnknownToClass: Boolean = false,
) : ConfixOracleFacade {

    private val service = ConfixOracleService()
    private var edgeSub: (() -> Unit)? = null

    // ── ConfixOracleFacade fwd ─────────────────────────────────────

    override fun addSource(sourceText: String, modulePath: String): Int =
        service.addSource(sourceText, modulePath)

    override fun getTypedefChain(modulePath: String, typeName: String): IntArray =
        service.getTypedefChain(modulePath, typeName)

    override fun isA(childPoolIdx: Int, parentPoolIdx: Int): Int? {
        val known = service.isA(childPoolIdx, parentPoolIdx) ?: return null
        if (known == 2) return 2 as Int?           // oracle says YES
        if (known == 1) return 1 as Int?           // oracle says NO
        return if (defaultUnknownToClass) 2 as Int? else 0 as Int?
    }

    override fun setListener(listener: TypeDefListener?) {
        edgeSub?.invoke()
        edgeSub = null

        if (listener != null) {
            edgeSub = service.subscribeEdges { _ ->
                listener.onTypeDefChanged("<dynamic>", "<dynamic>")
            }
        }
    }

    override fun edgeCount(): Int = service.edgeCount()

    // ── public accessors for Java ──────────────────────────────────

    /** The facade to hand to xvm's ConstantPool. */
    fun getFacade(): ConfixOracleFacade = this

    /** Observable edges — subscribe to receive Twin snapshots on every mutation. */
    val edges: CowSeriesHandle<IsAEdge> get() = service.edges

    /** Subscribe to edge mutations. @return cancel to unsubscribe */
    fun subscribeEdges(f: (Twin<Series<IsAEdge>>) -> Unit): () -> Unit =
        service.subscribeEdges(f)
}

// ── ObservableEdgesView ───────────────────────────────────────────
//
// Java-callable adapter — subscribe with int[] callbacks instead of Kotlin lambdas.

class ObservableEdgesView(private val handle: CowSeriesHandle<IsAEdge>) {
    fun subscribe(callback: (oldSnapshot: IntArray, newSnapshot: IntArray) -> Unit): Subscription {
        return handle.subscribe { twin ->
            callback(intArrayOf(twin.a.a), intArrayOf(twin.b.a))
        }
    }

    fun size(): Int = handle.a
}