@file:Suppress("NonAsciiCharacters")

package borg.trikeshed.pointcut

import borg.trikeshed.context.lcnc.PointcutMark
import borg.trikeshed.cursor.FieldSynapse
import borg.trikeshed.cursor.TypedefProductionSystem
import borg.trikeshed.dag.DagCoordinate
import borg.trikeshed.graal.ConfixBlackboard
import borg.trikeshed.lib.Series
import borg.trikeshed.lib.j
import borg.trikeshed.lib.α
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * M1 — Pointcut → Blackboard adapter.
 *
 * The midpoint between the Truffle-pointcut ring ([TypedefProductionSystem] slabs,
 * [PointcutEvent] guest-VM property hooks) and the polyglot blackboard
 * ([ConfixBlackboard]).
 *
 * Every pointcut observation is normalized to a [DagCoordinate] — the blackboard
 * classfile DAG's primary event fabric coordinate (BlackboardDagFabric.kt) — and
 * landed under the key scheme
 *
 * ```
 * pointcut/<typedef>/<method>/<siteIdx>
 * ```
 *
 * with provenance `language = event.vmFacet.id`, so a landing keeps its guest-VM
 * facet identity all the way into the confix provenance table.
 *
 * The value payload is a [PointcutLanding] carrying the zero-cost [PointcutMark]
 * byte (LcncSpineMarks.kt) alongside the coordinate — the pointcut phase tag is
 * part of the landed record, not a side channel.
 *
 * Downstream consumers read [landings] (a lazy [Series], `α`-projected columns,
 * no eager loop) or collect [flow]; there is no callback-list fanout here.
 */
class PointcutBlackboardAdapter(
    /** The blackboard this adapter lands pointcut observations on. */
    val blackboard: ConfixBlackboard,
    /**
     * Facet attributed to [TypedefProductionSystem] slab events — those come
     * from the host JVM ring, so [VmFacet.JVM] ("java") is the default.
     * Guest-VM [PointcutEvent]s carry their own facet and ignore this.
     */
    val slabFacet: VmFacet = VmFacet.JVM,
) : TypedefProductionSystem.SlabSubscriber {

    /**
     * One landed pointcut observation.
     *
     * @property key the blackboard key this landing occupies
     * @property coordinate DAG fabric coordinate for the observation
     * @property mark zero-cost pointcut phase tag; [PointcutMark.raw] is the byte
     * @property facet guest/host VM facet — the provenance `language`
     * @property propertyName property or opcode label at the pointcut site
     * @property value the observed value, when the pointcut carried one
     */
    data class PointcutLanding(
        val key: String,
        val coordinate: DagCoordinate,
        val mark: PointcutMark,
        val facet: VmFacet,
        val propertyName: String,
        val value: Any?,
    ) {
        /** The [PointcutMark] byte, unwrapped — the payload's phase ordinal. */
        val markRaw: Byte get() = mark.raw

        /** Provenance language landed with this record. */
        val language: String get() = facet.id

        override fun toString(): String =
            "$key#${mark.raw}@${coordinate.timestamp}:${facet.id}"
    }

    // Slab delivery runs on whichever thread tripped the ring flush, while direct
    // accept() calls may come from a guest-VM thread — the landing log is shared.
    private val log: MutableList<PointcutLanding> =
        java.util.Collections.synchronizedList(ArrayList())

    private val _flow = MutableSharedFlow<PointcutLanding>(
        replay = 0,
        extraBufferCapacity = FLOW_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Landed observations, newest last — a flow, not a callback list. */
    val flow: SharedFlow<PointcutLanding> get() = _flow.asSharedFlow()

    /** All landings so far as a lazy [Series] (kernel algebra, no eager copy). */
    val landings: Series<PointcutLanding> get() = log.size j { log[it] }

    /** Blackboard keys touched by this adapter, lazily projected. */
    val keys: Series<String> get() = landings α { it.key }

    /** Coordinates landed by this adapter, lazily projected. */
    val coordinates: Series<DagCoordinate> get() = landings α { it.coordinate }

    /** Number of observations landed. */
    val size: Int get() = log.size

    // ── TypedefProductionSystem ring → blackboard ────────────────────

    /**
     * Slab delivery from the pointcut ring. Only the first [count] entries of
     * [slab] are live; [epoch]/[nanoStart]/[nanoEnd] frame the slab window and
     * are used to bound landings whose own nano stamp is absent.
     */
    override fun onSlab(
        slab: Array<TypedefProductionSystem.TraceEvent>,
        count: Int,
        epoch: Long,
        nanoStart: Long,
        nanoEnd: Long,
    ) {
        val live = minOf(count, slab.size)
        if (live <= 0) return
        val threadId = Thread.currentThread().threadId()
        // Lazy projection over the live prefix — no (0 until size).map materialization.
        val prefix: Series<TypedefProductionSystem.TraceEvent> = live j { slab[it] }
        val landed: Series<PointcutLanding> = prefix α { evt -> land(evt, threadId, nanoStart) }
        // Series is lazy: force exactly once, in order, to perform the puts.
        for (i in 0 until landed.a) landed.b(i)
    }

    /** Land a single ring [TypedefProductionSystem.TraceEvent]. */
    fun accept(event: TypedefProductionSystem.TraceEvent): PointcutLanding =
        land(event, Thread.currentThread().threadId(), event.nano)

    private fun land(
        evt: TypedefProductionSystem.TraceEvent,
        threadId: Long,
        slabNanoStart: Long,
    ): PointcutLanding {
        val typedef = evt.typedefName().ifEmpty { UNKNOWN_TYPEDEF }
        val method = shortMethod(typedef, evt.methodName().ifEmpty { UNKNOWN_METHOD })
        val nano = if (evt.nano != 0L) evt.nano else slabNanoStart
        val coordinate = DagCoordinate(
            className = typedef,
            methodName = method,
            bytecodeOffset = evt.siteIdx,
            timestamp = nano / NANOS_PER_MILLI,
            threadId = threadId,
        )
        val mark = markOf(isSet = isSetLike(evt.opcode, method), isAfter = evt.phase != 0.toByte())
        return put(
            key = keyOf(typedef, method, evt.siteIdx),
            coordinate = coordinate,
            mark = mark,
            facet = slabFacet,
            propertyName = evt.opcodeName(),
            value = evt.callsiteHash,
        )
    }

    // ── Guest-VM PointcutEvent → blackboard ──────────────────────────

    /**
     * Land a direct guest-VM [PointcutEvent]. The event's `coordinate` string is
     * split on its last `.` into typedef/method; the site index is the intern-pool
     * ordinal of `propertyName`, which keeps distinct properties on one coordinate
     * at distinct blackboard keys without a second registry.
     */
    fun accept(event: PointcutEvent): PointcutLanding {
        val typedef = event.coordinate.substringBeforeLast('.', UNKNOWN_TYPEDEF)
            .ifEmpty { UNKNOWN_TYPEDEF }
        val method = event.coordinate.substringAfterLast('.').ifEmpty { UNKNOWN_METHOD }
        val siteIdx = TypedefProductionSystem.InternPool.intern(
            event.propertyName.ifEmpty { event.coordinate }
        )
        val coordinate = DagCoordinate(
            className = typedef,
            methodName = method,
            bytecodeOffset = siteIdx,
            timestamp = event.timestamp,
            threadId = Thread.currentThread().threadId(),
        )
        // A guest hook that carries a newValue is a write observation; the value is
        // already reified at delivery, so the phase is the AFTER side of that hook.
        val mark = markOf(isSet = event.newValue != null, isAfter = true)
        return put(
            key = keyOf(typedef, method, siteIdx),
            coordinate = coordinate,
            mark = mark,
            facet = event.vmFacet,
            propertyName = event.propertyName,
            value = event.newValue,
        )
    }

    /**
     * Land a whole [Series] of guest-VM events, lazily projected, returning the
     * landings produced by *this* call (not the whole [landings] log).
     */
    fun acceptAll(events: Series<PointcutEvent>): Series<PointcutLanding> {
        val projected = events α { accept(it) }
        val landed = ArrayList<PointcutLanding>(projected.a)
        for (i in 0 until projected.a) landed.add(projected.b(i))
        return landed.size j { landed[it] }
    }

    // ── Installation ─────────────────────────────────────────────────

    /**
     * Install this adapter as the [TypedefProductionSystem] slab subscriber,
     * returning the subscriber it displaced so a caller can restore it.
     */
    fun install(): TypedefProductionSystem.SlabSubscriber? {
        val prior = TypedefProductionSystem.subscriber
        TypedefProductionSystem.subscriber = this
        return prior
    }

    /** Restore [prior] as the slab subscriber if this adapter is still installed. */
    fun uninstall(prior: TypedefProductionSystem.SlabSubscriber? = null) {
        if (TypedefProductionSystem.subscriber === this) {
            TypedefProductionSystem.subscriber = prior
        }
    }

    // ── Internals ────────────────────────────────────────────────────

    private fun put(
        key: String,
        coordinate: DagCoordinate,
        mark: PointcutMark,
        facet: VmFacet,
        propertyName: String,
        value: Any?,
    ): PointcutLanding {
        val landing = PointcutLanding(key, coordinate, mark, facet, propertyName, value)
        blackboard.put(key, landing, language = facet.id)
        log.add(landing)
        _flow.tryEmit(landing)
        return landing
    }

    companion object {
        /** Key prefix for every pointcut landing. */
        const val KEY_PREFIX = "pointcut"

        private const val FLOW_BUFFER = 256
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val UNKNOWN_TYPEDEF = "?"
        private const val UNKNOWN_METHOD = "?"

        /** `pointcut/<typedef>/<method>/<siteIdx>` — the M1 key scheme. */
        fun keyOf(typedef: String, method: String, siteIdx: Int): String =
            "$KEY_PREFIX/$typedef/$method/$siteIdx"

        /** Strip a redundant `typedef.` prefix off a fully-qualified method name. */
        fun shortMethod(typedef: String, method: String): String =
            if (typedef.isNotEmpty() && method.startsWith("$typedef.")) {
                method.substring(typedef.length + 1).ifEmpty { method }
            } else method

        /**
         * Resolve the [PointcutMark] for a (set?, after?) pair through the
         * FieldSynapse template vocabulary, so the mark stays aligned 1:1 with
         * `FieldSynapse.TPL_*` rather than being re-ordinaled here.
         */
        fun markOf(isSet: Boolean, isAfter: Boolean): PointcutMark = PointcutMark.fromTemplate(
            when {
                isSet && isAfter -> FieldSynapse.TPL_AFTER_SET
                isSet -> FieldSynapse.TPL_BEFORE_SET
                isAfter -> FieldSynapse.TPL_AFTER_GET
                else -> FieldSynapse.TPL_BEFORE_GET
            }
        )

        /**
         * A ring opcode is write-shaped when it is a PROPERTY/PARAMETER site whose
         * method reads as a mutator; everything else on the ring is a read/observe.
         */
        fun isSetLike(opcode: Byte, method: String): Boolean =
            when (opcode.toInt() and 0xFF) {
                0x13, 0x14 -> method.startsWith("set") || method.startsWith("put")
                else -> false
            }
    }
}
