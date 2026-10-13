package borg.trikeshed.btrfs

import kotlin.time.Instant

/** Local inode times; not part of the content-addressed file tree. Reads use a no-atime policy. */
data class BtrfsFileTimes(
    val lastModifiedTime: Instant,
    val lastAccessTime: Instant,
    val creationTime: Instant,
    val changeTime: Instant,
) {
    fun written(at: Instant) = copy(lastModifiedTime = at, changeTime = at)

    companion object {
        fun at(time: Instant) = BtrfsFileTimes(time, time, time, time)
        val ZERO = at(Instant.fromEpochSeconds(0))
    }
}
