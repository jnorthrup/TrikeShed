package borg.trikeshed.reactor.http

import borg.trikeshed.lib.j
import borg.trikeshed.reactor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class HttpMotdReaction(
    private val reactor: Reactor,
) : UnaryAsyncReaction {
    override suspend fun invoke(reactorKey: SelectionKey): AsyncReaction? {
        val serverChannel = reactorKey.channel() as ServerChannel
        val clientSocket = serverChannel.accept() ?: return OP_ACCEPT j this

        clientSocket.configureBlocking(false)

        return OP_READ j object : UnaryAsyncReaction {
            override suspend fun invoke(reactorKey: SelectionKey): AsyncReaction? {
                val clientChannel = reactorKey.channel() as ClientChannel
                val buffer = ByteBuffer.allocateDirect(2048)
                val bytesRead = clientChannel.read(buffer)

                if (bytesRead == -1) {
                    clientChannel.close()
                    return null
                }

                val httpStateMachine = HttpStateMachine(clientChannel, buffer)
                return httpStateMachine.parseRequest()
            }
        }
    }
}
