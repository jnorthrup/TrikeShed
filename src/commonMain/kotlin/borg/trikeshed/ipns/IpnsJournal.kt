package borg.trikeshed.ipns

import borg.trikeshed.job.sha256
import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.UringIOException
import borg.trikeshed.userspace.nio.channels.FileChannel
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.UringOp
import borg.trikeshed.userspace.UringOp.Companion.UringSubmission
import borg.trikeshed.userspace.nio.file.StandardOpenOption.*
import borg.trikeshed.userspace.nio.file.attribute.PosixFilePermission.*
import borg.trikeshed.userspace.nio.file.attribute.PosixFilePermissions
import kotlin.time.Instant

/** Durable signer and append-only signed versions. One publisher owns a journal at a time. */
class IpnsJournal private constructor(
    private val channel: FileChannel,
    val identity: IpnsKeyPair,
    private val crypto: IpnsCrypto,
    private val leasePath: String,
) {
    var latest: IpnsRecord? = null
        private set
    private var end = HEADER_SIZE.toLong()
    private var closed = false

    fun append(record: IpnsRecord) {
        IpnsRecord.verify(identity.name, record.bytes, Instant.fromEpochSeconds(0), crypto, allowExpired = true)
        latest?.let { require(record.sequence >= it.sequence) { "IPNS sequence regression" } }
        val wire = record.bytes
        val length = byteArrayOf((wire.size ushr 24).toByte(), (wire.size ushr 16).toByte(), (wire.size ushr 8).toByte(), wire.size.toByte())
        writeAll(channel, length + wire + sha256(wire), end)
        channel.force(true)
        end += 4 + wire.size + 32
        latest = record
    }

    fun close() {
        if (closed) return
        closed = true
        try { channel.close() } finally { unlink(leasePath) }
    }

    private fun recover() {
        val size = channel.size()
        while (end < size) {
            if (size - end < 4) break
            val head = readAll(channel, 4, end)
            val width = head.fold(0L) { n, byte -> (n shl 8) or (byte.toLong() and 255) }
            require(width in 1..10240) { "Corrupt IPNS journal record length at $end" }
            if (size - end - 4 < width + 32) break
            val wire = readAll(channel, width.toInt(), end + 4)
            val hash = readAll(channel, 32, end + 4 + width)
            require(sha256(wire).contentEquals(hash)) { "Corrupt IPNS journal record at $end" }
            val record = IpnsRecord.verify(identity.name, wire, Instant.fromEpochSeconds(0), crypto, allowExpired = true)
            latest?.let { require(record.sequence >= it.sequence) { "IPNS journal sequence regression" } }
            latest = record
            end += 4 + width + 32
        }
        if (end != size) { channel.truncate(end); channel.force(true) }
    }

    companion object {
        private val MAGIC = "TSIPNS01".encodeToByteArray()
        private const val HEADER_SIZE = 104
        fun open(path: String, crypto: IpnsCrypto): IpnsJournal {
            val permissions = PosixFilePermissions.asFileAttribute(setOf(OWNER_READ, OWNER_WRITE))
            val lease = "$path.lock"
            // Exclusive creation also guards separate processes. A crash leaves an explicit stale lease;
            // it must only be removed after the operator has established that its owner is stopped.
            FileChannel.open(lease, setOf(WRITE, CREATE_NEW), permissions).close()
            return try { acquire(path, crypto, lease) }
            catch (failure: Throwable) {
                runCatching { unlink(lease) }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }

        private fun acquire(path: String, crypto: IpnsCrypto, lease: String): IpnsJournal {
            val permissions = PosixFilePermissions.asFileAttribute(setOf(OWNER_READ, OWNER_WRITE))
            val channel: FileChannel
            val created: Boolean
            try {
                channel = FileChannel.open(path, setOf(READ, WRITE, CREATE_NEW), permissions)
                created = true
            } catch (error: UringIOException) {
                if (error.result != -17) throw error
                return reopen(path, crypto, lease)
            }
            try {
                check(created)
                val identity = crypto.generate()
                val header = MAGIC + identity.publicKey + identity.privateKey
                writeAll(channel, header + sha256(header), 0)
                channel.force(true)
                return IpnsJournal(channel, identity, crypto, lease)
            } catch (failure: Throwable) { channel.close(); throw failure }
        }

        private fun reopen(path: String, crypto: IpnsCrypto, lease: String): IpnsJournal {
            val channel = FileChannel.open(path, READ, WRITE)
            try {
                require(channel.size() >= HEADER_SIZE) { "Incomplete IPNS identity journal; refusing to replace its signer" }
                val header = readAll(channel, HEADER_SIZE, 0)
                require(header.copyOfRange(0, 8).contentEquals(MAGIC) &&
                    sha256(header.copyOfRange(0, 72)).contentEquals(header.copyOfRange(72, 104))) { "Corrupt IPNS identity journal" }
                val identity = IpnsKeyPair(header.copyOfRange(8, 40), header.copyOfRange(40, 72))
                require(crypto.verify(identity.publicKey, MAGIC, crypto.sign(identity.privateKey, MAGIC))) { "IPNS signer does not match public key" }
                return IpnsJournal(channel, identity, crypto, lease).also { it.recover() }
            } catch (failure: Throwable) { channel.close(); throw failure }
        }

        private fun unlink(path: String) {
            val ring = UringChannels.open()
            try {
                val bytes = path.encodeToByteArray()
                ring.enqueue(UringSubmission(UringOp.UNLINKAT, -100, 0, bytes.size, 0,
                    userData = 1, buffer = ByteBuffer(bytes)))
                ring.submit()
                val result = ring.wait(1).single()
                check(result.userData == 1L)
                if (result.res < 0) throw UringIOException(UringOp.UNLINKAT, result.res, path)
            } finally { ring.closeNow() }
        }

        private fun writeAll(channel: FileChannel, bytes: ByteArray, offset: Long) {
            val buffer = ByteBuffer(bytes)
            var at = offset
            while (buffer.hasRemaining()) {
                val n = channel.write(buffer, at)
                check(n > 0) { "IPNS journal write made no progress" }
                at += n
            }
        }

        private fun readAll(channel: FileChannel, size: Int, offset: Long): ByteArray {
            val bytes = ByteArray(size)
            val buffer = ByteBuffer(bytes)
            var at = offset
            while (buffer.hasRemaining()) {
                val n = channel.read(buffer, at)
                check(n > 0) { "Truncated IPNS journal" }
                at += n
            }
            return bytes
        }
    }
}
