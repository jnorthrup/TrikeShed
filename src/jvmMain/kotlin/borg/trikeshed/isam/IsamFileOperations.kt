package borg.trikeshed.isam

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.nio.IOException
import borg.trikeshed.userspace.nio.UringIOException
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.file.spi.FileOperations
import borg.trikeshed.userspace.nio.file.spi.JvmFileOperations

/** ISAM metadata uses the same channel factory, facade and backend as the data groups. */
internal class IsamFileOperations(private val channels: IsamChannelFactory) : FileOperations by JvmFileOperations() {
    override fun readAllBytes(filename: String): ByteArray {
        val channel = channels(filename, setOf(StandardOpenOption.READ))
        var failure: Throwable? = null
        try {
            val size = channel.size()
            require(size in 0..Int.MAX_VALUE.toLong()) { "ISAM metadata exceeds buffer capacity" }
            val bytes = ByteArray(size.toInt())
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                val start = buffer.position()
                val remaining = buffer.remaining()
                val count = channel.read(buffer, start.toLong())
                if (count <= 0 || count > remaining || buffer.position() != start + count) throw IOException("Incomplete ISAM metadata read")
            }
            return bytes
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { closeChannels(listOf(channel), failure) }
    }

    override fun readString(filename: String): String = readAllBytes(filename).decodeToString()
    override fun readAllLines(filename: String): List<String> = readString(filename).lineSequence().toList()

    override fun write(filename: String, bytes: ByteArray) {
        val channel = channels(filename, setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
        var failure: Throwable? = null
        try {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                val start = buffer.position()
                val remaining = buffer.remaining()
                val count = channel.write(buffer, start.toLong())
                if (count <= 0 || count > remaining || buffer.position() != start + count) throw IOException("Incomplete ISAM metadata write")
            }
            channel.force(true)
        } catch (caught: Throwable) { failure = caught; throw caught }
        finally { closeChannels(listOf(channel), failure) }
    }

    override fun write(filename: String, string: String) = write(filename, string.encodeToByteArray())
    override fun write(filename: String, lines: List<String>) = write(filename, lines.joinToString("\n", postfix = "\n"))

    override fun exists(filename: String): Boolean = try {
        channels(filename, setOf(StandardOpenOption.READ)).close()
        true
    } catch (failure: UringIOException) {
        if (failure.operation == UringOp.OPENAT && failure.result == -2) false else throw failure
    }
}
