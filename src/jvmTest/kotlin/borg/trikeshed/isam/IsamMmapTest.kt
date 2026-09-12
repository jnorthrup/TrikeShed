package borg.trikeshed.isam

import borg.trikeshed.cursor.ColumnMeta
import borg.trikeshed.cursor.Cursor
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.isam.meta.IsamMetaFileReader
import borg.trikeshed.lib.get
import borg.trikeshed.lib.j
import borg.trikeshed.userspace.MemoryOperation
import borg.trikeshed.userspace.MemoryTraceEvent
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.UringTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.*

class IsamMmapTest {
    private class Trace : UringTrace {
        val memory = mutableListOf<MemoryTraceEvent>()
        val io = mutableListOf<UringSubmission>()
        val order = mutableListOf<String>()
        override fun channel(id: Long, availability: String, nativeCapabilities: Long) {}
        override fun submit(channel: Long, submission: UringSubmission) { io += submission }
        override fun complete(channel: Long, submission: UringSubmission, result: Int) {
            if (submission.opcode == UringOp.CLOSE && result == 0) order += "close:${submission.fd}"
        }
        override fun failed(channel: Long, submission: UringSubmission, failure: String) { error(failure) }
        override fun memory(event: MemoryTraceEvent) {
            memory += event
            if (event.operation == MemoryOperation.UNMAP && event.failure == null) order += "unmap:${event.fd}"
        }
    }

    private fun rows(): Cursor {
        val metadata = arrayOf(ColumnMeta("id", IOMemento.IoInt), ColumnMeta("flag", IOMemento.IoByte))
        return 2 j { row: Int -> 2 j { column: Int ->
            val value: Any = if (column == 0) (row + 1) * 257 else (row + 9).toByte()
            value j { metadata[column] }
        } }
    }

    @Test fun mappedGroupsUseVmTraceAndReleaseBeforeDescriptors(): Unit = runBlocking {
        val directory = Files.createTempDirectory("isam-mmap-")
        try {
            val trace = Trace()
            val scope = CoroutineScope(coroutineContext + trace)
            val operations = UringIsamOperations(scope, IsamReadMode.MMAP)
            val path = directory.resolve("rows.bin").toString()
            operations.write(rows(), path, emptyMap(), true)
            assertEquals(3, trace.io.count { it.opcode == UringOp.FSYNC }, "Metadata and both data groups are flushed")
            trace.io.clear(); trace.order.clear()
            val reader = operations.createReader(path, "$path.meta", IsamMetaFileReader("$path.meta"))
            reader.open()
            val beforeRows = trace.io.size
            trace.order.clear()
            val snapshot = try {
                assertEquals(2, reader.recordCount)
                reader.readRow(1)
            } finally { reader.close() }
            assertEquals(514, snapshot[0].a)
            assertEquals(10.toByte(), snapshot[1].a, "Rows remain usable after unmap")
            val maps = trace.memory.filter { it.operation == MemoryOperation.MAP && it.failure == null }
            assertEquals(2, maps.size)
            for (mapping in maps) {
                assertFalse(trace.io.drop(beforeRows).any { it.fd == mapping.fd && it.opcode == UringOp.READ }, "Mapped data reads must not be reported as file READ SQEs")
                assertTrue(trace.memory.any { it.mapping == mapping.mapping && it.operation == MemoryOperation.READ })
                val unmap = trace.order.indexOf("unmap:${mapping.fd}")
                val close = trace.order.indexOf("close:${mapping.fd}")
                assertTrue(unmap >= 0 && close > unmap, "ISAM unmaps its region before closing that group descriptor")
            }
        } finally {
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
        }
    }
}
