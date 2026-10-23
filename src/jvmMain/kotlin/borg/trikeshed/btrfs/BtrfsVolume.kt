package borg.trikeshed.btrfs

import borg.trikeshed.userspace.volume.Volume
import borg.trikeshed.userspace.nio.file.Files
import borg.trikeshed.userspace.nio.channels.UringChannels
import borg.trikeshed.userspace.nio.spi.currentNioCapabilityReport
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking

/** Legacy synchronous opener; reads use the common canonical parser and owned userspace channels. */
class BtrfsVolume(private val devicePath: String) : Volume {
    override val blockSize: Int = 4096
    private val deviceBytes: Long = Files.open(devicePath, readOnly = true).let { file ->
        try { file.size() } finally { file.close() }
    }
    override val capacity: Long

    init {
        require(deviceBytes >= 65536L + 4096 && deviceBytes % blockSize == 0L) { "Truncated or unaligned Btrfs image" }
        val sb = runBlocking {
            withImage { image ->
                val bytes = ByteArray(4096)
                image.read(65536L / blockSize, 1).get(bytes)
                BtrfsSuperblock.parse(bytes)
            }
        }
        require(sb.totalBytes <= deviceBytes.toULong() && sb.numDevices == 1uL) { "Unsupported Btrfs image geometry" }
        capacity = sb.totalBytes.toLong() / blockSize
    }

    private suspend fun <T> withImage(action: suspend (BtrfsUringFileVolume) -> T): T = coroutineScope {
        val channel = UringChannels.open(this, 8)
        var volume: BtrfsUringFileVolume? = null
        try {
            volume = BtrfsUringFileVolume.open(channel, devicePath, blockSize, deviceBytes / blockSize,
                create = false, resize = false, backendReport = currentNioCapabilityReport(), readOnly = true)
            action(volume)
        } finally {
            try { volume?.drain() } finally { channel.drain() }
        }
    }

    override suspend fun read(lba: Long, count: Int): ByteArray =
        withImage { BtrfsFilesystemImage.readLogical(it, lba, count) }

    override suspend fun write(lba: Long, data: ByteArray): Nothing =
        throw UnsupportedOperationException("BtrfsVolume is read-only; publish a staged BtrfsFilesystemImage for writes")

    override suspend fun sync(): Unit = Unit
}
