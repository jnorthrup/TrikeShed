package borg.trikeshed.nio

expect interface ByteBuffer {
    fun clear()
    fun flip()
    fun hasRemaining(): Boolean
    fun remaining(): Int
    fun position(): Int
    fun position(newPosition: Int)
    fun put(byte: Byte)
    fun put(bytes: ByteArray)
    fun putInt(value: Int)
    fun putLong(value: Long)
    fun get(): Byte
    fun get(bytes: ByteArray)
    fun getInt(): Int
    fun getLong(): Long
    fun limit(): Int
    fun limit(newLimit: Int)
    fun capacity(): Int
}
