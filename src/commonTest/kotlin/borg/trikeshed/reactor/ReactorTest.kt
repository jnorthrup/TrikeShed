package borg.trikeshed.reactor

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.j
import kotlin.test.*

class ReactorTest {
    @Test
    fun testBasicServerClientCommunication() = runTest {
        val reactor = createTestReactor()
        val platform = PlatformIO.create()
        val serverChannel = platform.createServerChannel().apply {
            configureBlocking(false)
            bind(0) // Use port 0 to get random available port
        }
        
        // Get the actual port that was assigned
        val port = (serverChannel as? JvmServerChannelImpl)?.underlying?.socket()?.localPort
            ?: throw IllegalStateException("Could not get server port")
            
        val clientChannel = platform.createClientChannel().apply {
            configureBlocking(false)
            connect("localhost", port)
        }
        
        reactor.registerChannel(serverChannel, OP_ACCEPT) { key ->
            val server = key.channel() as ServerChannel
            val client = server.accept()
            client?.configureBlocking(false)
            client?.let { channel ->
                reactor.registerChannel(channel, OP_READ) { clientKey ->
                    val buffer = ByteBufferFactory.allocate(1024)
                    val readChannel = clientKey.channel() as ClientChannel
                    val bytesRead = readChannel.read(buffer)
                    if (bytesRead > 0) {
                        buffer.flip()
                        readChannel.write(buffer)
                    }
                    OP_READ j this
                }
            }
            OP_ACCEPT j this
        }

        // Test communication
        val testData = "Hello Reactor!".toByteArray()
        val writeBuffer = ByteBufferFactory.wrap(testData)
        (clientChannel as ClientChannel).write(writeBuffer)
        
        delay(100) // Give time for processing
        
        val readBuffer = ByteBufferFactory.allocate(1024)
        val bytesRead = (clientChannel as ClientChannel).read(readBuffer)
        readBuffer.flip()
        val response = ByteArray(bytesRead)
        readBuffer.get(response)
        
        assertEquals(String(testData), String(response))

        reactor.shutdown()
    }

    @Test
    fun testReactorShutdown() = runTest {
        val reactor = createTestReactor()
        assertTrue(reactor.isActive)
        reactor.shutdown()
        assertFalse(reactor.isActive)
    }
}

expect fun runTest(block: suspend () -> Unit)
expect fun createTestReactor(): Reactor
expect fun delay(ms: Long)
