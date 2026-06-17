package borg.trikeshed.userspace

/**
 * Base interface for fanout events in the userspace reactor.
 * All events emitted through the fanout dispatcher implement this.
 */
interface FanoutEvent {
    val eventType: Int
}

/**
 * Marker for events that should be broadcast to all subscribers.
 */
sealed interface BroadcastEvent : FanoutEvent {
    override val eventType: Int get() = 1
}

/**
 * Marker for point-to-point events.
 */
sealed interface DirectEvent : FanoutEvent {
    override val eventType: Int get() = 2
}