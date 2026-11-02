package borg.trikeshed.volume

import borg.trikeshed.userspace.volume.MingwVolume
import borg.trikeshed.userspace.volume.Volume

actual object VolumeBackends {
    /** The C runtime's file calls stand in for POSIX on Windows. */
    actual fun openPosix(path: String, blockSize: Int, capacityBytes: Long): Volume =
        MingwVolume(path, blockSize, capacityBytes)
    actual fun openLiburing(path: String, blockSize: Int, capacityBytes: Long): Volume =
        error("liburing volume backend is not available on Windows; use openPosix")
}
