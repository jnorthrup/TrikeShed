package borg.trikeshed.userspace

import borg.trikeshed.userspace.nio.ByteBuffer

public interface UserspaceChannelBackend {
    fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int
    fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int
    fun accept(file: FileImpl): Int
    fun connect(file: FileImpl, address: String, port: Int): Int
    fun close(file: FileImpl): Int
}

internal sealed interface PreparedChannelOp {
    val userData: Long

    data class Read(
        val file: FileImpl,
        val buffer: ByteBuffer,
        val offset: Long,
        override val userData: Long,
    ) : PreparedChannelOp

    data class Write(
        val file: FileImpl,
        val buffer: ByteBuffer,
        val offset: Long,
        override val userData: Long,
    ) : PreparedChannelOp

    data class Accept(
        val file: FileImpl,
        override val userData: Long,
    ) : PreparedChannelOp

    data class Connect(
        val file: FileImpl,
        val address: String,
        val port: Int,
        override val userData: Long,
    ) : PreparedChannelOp

    data class Close(
        val file: FileImpl,
        override val userData: Long,
    ) : PreparedChannelOp
}

public class FunctionalUringFacade(
    private val entries: Int,
    private val backend: UserspaceChannelBackend,
) {
    private val pending = ArrayDeque<PreparedChannelOp>()
    private val completions = ArrayDeque<SelectionResult>()

    init {
        require(entries > 0) { "entries must be positive" }
    }

    fun read(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        enqueue(PreparedChannelOp.Read(file, buffer, offset, userData))
    }

    fun write(file: FileImpl, buffer: ByteBuffer, offset: Long, userData: Long) {
        enqueue(PreparedChannelOp.Write(file, buffer, offset, userData))
    }

    fun accept(file: FileImpl, userData: Long) {
        enqueue(PreparedChannelOp.Accept(file, userData))
    }

    fun connect(file: FileImpl, address: String, port: Int, userData: Long) {
        enqueue(PreparedChannelOp.Connect(file, address, port, userData))
    }

    fun close(file: FileImpl, userData: Long) {
        enqueue(PreparedChannelOp.Close(file, userData))
    }

    fun submit(): Int {
        val submitted = pending.size
        while (pending.isNotEmpty()) {
            val op = pending.removeFirst()
            val res = when (op) {
                is PreparedChannelOp.Read -> backend.read(op.file, op.buffer, op.offset)
                is PreparedChannelOp.Write -> backend.write(op.file, op.buffer, op.offset)
                is PreparedChannelOp.Accept -> backend.accept(op.file)
                is PreparedChannelOp.Connect -> backend.connect(op.file, op.address, op.port)
                is PreparedChannelOp.Close -> backend.close(op.file)
            }
            completions.addLast(SelectionResult(res, op.userData))
        }
        return submitted
    }

    fun wait(minComplete: Int = 1): List<SelectionResult> {
        require(minComplete >= 0) { "minComplete must be non-negative" }
        if (completions.size < minComplete && pending.isNotEmpty()) submit()
        return peek()
    }

    fun peek(): List<SelectionResult> = buildList(completions.size) {
        while (completions.isNotEmpty()) add(completions.removeFirst())
    }

    private fun enqueue(op: PreparedChannelOp) {
        require(pending.size < entries) { "submission queue full" }
        pending.addLast(op)
    }
}

internal expect fun openUserspaceChannelBackend(entries: Int): UserspaceChannelBackend
