package borg.trikeshed.volume

import borg.trikeshed.userspace.volume.Volume

actual object VolumeBackends {
    actual fun openPosix(path: String, blockSize: Int, capacityBytes: Long): Volume =
        PosixVolume(path, blockSize, capacityBytes)

    actual fun openLiburing(path: String, blockSize: Int, capacityBytes: Long): Volume =
        LiburingVolume.unsupported()
}
