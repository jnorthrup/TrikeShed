package nio.ebpf.jit

/** Shared byte buffer for JIT code emission. */
class ByteBuf {
    private val buffer = mutableListOf<Byte>()
    fun data(): ByteArray = buffer.toByteArray()

    fun push(vararg bytes: Byte) { buffer += bytes.toList() }
    fun pushByte(value: Byte) { buffer += value }
    fun pushInt32(value: Int) {
        buffer += value.toByte()
        buffer += (value shr 8).toByte()
        buffer += (value shr 16).toByte()
        buffer += (value shr 24).toByte()
    }
    fun pushInt64(value: Long) {
        for (i in 0..7) {
            buffer += (value shr (i * 8)).toByte()
        }
    }
    fun pushWord(value: Int) {
        val v = value.toLong()
        buffer += (v and 0xFF).toByte()
        buffer += ((v shr 8) and 0xFF).toByte()
        buffer += ((v shr 16) and 0xFF).toByte()
        buffer += ((v shr 24) and 0xFF).toByte()
    }
    fun patch(offset: Int, byte: Byte) { buffer[offset] = byte }
}

typealias JitCode = ByteArray
typealias JitFunction = (LongArray) -> Long
