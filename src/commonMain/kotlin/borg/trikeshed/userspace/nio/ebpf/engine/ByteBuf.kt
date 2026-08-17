package borg.trikeshed.userspace.nio.ebpf.engine

/** Shared bytearray buffer for JIT emission. */
class ByteBuf {
    private val buffer = mutableListOf<Byte>()
    fun data(): ByteArray = ByteArray(buffer.size) { buffer[it] }
    fun push(vararg bytes: Byte) { buffer += bytes.toList() }
    fun pushByte(value: Byte) { buffer += value }
    fun pushInt32(value: Int) {
        buffer += value.toByte(); buffer += (value shr 8).toByte()
        buffer += (value shr 16).toByte(); buffer += (value shr 24).toByte()
    }
    fun pushInt64(value: Long) { for (i in 0..7) buffer += (value shr (i * 8)).toByte() }
    fun pushWord(value: Int) {
        buffer += value.toByte(); buffer += (value shr 8).toByte()
        buffer += (value shr 16).toByte(); buffer += (value shr 24).toByte()
    }
}

typealias JitCode = ByteArray
typealias JitFunction = (LongArray) -> Long
