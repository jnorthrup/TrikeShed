package borg.trikeshed.forge.blackboard

import borg.trikeshed.dag.ReteAgent
import borg.trikeshed.dag.bindOrCreateAgent
import borg.trikeshed.graph.CausalGraphNodeIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * Rete ring → `board` section.
 *
 * Every distinct [ReteAgent.Fire] becomes one tile in the strip below the
 * blackboard's `board` quadrant. The fire *is* the payload — it already carries
 * exactly the fields a renderer needs (rule, node, causal key, payload, agent).
 */
object ReteFireSurfaceProjection : ForgeSurfaceProjection<ReteAgent.Fire, ReteAgent.Fire> {

    override val anchorSectionId: String = "board"

    override val ttlMs: Long = FORGE_SURFACE_TTL_MS

    override val grid: ForgeSurfaceGrid = ForgeSurfaceGrid(
        columns = 4,
        cellWidth = 240.0,
        cellHeight = 120.0,
        gapX = 24.0,
        gapY = 24.0,
        elevation = 10.0,
    )

    /**
     * Content-derived and therefore stable: the same fire always names the same
     * tile, whatever its position in the window. Rule and node stay readable in
     * the id; the causal key and payload fold into the hash so two fires of the
     * same rule on the same node still get separate tiles.
     */
    override fun sectionIdOf(item: ReteAgent.Fire, index: Int): String = buildString {
        append("board-fire-")
        append(item.ruleName.forgeSectionToken())
        append('-')
        append(item.nodeId.forgeSectionToken())
        append('-')
        append(forgeSectionHash(item.causalKey, item.payload, item.agentId))
    }

    override fun payloadOf(item: ReteAgent.Fire, index: Int): ReteAgent.Fire = item
}

/**
 * Squash a free-form label into a section-id token: keeps letters, digits, `-`
 * and `_`, collapses every run of anything else -- the U+001F/U+001E separators
 * inside a causal key, slashes, whitespace -- into a single `-`, and trims the
 * result. Input with nothing keepable becomes `"x"`, so a section id is never
 * left empty.
 */
fun String.forgeSectionToken(): String {
    val out = StringBuilder(length)
    for (c in this) {
        val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_'
        if (ok) out.append(c)
        else if (out.isNotEmpty() && out.last() != '-') out.append('-')
    }
    return out.toString().trim('-').ifEmpty { "x" }
}

/**
 * FNV-1a over [parts] (separated by `0x1F`) rendered as 8 lowercase hex digits.
 *
 * Deliberately not `hashCode()`: this has to agree across platforms and across
 * runs, because it names a blackboard section.
 */
fun forgeSectionHash(vararg parts: String): String {
    val prime = 0x01000193u
    var h = 0x811C9DC5u

    fun mix(byte: Int) {
        h = h xor (byte and 0xFF).toUInt()
        h *= prime
    }

    parts.forEachIndexed { i, part ->
        if (i > 0) mix(0x1F)
        for (c in part) {
            mix(c.code)
            mix(c.code shr 8)
        }
    }

    val bits = h.toInt()
    return buildString(8) {
        for (shift in 28 downTo 0 step 4) append(FORGE_HEX[(bits ushr shift) and 0xF])
    }
}

private const val FORGE_HEX: String = "0123456789abcdef"

/**
 * Bounded, coroutine-safe collector for [ReteAgent.Fire]s on their way to the
 * `board` section.
 *
 * [onFire] is the non-suspending hook handed to
 * [CausalGraphNodeIndex.bindOrCreateAgent]. It `trySend`s onto a [Channel] of
 * [capacity] with [BufferOverflow.DROP_OLDEST], so it never blocks the agent's
 * rule loop and never grows without bound when nobody drains: the newest
 * [capacity] fires are what survive, at the queue and in the window alike.
 *
 * [drain] moves whatever is queued into the window and returns it oldest-first;
 * [awaitFires] is the suspending variant for callers that need a known number of
 * *further* fires to have landed.
 *
 * **Single-consumer.** [drain], [awaitFires], [project] and [clear] are each
 * individually safe, but the tap assumes one consuming coroutine. Two coroutines
 * pulling concurrently can interleave a suspended [awaitFires] receive with a
 * [drain], and observe the window briefly out of order.
 */
class ReteFireBoardTap(
    val capacity: Int = 32,
    val projection: ForgeSurfaceProjection<ReteAgent.Fire, ReteAgent.Fire> = ReteFireSurfaceProjection,
) {
    init {
        require(capacity > 0) { "tap capacity must be positive, got $capacity" }
    }

    private val sink: Channel<ReteAgent.Fire> =
        Channel(capacity = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mutex = Mutex()
    private val window = ArrayDeque<ReteAgent.Fire>()

    /** Monotonic count of fires ever absorbed into the window. Guarded by [mutex]. */
    private var absorbed: Long = 0L

    /** Hand this to `bindOrCreateAgent(onFire = tap::onFire)`. Never suspends. */
    fun onFire(fire: ReteAgent.Fire) {
        sink.trySend(fire)
    }

    /** Drain the queue into the window; returns the window oldest-first. */
    suspend fun drain(): List<ReteAgent.Fire> = mutex.withLock {
        pump()
        window.toList()
    }

    /**
     * Suspend until [count] *more* fires have been absorbed than when the call
     * started, then return the window oldest-first.
     *
     * Counting arrivals rather than window size is what makes a second call
     * meaningful: the window is a rolling view that is never emptied by
     * [drain], so `window.size >= count` would be satisfied forever after the
     * first batch. Wrap in `withTimeout*` to bound the wait.
     */
    suspend fun awaitFires(count: Int): List<ReteAgent.Fire> {
        require(count >= 0) { "count must be non-negative, got $count" }
        val target = mutex.withLock { absorbed } + count
        while (true) {
            mutex.withLock {
                pump()
                if (absorbed >= target) return window.toList()
            }
            // Received before re-locking, so it still precedes anything a later
            // pump() could pull off the same queue.
            val fire = sink.receive()
            mutex.withLock {
                push(fire)
                pump()
            }
        }
    }

    /** Drain, then project the window onto [base]. */
    suspend fun project(
        base: ForgeBlackboardView = ForgeBlackboardView.DEFAULT,
        now: Long = Clock.System.now().toEpochMilliseconds(),
    ): Triple<ForgeBlackboardView, ForgeSectionSurface<ReteAgent.Fire>, Map<String, ForgeSurfaceEnvelope<ReteAgent.Fire>>> =
        projection.project(drain(), base, now)

    /** Forget every queued and buffered fire (e.g. on board reset). */
    suspend fun clear(): Unit = mutex.withLock {
        while (sink.tryReceive().getOrNull() != null) { /* discard */ }
        window.clear()
    }

    /** Move everything currently queued into the window. Caller holds [mutex]. */
    private fun pump() {
        var next = sink.tryReceive().getOrNull()
        while (next != null) {
            push(next)
            next = sink.tryReceive().getOrNull()
        }
    }

    /** Append one fire, evicting the oldest past [capacity]. Caller holds [mutex]. */
    private fun push(fire: ReteAgent.Fire) {
        window.addLast(fire)
        while (window.size > capacity) window.removeFirst()
        absorbed++
    }
}

/**
 * Bind [tap] to this index so every rete fire lands on the `board` section.
 *
 * @throws IllegalArgumentException when the index already has a bound agent —
 *   [CausalGraphNodeIndex.bindOrCreateAgent] would keep that agent and silently
 *   leave [tap] empty forever. Call [CausalGraphNodeIndex.unbindAgent] first, or
 *   check [CausalGraphNodeIndex.hasBoundAgent] before tapping.
 */
fun CausalGraphNodeIndex.tapFiresToBoard(
    scope: CoroutineScope,
    tap: ReteFireBoardTap,
    agentId: String = "forge-board-fire-tap",
): ReteAgent.Agent {
    require(!hasBoundAgent()) {
        "index already has a bound agent; unbind it before tapping fires to the board"
    }
    return bindOrCreateAgent(scope = scope, agentId = agentId, onFire = tap::onFire)
}
