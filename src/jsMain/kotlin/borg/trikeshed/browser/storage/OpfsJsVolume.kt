package borg.trikeshed.browser.storage

import borg.trikeshed.userspace.volume.Volume

class OpfsJsVolume(
    private val config: BrowserVolumeConfig
) : Volume by OpfsVolume(OpfsJsBlockDevice(config), config)

class OpfsJsBlockDevice(
    private val config: BrowserVolumeConfig
) : BlockDevice {
    override val blockSize: Int = config.blockSize

    private val opfs = OpfsJs()
    private var handle: Any? = null

    private suspend fun getHandle(): Any {
        if (handle == null) {
            handle = opfs.openFileHandle(config.namespace)
        }
        return handle!!
    }

    override suspend fun read(offset: Long, length: Int): ByteArray {
        val h = getHandle()
        val data = opfs.read(h, offset, length)

        // Return zeros if EOF
        if (data.size < length) {
            val result = ByteArray(length)
            data.copyInto(result, 0, 0, data.size)
            return result
        }
        return data
    }

    override suspend fun write(offset: Long, data: ByteArray) {
        val h = getHandle()
        opfs.write(h, offset, data)
    }

    override suspend fun sync() {
        // sync happens implicitly on write close with createWritable
    }

    override suspend fun close() {
        handle = null
    }
}
