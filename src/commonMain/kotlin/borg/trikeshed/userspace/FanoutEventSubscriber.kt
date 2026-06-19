package borg.trikeshed.userspace

/**
 * Generic CCEK fanout subscriber for typed reactor events.
 *
 * Elements that want the root fanout stream implement this and can be
 * installed downstream from transport/protocol elements without binding to
 * a protocol-specific subscriber interface.
 */
interface FanoutEventSubscriber {
    suspend fun onFanoutEvent(event: FanoutEvent)
}
