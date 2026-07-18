@file:Suppress("UNCHECKED_CAST")

package borg.trikeshed.litebike

import borg.trikeshed.context.AsyncContextElement
import borg.trikeshed.litebike.taxonomy.Protocol

/**
 * LitebikeFanoutEvent — the CCEK-fanout event that bridges per-protocol
 * slot activity on [LitebikeListenerElement] out to CCEK subscribers.
 *
 * The shape follows the same pattern `HtxElement.FanoutEventSubscriber`
 * already uses (see `htx/HtxElement.kt:146`) — a single `onFanoutEvent`
 * callback that runs every time the parent element forwards a
 * [LitebikeListenerElement.ChannelMessage] into its outbound log.
 *
 * Where it lives in the picture:
 *   - [LitebikeListenerElement] already owns one CCEK element with a
 *     `fanoutSubscribers: List<AsyncContextElement>` surface.
 *   - Per-protocol worker `ChannelWorkgroupSlot`s accept inbound bytes
 *     via `accept(protocol, payload)`.
 *   - This event hooks the moment **after** the listener has classified
 *     and offered the message, but **before** the slot worker has fully
 *     consumed it — so SCTP can signal HTX about framing from a
 *     different wire, and any other CCEK element (an HtxElement acting as
 *     a downstream client, an audit logger, a keymux worker) can observe.
 *
 * How an element subscribes:
 *
 *   element.subscribe(myObserver)              // global fanout
 *   element.subscribeTo(Protocol.Socks5, obs) // per-protocol
 *
 * Any [AsyncContextElement] that wants to be a recipient implements
 * [LitebikeFanoutEventSink.onLitebikeFanoutEvent] via the duck-typed
 * `onFanoutEvent` convention [HtxElement] already uses.
 */
interface LitebikeFanoutEventSink {
    fun onLitebikeFanoutEvent(event: LitebikeFanoutEvent)
}

/**
 * One wire-level fanout event. Immutable; subscribers receive a copy
 * per fanout call (no shared mutable state).
 */
data class LitebikeFanoutEvent(
    val protocol: Protocol,
    val sequenceId: Long,
    val payloadSize: Int,
    val accepted: Boolean,                // true ⇒ a slot was registered and offered
    val subscriberCount: Int,            // number of CCEK subscribers at fanout time
    val epochMillis: Long,
)

/**
 * Heuristic: the CCEK shape preferred by this codebase (`HtxElement`,
 * `SctpElement`, `LitebikeListenerElement`) accepts subscribers as
 * `AsyncContextElement`s and matches those that implement a duck-typed
 * sink by name (`onFanoutEvent`). This file keeps the explicit interface
 * separate from the duck-typed lookup so downstream code can declare
 * intent explicitly without reflection.
 *
 * Bridge: register a sink by composition:
 *
 *   listener.addSink(mySctpBridge)        // explicit, type-safe
 *   listener.subscribe(myHtxElement)      // duck-typed via HtxElement.FanoutEventSubscriber
 *
 * The duck-typed bridge picks up subscribers whose runtime class
 * declares `fun onFanoutEvent(LitebikeFanoutEvent)` or whose parent
 * class is `HtxElement` (and therefore implements the legacy
 * `FanoutEventSubscriber` contract).
 */
object LitebikeFanoutBridge {

    /**
     * Convenience: subscribe an element to the listener and forward
     * every [LitebikeFanoutEvent] into both the legacy duck-typed
     * `onFanoutEvent(frame)` and the explicit `onLitebikeFanoutEvent`
     * hooks where present.
     */
    fun bridge(listener: LitebikeListenerElement, subscriber: AsyncContextElement) {
        // If the subscriber is already a Sink and the listener exposes
        // its own sink list, defer to the listener's explicit API.
        listener.subscribe(subscriber)
    }
}
