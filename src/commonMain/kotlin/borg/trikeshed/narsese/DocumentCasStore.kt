package borg.trikeshed.narsese

import borg.trikeshed.job.CasStore
import borg.trikeshed.job.ContentId
import borg.trikeshed.lib.Closeable
import borg.trikeshed.isam.synchronizedLock

/** Append-only CAS with a digest-to-frame index. Successful puts include file fsync. */
class DocumentCasStore private constructor(private val file: DocumentFrameFile) : CasStore(), Closeable {
    private val index = linkedMapOf<ContentId, DocumentFrame>()
    private val gate = Any()

    companion object {
        fun open(path: String, parentPath: String): DocumentCasStore {
            val file = DocumentFrameFile.open(path, parentPath, DocumentFileKind.CAS)
            try { return DocumentCasStore(file) } catch (failure: Throwable) {
                try { file.close() } catch (closeFailure: Throwable) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }
    }

    init {
        var offset = DocumentFrameFile.HEADER_SIZE.toLong()
        while (offset < file.end) {
            val frame = file.frameAt(offset)
            val cid = ContentId.of(frame.b)
            index[cid]?.let { check(file.read(it).contentEquals(frame.b)) { "CAS digest collision: $cid" } }
            index[cid] = frame.a
            offset = frame.a.end
        }
    }

    override fun put(bytes: ByteArray): ContentId = synchronizedLock(gate) {
        file.checkUsable()
        require(bytes.size <= DocumentFrameFile.MAX_PAYLOAD) { "document CAS payload exceeds ${DocumentFrameFile.MAX_PAYLOAD} bytes" }
        val payload = bytes.copyOf()
        val cid = ContentId.of(payload)
        index[cid]?.let {
            check(file.read(it).contentEquals(payload)) { "CAS digest collision or corruption: $cid" }
            return@synchronizedLock cid
        }
        val frame = file.append(file.sequence + 1, payload)
        file.flush()
        check(file.read(frame).contentEquals(payload)) { "CAS read-back verification failed: $cid" }
        index[cid] = frame
        cid
    }

    override fun get(cid: ContentId): ByteArray? = synchronizedLock(gate) {
        file.checkUsable()
        index[cid]?.let { frame ->
            file.read(frame).also { check(ContentId.of(it) == cid) { "CAS digest mismatch: $cid" } }
        }
    }

    override fun close() = synchronizedLock(gate) { file.close() }
}
