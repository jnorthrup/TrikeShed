```kotlin
package simple

import borg.trikeshed.native.HasPosixErr
import platform.posix.*
import kotlinx.cinterop.*

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

        fun exists(fname: String): Boolean = access(fname, F_OK).z

        /** lean on getline to read a file into a sequence of CharSeries */
        fun readLinesSeq(path: String): Sequence<String> = sequence {
            val fp = fopen(path, "r") ?: return@sequence
            try {
                val line: CPointerVarOf<CPointer<ByteVarOf<Byte>>> = nativeHeap.alloc()
                val len: ULongVarOf<size_t> = nativeHeap.alloc()
                line.value = null
                len.value = 0u
                try {
                    while (true) {
                        val read = getline(line.ptr, len.ptr, fp)
                        if (read == -1L) break
                        yield(line.value!!.toKString().trim())
                    }
                    if (ferror(fp) != 0) {
                        perror("ferror")
                        exit(1)
                    }
                } finally {
                    free(line.value)
                    nativeHeap.free(line)
                    nativeHeap.free(len)
                }
            } finally {
                fclose(fp)
            }
        }

        fun readLines(path: String): List<String> = memScoped {
            val fp = fopen(path, "r")
            HasPosixErr.posixRequires(fp != null) { "fopen $path" }
            try {
                val line: CPointerVarOf<CPointer<ByteVarOf<Byte>>> = alloc()
                val len: ULongVarOf<size_t> = alloc()
                len.value = 0u
                var read: ssize_t
                val list: MutableList<String> = mutableListOf()

                while (true) {
                    read = getline(line.ptr, len.ptr, fp)
                    if (read == -1L) break
                    list.add(line.value!!.toKString().trim())
                }
                free(line.value)
                if (ferror(fp) != 0) {
                    perror("ferror")
                    error("readLines ferror on $path")
                }
                return list
            } finally {
                fclose(fp)
            }
        }

        fun readAllBytes(filename: String): ByteArray = memScoped {
            val file = LinuxPosixFile(filename)
            val stat = statk(filename)
            val len = stat.st_size.convert<Int>()
            val buf = allocArray<ByteVar>(len)
            val read = read(file.fd, buf, len.convert())
            HasPosixErr.posixRequires(read == len.toLong()) { "readAllBytes $filename" }
            file.close()
            ByteArray(len) { buf[it] }
        }

        fun readString(filename: String): String = readAllBytes(filename).decodeToString()
    }
}
```