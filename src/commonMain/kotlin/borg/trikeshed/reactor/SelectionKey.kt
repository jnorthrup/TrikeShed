package borg.trikeshed.reactor

abstract class SelectionKey {
    abstract val isValid: Boolean
    abstract val readyOps: Int
    abstract var interestOps: Int
    abstract var attachment: Any?
    abstract fun cancel()
    abstract fun channel(): SelectableChannel
}
