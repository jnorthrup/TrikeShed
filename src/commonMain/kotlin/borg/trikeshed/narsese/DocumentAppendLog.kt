package borg.trikeshed.narsese

import borg.trikeshed.couch.isam.DurableAppendLog
import borg.trikeshed.lib.Closeable
import borg.trikeshed.isam.synchronizedLock

/** Separate curation ledger. append writes a CRC frame; flush establishes durability. */
class DocumentAppendLog private constructor(private val file: DocumentFrameFile) : DurableAppendLog, Closeable {
    private val gate = Any()
    companion object {
        fun open(path: String, parentPath: String): DocumentAppendLog =
            DocumentAppendLog(DocumentFrameFile.open(path, parentPath, DocumentFileKind.LOG))
    }

    override fun append(sequence: Long, payload: ByteArray): Long = synchronizedLock(gate) {
        file.append(sequence, payload).sequence
    }

    override suspend fun replay(onFrame: suspend (Long, ByteArray) -> Unit): Long {
        var offset = DocumentFrameFile.HEADER_SIZE.toLong()
        var sequence = 0L
        val end = synchronizedLock(gate) { file.checkUsable(); file.end }
        while (offset < end) {
            val frame = synchronizedLock(gate) { file.frameAt(offset) }
            check(frame.a.sequence > sequence) { "non-monotonic document ledger sequence" }
            onFrame(frame.a.sequence, frame.b)
            sequence = frame.a.sequence
            offset = frame.a.end
        }
        return sequence
    }

    override fun flush() = synchronizedLock(gate) { file.flush() }

    override fun injectCorruptionAfter(sequence: Long) = synchronizedLock(gate) {
        var offset = DocumentFrameFile.HEADER_SIZE.toLong()
        while (offset < file.end) {
            val frame = file.frameAt(offset).a
            if (frame.sequence > sequence) { file.corrupt(frame); return@synchronizedLock }
            offset = frame.end
        }
        error("no document frame after sequence $sequence")
    }

    override fun close() = synchronizedLock(gate) { file.close() }
}
