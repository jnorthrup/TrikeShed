package borg.trikeshed.userspace

import borg.trikeshed.lib.Series
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionalUringFacadeTest {
    private class RecordingBackend : UserspaceChannelBackend {
        val ops = mutableListOf<String>()

        override fun read(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
            ops += "read:${file.id}:$offset:${buffer.remaining()}"
            return 11
        }

        override fun write(file: FileImpl, buffer: ByteBuffer, offset: Long): Int {
            ops += "write:${file.id}:$offset:${buffer.remaining()}"
            return 22
        }

        override fun accept(file: FileImpl): Int {
            ops += "accept:${file.id}"
            return 33
        }

        override fun connect(file: FileImpl, address: String, port: Int): Int {
            ops += "connect:${file.id}:$address:$port"
            return 44
        }

        override fun close(file: FileImpl): Int {
            ops += "close:${file.id}"
            return 55
        }

        override fun sync(file: FileImpl, metaData: Boolean): Int {
            ops += "sync:${file.id}:$metaData"
            return 66
        }

        override fun truncate(file: FileImpl, size: Long): Int {
            ops += "truncate:${file.id}:$size"
            return 77
        }

        override fun map(
            file: FileImpl,
            mode: String,
            position: Long,
            size: Long
        ): Int {
            ops += "map:${file.id}:$mode:$position:$size"
            return 88
        }

        override fun submitBatch(submissions: List<UringSubmission>): List<SelectionResult> {
            return submissions.map { sub ->
                val result = when (sub.opcode) {
                    UringOp.READV -> {
                        ops += "read:${sub.fd}:${sub.offset}:${sub.buffer?.remaining()}"
                        11
                    }
                    UringOp.WRITEV -> {
                        ops += "write:${sub.fd}:${sub.offset}:${sub.buffer?.remaining()}"
                        22
                    }
                    UringOp.ACCEPT -> {
                        ops += "accept:${sub.fd}"
                        33
                    }
                    UringOp.CONNECT -> {
                        ops += "connect:${sub.fd}:127.0.0.1:${sub.len}"
                        44
                    }
                    UringOp.CLOSE -> {
                        ops += "close:${sub.fd}"
                        55
                    }
                    UringOp.FSYNC -> {
                        ops += "sync:${sub.fd}:true"
                        66
                    }
                    UringOp.NOP -> {
                        ops += "nop:${sub.fd}"
                        99
                    }
                    else -> 0
                }
                SelectionResult(result, sub.userData)
            }
        }

        override suspend fun batchEnqueue(submissions: Series<UringSubmission>): Series<UringCompletion> = TODO()
    }

    @Test
    fun submit_maps_queued_ops_through_backend_and_preserves_tokens() {
        val backend = RecordingBackend()
        val facade = FunctionalUringFacade(entries = 8, backend = backend)
        val file = FileImpl(7)

        facade.read(file, ByteBuffer.wrap(ByteArray(4)), offset = 12L, userData = 100L)
        facade.write(file, ByteBuffer.wrap(byteArrayOf(1, 2)), offset = 13L, userData = 101L)
        facade.close(file, userData = 102L)

        assertEquals(3, facade.submit())
        assertEquals(
            listOf(
                "read:7:12:4",
                "write:7:13:2",
                "close:7",
            ),
            backend.ops,
        )
        assertEquals(
            listOf(
                SelectionResult(11, 100L),
                SelectionResult(22, 101L),
                SelectionResult(55, 102L),
            ),
            facade.wait(minComplete = 3),
        )
    }

    @Test
    fun wait_submits_functional_fallback_when_kernel_ring_is_absent() {
        val backend = RecordingBackend()
        val facade = FunctionalUringFacade(entries = 8, backend = backend)
        val file = FileImpl(9)

        facade.accept(file, userData = 201L)
        facade.connect(file, address = "127.0.0.1", port = 443, userData = 202L)

        assertEquals(
            listOf(
                SelectionResult(33, 201L),
                SelectionResult(44, 202L),
            ),
            facade.wait(minComplete = 2),
        )
        assertEquals(
            listOf(
                "accept:9",
                "connect:9:127.0.0.1:443",
            ),
            backend.ops,
        )
        assertEquals(emptyList(), facade.peek())
    }

    @Test
    fun enqueue_maps_sync_truncate_and_map_ops() {
        val backend = RecordingBackend()
        val facade = FunctionalUringFacade(entries = 8, backend = backend)
        val file = FileImpl(11)

        facade.sync(file, metaData = true, userData = 301L)
        facade.truncate(file, size = 1024L, userData = 302L)
        facade.map(file, mode = "rw", position = 0L, size = 4096L, userData = 303L)

        assertEquals(3, facade.submit())
        assertEquals(
            listOf(
                "sync:11:true",
                "nop:-1", // from truncate which calls nop(-1)
                "nop:-1", // from map which calls nop(-1)
            ),
            backend.ops,
        )
    }
}
