package borg.trikeshed.reactor

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.nio.ByteBuffer
import kotlin.text.Charsets

class ReactorTest {
    
    @Test
    fun `test client and server interaction`() = runBlocking {
        val reactor = Reactor()
        val serverPort = 8080
        
        // Configure server
        reactor.serverChannel?.bind(InetSocketAddress(serverPort))
        
        // Add MOTD reaction for server
        val motdReaction = HttpMotdReaction(reactor)
        reactor.registerChannel(reactor.serverChannel!!, UnaryAsyncReaction.Companion.OP_ACCEPT, motdReaction)
        
        reactor.start()
        
        // Create client connection
        val clientChannel = SocketChannel.open()
        clientChannel.connect(InetSocketAddress("localhost", serverPort))
        
        val responseReceived = kotlinx.coroutines.CompletableDeferred<String>()
        
        // Register client with reactor
        reactor.registerChannel(
            clientChannel, 
            UnaryAsyncReaction.Companion.OP_WRITE,
            object : UnaryAsyncReaction {
                override fun invoke(key: SelectionKey): Join<Int, UnaryAsyncReaction>? {
                    return when (key.readyOps()) {
                        UnaryAsyncReaction.Companion.OP_WRITE -> {
                            val request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n"
                            val buffer = ByteBuffer.wrap(request.toByteArray())
                            (key.channel() as SocketChannel).write(buffer)
                            Join(UnaryAsyncReaction.Companion.OP_READ, this)
                        }
                        UnaryAsyncReaction.Companion.OP_READ -> {
                            val buffer = ByteBuffer.allocate(4096)
                            val bytesRead = (key.channel() as SocketChannel).read(buffer)
                            if (bytesRead > 0) {
                                buffer.flip()
                                val response = String(buffer.array(), 0, bytesRead, Charsets.UTF_8)
                                runBlocking { responseReceived.complete(response) }
                            }
                            null // Done with this channel
                        }
                        else -> null
                    }
                }
            }
        )
        
        // Wait for response with timeout
        val response = withTimeout(5000) {
            responseReceived.await()
        }
        
        assertTrue(response.contains("HTTP/1.1 200"))
        assertTrue(response.contains("Welcome to Trikeshed Server"))
        
        // Cleanup
        clientChannel.close()
        reactor.shutdown()
    }

    @Test 
    fun `test hydra client - multiple concurrent connections`() = runBlocking {
        val reactor = Reactor()
        val numConnections = 10
        val responses = mutableListOf<kotlinx.coroutines.CompletableDeferred<String>>()
        
        // Create multiple client connections
        repeat(numConnections) { clientId ->
            val clientChannel = SocketChannel.open()
            clientChannel.connect(InetSocketAddress("example.com", 80))
            val responseDeferred = kotlinx.coroutines.CompletableDeferred<String>()
            responses.add(responseDeferred)
            
            reactor.registerChannel(
                clientChannel,
                UnaryAsyncReaction.Companion.OP_WRITE,
                object : UnaryAsyncReaction {
                    override fun invoke(key: SelectionKey): Join<Int, UnaryAsyncReaction>? {
                        return when (key.readyOps()) {
                            UnaryAsyncReaction.Companion.OP_WRITE -> {
                                val request = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"
                                val buffer = ByteBuffer.wrap(request.toByteArray())
                                (key.channel() as SocketChannel).write(buffer)
                                Join(UnaryAsyncReaction.Companion.OP_READ, this)
                            }
                            UnaryAsyncReaction.Companion.OP_READ -> {
                                val buffer = ByteBuffer.allocate(4096)
                                val bytesRead = (key.channel() as SocketChannel).read(buffer)
                                if (bytesRead > 0) {
                                    buffer.flip()
                                    val response = String(buffer.array(), 0, bytesRead, Charsets.UTF_8)
                                    runBlocking { responseDeferred.complete(response) }
                                }
                                null // Done with this channel
                            }
                            else -> null
                        }
                    }
                }
            )
        }
        
        // Wait for all responses with timeout
        val allResponses = withTimeout(10000) {
            responses.map { it.await() }
        }
        
        // Verify all connections completed successfully
        assertEquals(numConnections, allResponses.size)
        allResponses.forEach { response ->
            assertTrue(response.contains("HTTP/1.1"))
        }
        
        reactor.shutdown()
    }
}
