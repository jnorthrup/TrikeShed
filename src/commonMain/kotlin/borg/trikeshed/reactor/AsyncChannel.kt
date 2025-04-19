package borg.trikeshed.reactor

interface AsyncChannel {
    suspend fun close()
    val isOpen: Boolean
}