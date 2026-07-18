@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.litebike

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.context.AsyncContextKey
import borg.trikeshed.context.ElementState
import borg.trikeshed.litebike.taxonomy.Protocol
import borg.trikeshed.litebike.taxonomy.ProtocolMark
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LitebikeListenerElement — the CCEK multiprotocol listener, ported from
 * `../litebike/src/universal_listener.rs` + `reactor.rs`.
 *
 * The Rust shape, on wire-stable identifiers:
 *   pub fn register_channel(&self, protocol_id: u8, sender: Sender<ChannelMessage>)
 *   pub fn submit_read(&self, fd: RawFd, buf: &mut [u8], protocol_id: u8)
 *   pub fn submit_write(...)
 *   pub fn submit_batch(&self) -> io::Result<u64>
 *
 * The Kotlin shape, ported with the existing TrikeShed CCEK plumbing:
 *   - Lifecycle through `AsyncContextElement` like `NuidFanoutElement` and
 *     `HtxElement` (CREATED → OPEN → ACTIVE → DRAINING → CLOSED).
 *   - Channel slots keyed by the byte-stable `Protocol.id` (Http=1,
 *     Socks5=2, ..., WebSocket=7).
 *   - `accept(bytes: ByteArray)` is the single inbound boundary.
 *   - Numeric IDs match litebike's `protocol_to_spec_id` exactly so the
 *     same wire table is honored on both sides of the FFI.
 *
 * This is the listener the JVM bind adapter hands bytes to. It does not
 * own a port; bind is platform-native (`JvmLitebikeBindAdapter` reads
 * from an `AsynchronousServerSocketChannel`). Zero NIO lives here.
 */
class LitebikeListenerElement(
    parentJob: Job? = null,
    val maxBatch: Int = 64,
) : AsyncContextElement(ElementState.CREATED, parentJob) {

    companion object Key : AsyncContextKey<LitebikeListenerElement>()
    override val key: AsyncContextKey<LitebikeListenerElement> = Key

    /** Inbound wire-level message; the unit of fanout. */
    data class ChannelMessage(
        val protocolId: UByte,
        val sequenceId: Long,
        val payload: ByteArray,
    )

    /** Per-protocol inbox. Each registered worker drains via [ChannelWorkgroupSlot.consume]. */
    class ChannelWorkgroupSlot(val protocol: Protocol) {
        private val inbox: Channel<ChannelMessage> = Channel(Channel.BUFFERED)

        /** Consume one message at a time; suspending when empty. */
        suspend fun consume(): ChannelMessage = inbox.receive()

        /** Try one message, non-blocking. */
        fun tryConsume(): ChannelMessage? = inbox.tryReceive().getOrNull()

        /** Suspend until a message arrives or the slot closes. */
        suspend fun offer(msg: ChannelMessage) { inbox.send(msg) }

        /** Close the slot. Outstanding sends will fail; consumers drain what's left. */
        fun close() { inbox.close() }
    }

    // ── registry ──────────────────────────────────────────────────

    private val registryMutex: Mutex = Mutex()

    /** Map: protocol id (UByte) → slot. One slot per protocol; deterministic IDs. */
    private val registry: MutableMap<UByte, ChannelWorkgroupSlot> = mutableMapOf()
    private val mutableSubscribers: MutableList<AsyncContextElement> = mutableListOf()

    override val fanoutSubscribers: List<AsyncContextElement>
        get() = mutableSubscribers.toList()

    fun subscribe(observer: AsyncContextElement) { mutableSubscribers.add(observer) }
    fun unsubscribe(observer: AsyncContextElement) { mutableSubscribers.remove(observer) }

    /**
     * Register a worker bound to a single protocol. Idempotent on
     * `protocol`. Returns the slot; callers consume via `slot.consume()`
     * (matching litebike's `register_channel(protocol_id, sender)` +
     * `consume_each`).
     */
    suspend fun register(protocol: Protocol): ChannelWorkgroupSlot = registryMutex.withLock {
        check(state == ElementState.OPEN || state == ElementState.ACTIVE) {
            "LitebikeListenerElement must be OPEN before register() (was $state)"
        }
        val existing = registry[protocol.id]
        if (existing != null) return@withLock existing
        val slot = ChannelWorkgroupSlot(protocol)
        registry[protocol.id] = slot
        slot
    }

    /** Drop the slot for [protocol.id]. */
    suspend fun unregister(protocol: Protocol) {
        val slot = registryMutex.withLock { registry.remove(protocol.id) }
        slot?.close()
    }

    // ── lifecycle overrides ───────────────────────────────────────

    override suspend fun open() {
        if (state == ElementState.CREATED) state = ElementState.OPEN
    }

    /** Promote OPEN → ACTIVE. */
    suspend fun activate() {
        if (state == ElementState.OPEN) state = ElementState.ACTIVE
    }

    override suspend fun close() {
        if (state.isAtLeast(ElementState.OPEN) && state.isLessThan(ElementState.CLOSED)) {
            if (state < ElementState.DRAINING) state = ElementState.DRAINING
            supervisor.cancel()
            registryMutex.withLock {
                registry.values.forEach { it.close() }
                registry.clear()
                mutableSubscribers.clear()
            }
            state = ElementState.CLOSED
        }
    }

    // ── accept / dispatch ─────────────────────────────────────────

    private val sequenceCounter: kotlinx.coroutines.sync.Mutex = Mutex()
    private var sequence: Long = 0L

    private suspend fun nextSequenceId(): Long = sequenceCounter.withLock {
        sequence = if (sequence == Long.MAX_VALUE) 0L else sequence + 1L
        sequence
    }

    /**
     * R05 — pre-allocate a sequence id without emitting. The JVM bind
     * adapter calls this before calling [acceptWithSequence] so the
     * originating connection can be stamped with the same id the
     * worker will see on its inbound [ChannelMessage].
     */
    internal suspend fun nextSequenceIdForEmit(): Long = nextSequenceId()

    /**
     * R05 — accept [payload] with a caller-supplied [sequenceId]. Same
     * semantics as [accept] but skips sequence-id allocation so the
     * caller can correlate the inbound message with a side table of
     * originating sockets.
     */
    internal suspend fun acceptWithSequence(
        protocol: Protocol,
        payload: ByteArray,
        sequenceId: Long,
    ): Boolean = acceptWithSequence(protocol.id, payload, sequenceId)

    /**
     * UByte overload of [acceptWithSequence].
     */
    internal suspend fun acceptWithSequence(
        protocolId: UByte,
        payload: ByteArray,
        sequenceId: Long,
    ): Boolean {
        check(state == ElementState.OPEN || state == ElementState.ACTIVE) {
            "LitebikeListenerElement must be OPEN/ACTIVE before accept() (was $state)"
        }
        val slot = registry[protocolId] ?: return false
        val proto = Protocol.fromId(protocolId)
        val msg = ChannelMessage(protocolId, sequenceId, payload)
        slot.offer(msg)
        val event = LitebikeFanoutEvent(
            protocol = proto ?: return true,
            sequenceId = sequenceId,
            payloadSize = payload.size,
            accepted = true,
            subscriberCount = fanoutSubscribers.size,
            epochMillis = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        )
        fireFanoutEvent(event)
        return true
    }

    /**
     * Classify [bytes] and offer the resulting [ChannelMessage] to the
     * matching protocol's slot. Litebike classifies via RBCursive;
     * here we trust the caller's protocol tag (the wire decoder lives
     * on the JVM bind adapter). Returns true if a slot was found.
     *
     * After the slot offer, fires a [LitebikeFanoutEvent] to every
     * CCEK subscriber — this is the bridge SCTP / HTX elements use to
     * observe inbound protocol traffic.
     */
    suspend fun accept(protocolId: UByte, payload: ByteArray): Boolean {
        check(state == ElementState.OPEN || state == ElementState.ACTIVE) {
            "LitebikeListenerElement must be OPEN/ACTIVE before accept() (was $state)"
        }
        val slot = registry[protocolId] ?: return false
        val proto = Protocol.fromId(protocolId)
        val sequenceId = nextSequenceId()
        val msg = ChannelMessage(protocolId, sequenceId, payload)
        slot.offer(msg)
        // Bridge: notify every CCEK subscriber of the fanout event.
        val event = LitebikeFanoutEvent(
            protocol = proto ?: return true,
            sequenceId = sequenceId,
            payloadSize = payload.size,
            accepted = true,
            subscriberCount = fanoutSubscribers.size,
            epochMillis = kotlinx.datetime.Clock.System.now().toEpochMilliseconds(),
        )
        fireFanoutEvent(event)
        return true
    }

    /**
     * Convenience overload: caller did the protocol detection and has a
     * `Protocol` enum value. Same behavior.
     */
    suspend fun accept(protocol: Protocol, payload: ByteArray): Boolean =
        accept(protocol.id, payload)

    /**
     * Notify every CCEK subscriber of [event]. Subscribers may implement
     * the explicit [LitebikeFanoutEventSink] interface. No reflection —
     * the duck-typed `onFanoutEvent(frame)` path that `HtxElement` uses
     * is reached by wrapping `HtxElement` in a small adapter if/when
     * someone bridges the two. Keeping this KMP-safe keeps the listener
     * compilable on JS and wasm, not just JVM.
     */
    fun fireFanoutEvent(event: LitebikeFanoutEvent) {
        for (subscriber in fanoutSubscribers.toList()) {
            if (subscriber is LitebikeFanoutEventSink) {
                runCatching { subscriber.onLitebikeFanoutEvent(event) }
            }
        }
    }

    /**
     * Snapshot of the registry for diagnostics and the `/api/cap`
     * endpoint. Returns protocol IDs and their RFC markers.
     */
    fun snapshot(): List<ProtocolMark> = registry.keys
        .mapNotNull { Protocol.fromId(it) }
        .map { ProtocolMark.forProtocol(it) }

    /** Structured-concurrency fanout: launch exactly one consumer for each
     *  registered slot. Mirrors litebike's `consume_each` per channel and
     *  stops a slot consumer when [block] returns false or the slot closes. */
    suspend fun fanoutChannels(block: suspend (Protocol, ChannelMessage) -> Boolean) {
        check(state == ElementState.OPEN || state == ElementState.ACTIVE) {
            "LitebikeListenerElement must be OPEN/ACTIVE before fanoutChannels() (was $state)"
        }
        val slots = registryMutex.withLock { registry.values.toList() }
        kotlinx.coroutines.coroutineScope {
            slots.forEach { slot ->
                launch {
                    try {
                        while (true) {
                            val msg = slot.consume()
                            if (!block(slot.protocol, msg)) break
                        }
                    } catch (_: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                        // slot closed — exit normally
                    }
                }
            }
        }
    }
}
