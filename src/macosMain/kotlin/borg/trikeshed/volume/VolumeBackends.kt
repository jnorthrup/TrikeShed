package borg.trikeshed.volume

import borg.trikeshed.userspace.volume.Volume
import borg.trikeshed.userspace.volume.PosixVolume

actual object VolumeBackends {
    actual fun openPosix(path: String, blockSize: Int, capacityBytes: Long): Volume =
        PosixVolume(path, blockSize, capacityBytes)

    actual fun openLiburing(path: String, blockSize: Int, capacityBytes: Long): Volume =
        error("liburing volume backend is not available on macOS; use openPosix")
}
