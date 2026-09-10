package borg.trikeshed.userspace.nio

import borg.trikeshed.btrfs.BtrfsUringChannelReport
import borg.trikeshed.btrfs.BtrfsUringFileVolume
import borg.trikeshed.narsese.DocumentAppendLog
import borg.trikeshed.narsese.DocumentCasStore
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.UringChannel
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.file.StandardOpenOption
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One writer owns these three files in an existing directory. The source volume is a
 * dedicated staging extent; the append-only CAS retains original bytes and receipts.
 * Callers stop admission and drain their DocumentFeed before draining this storage.
 * Synchronous CAS/log contracts use exclusively owned SQ/CQ compatibility channels;
 * the source volume uses the scoped suspending submission/completion channel.
 */
class DocumentFeedStorage private constructor(
    val volume: BtrfsUringFileVolume,
    val cas: DocumentCasStore,
    val log: DocumentAppendLog,
    private val channel: UringChannel,
    private val job: CompletableJob,
) {
    companion object {
        suspend fun open(
            scope: CoroutineScope,
            rootPath: String,
            sourceCapacityBytes: Int = 64 * 1024 * 1024,
        ): DocumentFeedStorage {
            require(rootPath.isNotBlank())
            require(sourceCapacityBytes > 0 && sourceCapacityBytes % 4096 == 0) {
                "source capacity must be a positive multiple of 4096 bytes"
            }
            val root = rootPath.trimEnd('/').ifEmpty { "/" }
            val sourcePath = "$root/document-source.bin"
            // This is dedicated scratch storage. Never resize any nonempty existing file.
            val source = FileChannel.open(sourcePath, StandardOpenOption.READ,
                StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            try {
                if (source.size() == 0L) {
                    val lastByte = ByteBuffer.wrap(byteArrayOf(0))
                    check(source.write(lastByte, sourceCapacityBytes.toLong() - 1) == 1) {
                        "source-volume initialization made no progress"
                    }
                    source.force(true)
                }
                require(source.size() == sourceCapacityBytes.toLong()) {
                    "existing document source volume capacity differs from configured capacity"
                }
                val directory = FileChannel.open(root, StandardOpenOption.READ)
                try { directory.force(true) } finally { directory.close() }
            } finally { source.close() }

            val job = SupervisorJob(scope.coroutineContext[Job])
            val owner = CoroutineScope(scope.coroutineContext + job)
            var channel: UringChannel? = null
            var volume: BtrfsUringFileVolume? = null
            var cas: DocumentCasStore? = null
            var log: DocumentAppendLog? = null
            try {
                channel = UringChannels.open(owner, entries = 8)
                volume = BtrfsUringFileVolume.open(channel, sourcePath, blockSize = 4096,
                    capacity = sourceCapacityBytes.toLong() / 4096, create = false, resize = false,
                    backendReport = currentNioCapabilityReport(),
                    channelReport = BtrfsUringChannelReport(channel.availability,
                        channel.capabilities, channel.nativeCapabilities))
                cas = DocumentCasStore.open("$root/document-cas.wal", root)
                log = DocumentAppendLog.open("$root/document-curator.wal", root)
                return DocumentFeedStorage(volume, cas, log, channel, job)
            } catch (failure: Throwable) {
                try {
                    withContext(NonCancellable) {
                        try { log?.close() } finally {
                            try { cas?.close() } finally {
                                try { volume?.drain() } finally {
                                    try { channel?.drain() } finally { job.complete(); job.join() }
                                }
                            }
                        }
                    }
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }
    }

    private val gate = Mutex()
    private var closed = false

    suspend fun drain(): Unit = withContext(NonCancellable) { gate.withLock {
        if (closed) return@withLock
        closed = true
        try {
            try { log.flush() } finally { log.close() }
        } finally {
            try { cas.close() } finally {
                try { volume.drain() } finally {
                    try { channel.drain() } finally { job.complete(); job.join() }
                }
            }
        }
    } }
}
