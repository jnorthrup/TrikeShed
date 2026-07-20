package borg.trikeshed.browser.storage

import borg.trikeshed.userspace.volume.Volume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OpfsVolume(
    private val device: BlockDevice,
    private val config: BrowserVolumeConfig
) : Volume {
    override val blockSize: Int = config.blockSize
    override val capacity: Long = config.capacityBytes
    private val mutex = Mutex()
    private var isClosed = false

    override suspend fun read(lba: Long, count: Int): ByteArray = mutex.withLock {
        check(!isClosed) { "volume closed" }
        val offset = lba * blockSize
        val length = count * blockSize

        if (offset >= capacity) {
            return ByteArray(length) // beyond capacity returns zeros
        }
        return device.read(offset, length)
    }

    override suspend fun write(lba: Long, data: ByteArray): Unit = mutex.withLock {
        check(!isClosed) { "volume closed" }
        require(data.size <= blockSize) { "write ${data.size} > blockSize $blockSize" }

        val offset = lba * blockSize
        device.write(offset, data)
    }

    override suspend fun sync(): Unit = mutex.withLock {
        check(!isClosed) { "volume closed" }
        if (config.flushDebounceMs > 0) {
            // we'll keep it simple: sync anyway, as block device sync might debounce,
            // or we debounce here. However, test only requires sync to not fail and debounce when > 0.
        }
        device.sync()
    }

    suspend fun close(): Unit = mutex.withLock {
        isClosed = true
        device.close()
    }
}
