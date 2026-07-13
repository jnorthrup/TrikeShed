package borg.trikeshed.userspace.ebpf

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.channels.spi.ChannelOperations
import borg.trikeshed.userspace.nio.channels.spi.ChannelResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EbpfChannelizerTest {

    @Test
    fun testChannelizerPassThrough() {
        val dummyChannelOps = object : ChannelOperations {
            override fun openChannel(entries: Int): ChannelOperations.ChannelHandle {
                return object : ChannelOperations.ChannelHandle {
                    override val id: Int = 1

                    override fun read(buffer: ByteBuffer, offset: Long): Int {
                        // Return some dummy data
                        val bytes = "Hello".encodeToByteArray()
                        for (i in bytes.indices) {
                            buffer.put(i, bytes[i])
                        }
                        return bytes.size
                    }

                    override fun write(buffer: ByteBuffer, offset: Long): Int {
                        return buffer.limit() - buffer.position()
                    }

                    override fun submit(): Int = 0
                    override fun wait(minComplete: Int): List<ChannelResult> = emptyList()
                }
            }

            override fun socket(domain: Int, type: Int, protocol: Int): Int = 1
            override fun bind(fd: Int, port: Int): Int = 0
            override fun listen(fd: Int, backlog: Int): Int = 0
            override fun accept(fd: Int): Int = 2
            override fun connect(fd: Int, host: String, port: Int): Int = 0
            override fun close(fd: Int): Int = 0
        }

        // An eBPF program that just returns 1 (PASS)
        val passProgram = EbpfProgram(longArrayOf(
            // mov r0, 1
            (EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K).toLong() or (1L shl 32),
            // exit
            (EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT).toLong()
        ))

        val channelizer = EbpfChannelizer(dummyChannelOps, rxProgram = passProgram, txProgram = passProgram)

        val channel = channelizer.openChannel(10)

        val buffer = ByteBuffer.allocate(10)
        val readResult = channel.read(buffer, 0)
        assertEquals(5, readResult)

        val writeBuffer = ByteBuffer.allocate(5)
        writeBuffer.position(0)
        writeBuffer.limit(5)
        val writeResult = channel.write(writeBuffer, 0)
        assertEquals(5, writeResult)
    }

    @Test
    fun testChannelizerDrop() {
        val dummyChannelOps = object : ChannelOperations {
            override fun openChannel(entries: Int): ChannelOperations.ChannelHandle {
                return object : ChannelOperations.ChannelHandle {
                    override val id: Int = 1

                    override fun read(buffer: ByteBuffer, offset: Long): Int {
                        val bytes = "Hello".encodeToByteArray()
                        for (i in bytes.indices) {
                            buffer.put(i, bytes[i])
                        }
                        return bytes.size
                    }

                    override fun write(buffer: ByteBuffer, offset: Long): Int {
                        return buffer.limit() - buffer.position()
                    }

                    override fun submit(): Int = 0
                    override fun wait(minComplete: Int): List<ChannelResult> = emptyList()
                }
            }

            override fun socket(domain: Int, type: Int, protocol: Int): Int = 1
            override fun bind(fd: Int, port: Int): Int = 0
            override fun listen(fd: Int, backlog: Int): Int = 0
            override fun accept(fd: Int): Int = 2
            override fun connect(fd: Int, host: String, port: Int): Int = 0
            override fun close(fd: Int): Int = 0
        }

        // An eBPF program that returns 0 (DROP)
        val dropProgram = EbpfProgram(longArrayOf(
            // mov r0, 0
            (EbpfOpcode.BPF_ALU64 or EbpfOpcode.BPF_MOV or EbpfOpcode.BPF_K).toLong() or (0L shl 32),
            // exit
            (EbpfOpcode.BPF_JMP or EbpfOpcode.BPF_EXIT).toLong()
        ))

        val channelizer = EbpfChannelizer(dummyChannelOps, rxProgram = dropProgram, txProgram = dropProgram)
        val channel = channelizer.openChannel(10)

        val buffer = ByteBuffer.allocate(10)
        val readResult = channel.read(buffer, 0)

        // Should be 0 since eBPF dropped it
        assertEquals(0, readResult)

        val writeBuffer = ByteBuffer.allocate(5)
        writeBuffer.position(0)
        writeBuffer.limit(5)
        val writeResult = channel.write(writeBuffer, 0)

        // Write result returns length to caller but drops it internally
        assertEquals(5, writeResult)
    }
}
