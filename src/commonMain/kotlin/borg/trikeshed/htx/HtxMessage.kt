package borg.trikeshed.htx

object HtxMessage {
    private val EOM = byteArrayOf(0x00.toByte(), 0xFF.toByte(), 0x00.toByte())

    fun findEomOffset(data: ByteArray): Int {
        val window = 3
        for (i in 0 until data.size - window + 1) {
            if (data[i] == EOM[0] && data[i + 1] == EOM[1] && data[i + 2] == EOM[2]) {
                return i
            }
        }
        return -1
    }
}
