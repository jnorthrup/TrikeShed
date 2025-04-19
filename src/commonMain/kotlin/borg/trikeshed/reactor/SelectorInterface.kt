package borg.trikeshed.reactor

interface SelectorInterface {
    suspend fun select(): Int
    suspend fun wakeup()
    suspend fun register(channel: SelectableChannel, ops: Int, attachment: Any?): SelectionKey
    suspend fun selectedKeys(): Set<SelectionKey>
    suspend fun close()
    
    companion object {
        suspend fun create(): SelectorInterface
    }
}
