package borg.trikeshed.common

import borg.trikeshed.lib.Join
import borg.trikeshed.lib.Series

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
    fun streamLines(
        /**non-seekable RO file, as in a fifo  */
        fileName: String,
        bufsize: Int = 64,
    ): Sequence<Join<Long, ByteArray>>

    /** read offsets and lines
     * checks if the bytes can be facade as chars or if utf8 conversion is needed (dirty)
     * @return triple ( len, bytes, dirty   )*/
    fun iterateLines(
        fileName: String,
        bufsize: Int = 12, //for testing
    ): Iterable<Join<Long, Series<Byte>>>


}

