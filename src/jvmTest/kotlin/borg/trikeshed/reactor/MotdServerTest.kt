package borg.trikeshed.reactor

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import kotlin.test.assertTrue

class MotdServerTest {
    private lateinit var reactor: Reactor
    private val port = 8080
    private val client = HttpClient(CIO)

    @Before
    fun setup() {
        reactor = Reactor()
        reactor.serverChannel?.bind(InetSocketAddress(port))
        
        runBlocking {
            reactor.registerChannel(
                reactor.serverChannel!!,
                UnaryAsyncReaction.Companion.OP_ACCEPT,
                HttpMotdReaction(reactor)
            )
        }
        
        reactor.start()
        // Give the server a moment to start
        Thread.sleep(1000)
    }

    @After
    fun teardown() {
        reactor.shutdown()
        client.close()
    }

    @Test
    fun testMotdServer() = runBlocking {
        val response = client.get("http://localhost:$port")
        val text = response.bodyAsText()
        
        println("Received response: $text")
        assertTrue(text.contains("Welcome to Trikeshed Server"), "Response should contain welcome message")
    }
}
