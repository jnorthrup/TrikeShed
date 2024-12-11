package borg.trikeshed.lib

expect class SelectionKey {
    val isValid: Boolean
    val readyOps: Int
    var interestOps: Int
    var attachment: Any?
    fun cancel()
    fun channel(): SelectableChannel
}
