package borg.trikeshed.lib

interface AsyncChannel {
    suspend fun close()
    val isOpen: Boolean
}
