package borg.trikeshed.ebpf

/**
 * eBPF Map Interface
 */
interface EbpfMap {
    fun lookup(key: ByteArray): ByteArray?
    fun update(key: ByteArray, value: ByteArray)
    fun delete(key: ByteArray)
}

class EbpfHashMap : EbpfMap {
    private val store = HashMap<ByteArrayWrapper, ByteArray>()

    private class ByteArrayWrapper(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ByteArrayWrapper) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    override fun lookup(key: ByteArray): ByteArray? {
        return store[ByteArrayWrapper(key)]
    }

    override fun update(key: ByteArray, value: ByteArray) {
        store[ByteArrayWrapper(key)] = value.copyOf()
    }

    override fun delete(key: ByteArray) {
        store.remove(ByteArrayWrapper(key))
    }
}

class EbpfArrayMap(val maxEntries: Int, val valueSize: Int) : EbpfMap {
    private val data = Array<ByteArray?>(maxEntries) { null }

    private fun keyToInt(key: ByteArray): Int {
        if (key.size < 4) return 0
        return (key[0].toInt() and 0xFF) or
               ((key[1].toInt() and 0xFF) shl 8) or
               ((key[2].toInt() and 0xFF) shl 16) or
               ((key[3].toInt() and 0xFF) shl 24)
    }

    override fun lookup(key: ByteArray): ByteArray? {
        val idx = keyToInt(key)
        if (idx in 0 until maxEntries) {
            return data[idx]
        }
        return null
    }

    override fun update(key: ByteArray, value: ByteArray) {
        val idx = keyToInt(key)
        if (idx in 0 until maxEntries) {
            // valueSize enforcement left basic for POC
            val copy = value.copyOf(valueSize)
            data[idx] = copy
        }
    }

    override fun delete(key: ByteArray) {
        val idx = keyToInt(key)
        if (idx in 0 until maxEntries) {
            data[idx] = null
        }
    }
}
