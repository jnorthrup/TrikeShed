package borg.trikeshed.common

import borg.trikeshed.lib.*

/** not unlike nio.Files */
expect object Files {
    fun readAllLines(filename: String): List<String>
    fun readAllBytes(filename: String): ByteArray
    fun readString(filename: String): String
    fun write(filename: String, bytes: ByteArray)
    fun write(filename: String, lines: List<String>)
    fun write(filename: String, string: String)
    fun cwd(): String
    fun exists(filename: String): Boolean
    /** read offsets and lines accompanying*/
    fun streamLines(/**non-seekable RO file, as in a fifo  */ fileName: String,bufsize:Int=64): Sequence<Join<Long, ByteArray>>
}