package borg.trikeshed.lib

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlinx.coroutines.channels.Channel as KChannel

const val OP_READ: Int = SelectionKey.OP_READ
const val OP_WRITE: Int = SelectionKey.OP_WRITE
const val OP_ACCEPT: Int = SelectionKey.OP_ACCEPT
const val OP_CONNECT: Int = SelectionKey.OP_CONNECT
const val OP_READ_WRITE: Int = SelectionKey.OP_READ or SelectionKey.OP_WRITE

typealias Interest = Int
typealias AsyncReaction = UnaryAsyncReaction

interface UnaryAsyncReaction : (SelectionKey) -> (Join<Interest, UnaryAsyncReaction>?);

data class Join<out A, out B>(val interest: A, val b: B)
infix fun Interest.j(reaction: AsyncReaction) = Join(this, reaction)

class ReactorDSL(private val selector: Selector) {
    inner class ProtocolBuilder {
        infix fun on(interest: Interest) = InterestBuilder(interest)
        infix fun read(size: Int) = ReadBuilder(size)
        infix fun write(message: String) = WriteBuilder(message)
    }

    inner class InterestBuilder(private val interest: Interest) {
        infix fun react(block: (SelectionKey) -> Join<Interest, AsyncReaction>?) =
            interest j block
    }

    inner class ReadBuilder(private val size: Int) {
        infix fun expect(expected: String) = 
            OP_READ j { key ->
                val buffer = ByteBuffer.allocate(size)
                val bytesRead = (key.channel() as SocketChannel).read(buffer)
                buffer.flip()
                val received = String(buffer.array(), 0, bytesRead)
                if (received == expected) {
                    OP_WRITE j { null }
                } else null
            }
    }

    inner class WriteBuilder(private val message: String) {
        infix fun then(next: Join<Interest, AsyncReaction>) =
            OP_WRITE j { key ->
                (key.channel() as SocketChannel).write(ByteBuffer.wrap(message.toByteArray()))
                next
            }
    }

    infix fun Join<Interest, AsyncReaction>.andThen(
        other: Join<Interest, AsyncReaction>
    ) = interest j { key ->
        b(key)?.let { result ->
            result.interest j other.b
        }
    }

    fun ping() = protocol {
        read(64) expect "PING" andThen
        write("PONG!") then
        (read(64) expect "MORE")
    }

    fun protocol(block: ProtocolBuilder.() -> Join<Interest, AsyncReaction>) =
        ProtocolBuilder().block()
}

class Reactor(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    val serverChannel: ServerSocketChannel = ServerSocketChannel.open(),
    val selector: Selector = serverChannel.provider().openSelector(),
    val queue: Channel<UnaryAsyncReaction> = KChannel<UnaryAsyncReaction>(Channel.UNLIMITED),
    val scope: CoroutineScope = CoroutineScope(dispatcher + SupervisorJob()),
) {
    private suspend fun enqueue(reaction: UnaryAsyncReaction) {
        withContext(scope.coroutineContext) {
            queue.send(reaction)
        }
    }

    suspend fun start() = coroutineScope {
        serverChannel.bind(InetSocketAddress(3080))
        serverChannel.configureBlocking(false)
        serverChannel.register(selector, OP_ACCEPT)

        launch(scope.coroutineContext) {
            while (true) {
                selector.select()
                val selectedKeys = selector.selectedKeys().iterator()
                while (selectedKeys.hasNext()) {
                    val key = selectedKeys.next()
                    selectedKeys.remove()
                    (key.attachment() as? UnaryAsyncReaction)?.invoke(key)?.also {
                        val (interest, reaction) = it
                        enqueue(reaction)
                    }
                }
            }
        }

        launch(scope.coroutineContext) {
            for (reaction in queue) {
                reaction.invoke(selector.selectedKeys().first())
            }
        }
    }
}

fun Selector.reactor(block: ReactorDSL.() -> Join<Interest, AsyncReaction>): AsyncReaction =
    ReactorDSL(this).block().b

fun handleAccept(key: SelectionKey) = (key.channel() as ServerSocketChannel).accept().run {
    configureBlocking(false)
    register(
        key.selector(),
        OP_READ,
        key.selector().reactor {
            protocol {
                ping() andThen
                write("Hello!") then
                (read(64) expect "YES")
            }
        }
    )
    null
}
