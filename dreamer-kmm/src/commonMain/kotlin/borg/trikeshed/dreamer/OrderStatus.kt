package borg.trikeshed.dreamer

/**
 * Lifecycle state for a simulated order.
 */
enum class OrderStatus {
    PENDING,
    FILLED,
    CANCELLED,
    REJECTED,
}
