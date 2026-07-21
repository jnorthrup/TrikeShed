package borg.trikeshed.volume

import borg.trikeshed.volume.Volume

expect object VolumeBackends {
    fun openPosix(path: String, blockSize: Int = 4096, capacityBytes: Long = blockSize.toLong() * 1024L): Volume
    fun openLiburing(path: String, blockSize: Int = 4096, capacityBytes: Long = blockSize.toLong() * 1024L): Volume
}
