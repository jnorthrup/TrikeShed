package borg.trikeshed.reactor

expect interface SelectableChannel {
    fun configureBlocking(block: Boolean): SelectableChannel
    fun register(selector: SelectorInterface, ops: Int, attachment: Any?): SelectionKey
    fun close()
}

expect interface SelectorInterface {
    suspend fun select(): Int
    suspend fun wakeup()
    suspend fun register(channel: SelectableChannel, ops: Int, attachment: Any?): SelectionKey
    suspend fun selectedKeys(): Set<SelectionKey>
    suspend fun close()
}

expect abstract class SelectionKey {
    abstract val isValid: Boolean
    abstract val readyOps: Int
    abstract var interestOps: Int
    abstract var attachment: Any?
    abstract fun cancel()
    abstract fun channel(): SelectableChannel
}

expect fun createSelectorInterface(): SelectorInterface
