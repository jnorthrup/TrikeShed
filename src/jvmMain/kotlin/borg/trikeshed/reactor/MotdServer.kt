package borg.trikeshed.reactor

import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

fun main() = runBlocking {
    val platform = PlatformIO.create()
    val reactor = Reactor()
    val serverChannel = platform.createServerChannel().apply {
        configureBlocking(false)
        bind(8080)
    }
    
    reactor.registerChannel(serverChannel, OP_ACCEPT, HttpMotdReaction)
    reactor.start()
    
    // Keep the coroutine alive
    kotlinx.coroutines.delay(Long.MAX_VALUE)
}
