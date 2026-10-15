package borg.trikeshed.tilting.leaf

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

/** Reads a whole file through stdio. */
fun readFileBytes(path: String): ByteArray {
    val file = fopen(path, "rb") ?: error("cannot open $path")
    try {
        fseek(file, 0, SEEK_END)
        val size = ftell(file)
        fseek(file, 0, SEEK_SET)
        val bytes = ByteArray(size.toInt())
        if (size > 0) bytes.usePinned { pinned ->
            check(fread(pinned.addressOf(0), 1u, size.toULong(), file).toLong() == size) { "short read of $path" }
        }
        return bytes
    } finally {
        fclose(file)
    }
}

/** leafPermutation.kexe <corpus>: the permutation's Kotlin/Native cells. */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: error("usage: leafPermutation <file of concatenated 4096-byte pages>")
    leafPermutation(readFileBytes(path)) { println(it) }
}
