package borg.trikeshed.reactor

expect class TestReactorPlatform() {
    fun createServerSocket(): SelectableChannel
    fun createClientSocket(port: Int): SelectableChannel
    fun getLocalPort(serverChannel: SelectableChannel): Int
    fun writeToChannel(channel: SelectableChannel, data: ByteArray)
    fun readFromChannel(channel: SelectableChannel): ByteArray
}
