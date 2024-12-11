package borg.trikeshed.lib

interface SelectableChannel : AsyncChannel {
    suspend fun configureBlocking(block: Boolean): SelectableChannel
    suspend fun register(selector: CommonSelector, ops: Int, attachment: Any?): SelectionKey
}
