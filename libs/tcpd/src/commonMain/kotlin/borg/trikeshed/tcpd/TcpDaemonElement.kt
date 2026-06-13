package borg.trikeshed.tcpd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.net.InetSocketAddress

/**
 * TCP daemon element - standalone implementation.
 * 
 * Lifecycle: CREATED -> OPEN -> ACTIVE -> DRAINING -> CLOSED
 * Each connection spawns a child branch via CoroutineScope.
 */
class TcpDaemonElement(
    val config: TcpDaemonConfig,
    val scope: CoroutineScope,
) {
    private var serverSocket: ServerSocketChannel? = null
    private val sessions = mutableMapOf<String, TcpSession>()

    /** Launch listener branch. */
    fun launchListenerBranch() = scope.launch {
        runListener()
    }

    private suspend fun runListener() {
        serverSocket = ServerSocketChannel.open().apply {
            configureBlocking(false)
            bind(InetSocketAddress(config.host, config.port))
            println("[TcpDaemon] Listening on ${config.host}:${config.port}")
        }

        while (scope.isActive) {
            try {
                val socket = serverSocket?.accept()
                if (socket != null) {
                    socket.configureBlocking(false)
                    spawnSession(socket)
                } else {
                    delay(10)
                }
            } catch (e: java.nio.channels.ClosedChannelException) {
                break
            } catch (e: Exception) {
                println("[TcpDaemon] Accept error: ${e.message}")
                delay(100)
            }
        }
        
        println("[TcpDaemon] Listener branch exiting")
    }

    private fun spawnSession(socket: SocketChannel) {
        val sessionId = "tcp-${System.currentTimeMillis()}-${socket.hashCode()}"
        val session = TcpSession(sessionId, socket, config, this)
        sessions[sessionId] = session
        
        scope.launch {
            session.run()
        }
    }

    suspend fun close() {
        println("[TcpDaemon] Closing server socket")
        serverSocket?.close()
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    fun activeSessions(): Int = sessions.size
}

data class TcpDaemonConfig(
    val host: String = "0.0.0.0",
    val port: Int = 8080,
    val useTls: Boolean = false,
    val channelCapacity: Int = 1024,
    val idleTimeoutMs: Long = 300_000,
    val maxFrameSize: Int = 16 * 1024 * 1024,
)