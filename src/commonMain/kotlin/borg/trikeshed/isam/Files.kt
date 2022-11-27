package borg.trikeshed.isam

expect object Files{
    fun readAllLines(filename: String): List<String>
    fun readAllBytes(filename: String): ByteArray
    fun readString(filename: String): String

    fun writeAllBytes(filename: String, bytes: ByteArray)
    fun writeAllLines(filename: String, lines: List<String>)
    fun writeString(filename: String, string: String)

}