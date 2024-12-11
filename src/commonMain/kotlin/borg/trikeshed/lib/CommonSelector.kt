package borg.trikeshed.lib

expect class CommonSelector {
    suspend fun select(): Int
    suspend fun wakeup()
    suspend fun selectedKeys(): Set<SelectionKey>
    suspend fun close()
}
