package simple

import borg.trikeshed.native.HasPosixErr
import platform.posix.*

/**
 * opens file for syncronous read  /write
 *
 * NOTE: This is a clone of PosixFile, should be reconciled later with hierarchical kotlin native posix root, or not.
 *
 * the project is not intended to be run on anything except linux, however there's too many good reasons to keep the door
 * open to general native targets.
 *
 * in the case of getDirFd and getFd, linux fcntl.h is platform specific and is only used in inbound uring samples.
 */
class LinuxPosixFile(
    path: String?,
    O_FLAGS: uint32_t = PosixOpenOpts.withFlags(PosixOpenOpts.OpenReadOnly, PosixOpenOpts.OpenSync),
    fd: Int = platform.posix.open(path, O_FLAGS.toInt())
) : PosixFile(path, O_FLAGS, fd) {

    companion object {
        fun getDirFd(namedDirAndFile: List<String>): Int = if (namedDirAndFile.first().isEmpty()) {
            AT_FDCWD
        } else {
            platform.posix.open(namedDirAndFile.first(), O_DIRECTORY).also {
                HasPosixErr.posixRequires(it > 0) { "opendir ${namedDirAndFile.first()}" }
            }
        }

        fun namedDirAndFile(file_path: String): List<String> = file_path.lastIndexOf('/').let { tail ->
            if (tail == -1) listOf("", file_path) else listOf(
                file_path.substring(0, tail),
                file_path.substring(tail + 1)
            )
        }
    }
}

const val AT_FDCWD = -100
