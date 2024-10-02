package borg.trikeshed.http


import kotlinx.coroutines.runBlocking

typealias Socket = Int
typealias Address = String

fun main() {
    val server = SimpleHttpServer(8080)
    server.start()
}

class SimpleHttpServer(private val port: Int) : Server {

    private var serverSocket: Socket? = null

    override fun start() {
        serverSocket = createSocket()
        bindSocket(serverSocket!!, port)
        listen(serverSocket!!)
        acceptConnections(serverSocket!!)
    }

    override fun stop() {
        serverSocket?.let { closeSocket(it) }
    }

    private fun createSocket(): Socket {
        // Implementation of socket creation
        return 0 // Placeholder for socket descriptor
    }

    private fun bindSocket(socket: Socket, port: Int) {
        // Implementation of socket binding to a port
    }

    private fun listen(socket: Socket) {
        // Implementation of socket listening
    }

    private fun acceptConnections(socket: Socket) = runBlocking {
        while (true) {
            val clientSocket = accept(socket)
            handleRequest(clientSocket)
        }
    }

    private suspend fun handleRequest(socket: Socket) {
        val buffer = ByteArray(1024)
        // Read request from the socket
        val request = read(socket, buffer)
        // Handle request (not used in this example)
        // Send response
        val response = "HTTP/1.1 200 OK\nContent-Type: text/plain\nContent-Length: 13\n\nHello, World!"
        write(socket, response.toByteArray())
        closeSocket(socket)
    }

    private fun accept(socket: Socket): Socket {
        // Implementation of accepting a connection
        return 0 // Placeholder for client socket descriptor
    }

    private fun read(socket: Socket, buffer: ByteArray): String {
        // Implementation of reading from a socket
        return ""
    }

    private fun write(socket: Socket, data: ByteArray) {
        // Implementation of writing to a socket
    }

    private fun closeSocket(socket: Socket) {
        // Implementation of closing a socket
    }
}