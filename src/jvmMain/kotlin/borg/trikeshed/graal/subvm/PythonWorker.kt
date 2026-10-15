package borg.trikeshed.graal.subvm

import borg.trikeshed.vm.Teleported
import borg.trikeshed.vm.field
import borg.trikeshed.vm.str
import java.io.File
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.UUID

/** One stock GraalPy context in its own process, with an explicit application environment. */
data class PythonWorker(
    val workingDirectory: String? = null,
    val pythonPath: List<String> = emptyList(),
    val executable: String? = null,
    val pythonHome: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val arguments: List<String> = emptyList(),
    val timeoutMillis: Long = 300_000,
) {
    init {
        require(timeoutMillis > 0)
        require(workingDirectory == null || File(workingDirectory).isDirectory) { "Python worker directory is missing" }
        require(pythonPath.all { File(it).exists() }) { "Python worker import path is missing" }
        require(executable == null || File(executable).isFile) { "Python worker executable is missing" }
    }

    internal fun encode(): String = SubVmProtocol.encode(Teleported.obj(
        "directory" to workingDirectory, "path" to pythonPath, "executable" to executable,
        "home" to pythonHome, "environment" to environment, "arguments" to arguments,
        "timeout" to timeoutMillis,
    ))

    companion object {
        internal fun decode(source: String): PythonWorker {
            val message = SubVmProtocol.decode(source)
            fun strings(name: String) = (message.field(name) as? Teleported.Arr)?.v.orEmpty().map {
                (it as? Teleported.Str)?.v ?: error("Python worker $name requires strings")
            }
            val environment = (message.field("environment") as? Teleported.Obj)?.v.orEmpty().mapValues {
                (it.value as? Teleported.Str)?.v ?: error("Python worker environment requires strings")
            }
            return PythonWorker(message.str("directory"), strings("path"), message.str("executable"),
                message.str("home"), environment, strings("arguments"),
                (message.field("timeout") as? Teleported.Num)?.v ?: 300_000)
        }
    }
}

/** Separate protocol transport: native writes to fd 1/2 remain terminal bytes. */
internal class PythonWorkerTransport(private val timeoutMillis: Long) : AutoCloseable {
    private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).apply {
        soTimeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    val port: Int get() = listener.localPort
    val token: String = UUID.randomUUID().toString()

    fun accept(process: Process): Socket {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var connection: Socket? = null
        while (connection == null) {
            if (!process.isAlive) error("Python worker exited ${process.exitValue()}: " +
                process.errorStream.readAllBytes().decodeToString())
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            check(remaining > 0) { "Python worker did not open its protocol within ${timeoutMillis}ms" }
            listener.soTimeout = remaining.coerceAtMost(250).coerceAtLeast(1).toInt()
            try { connection = listener.accept() } catch (_: SocketTimeoutException) { /* Check process/deadline. */ }
        }
        val socket = requireNotNull(connection)
        try {
            socket.soTimeout = timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            require(DataInputStream(socket.getInputStream()).readUTF() == token) { "Python worker protocol identity mismatch" }
            socket.soTimeout = 0
            socket.tcpNoDelay = true
            return socket
        } catch (failure: Throwable) { socket.close(); throw failure }
    }

    override fun close() = listener.close()

    companion object {
        fun connect(port: Int, token: String): Socket = Socket(InetAddress.getLoopbackAddress(), port).apply {
            tcpNoDelay = true
            DataOutputStream(getOutputStream()).apply { writeUTF(token); flush() }
        }
    }
}
