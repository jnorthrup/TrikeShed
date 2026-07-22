package borg.trikeshed.parse.confix

internal class ByteArrayBuilder(initialCapacity: Int = 32) {
    private var buffer = ByteArray(initialCapacity)
    var size = 0
        private set

    fun write(b: Int) {
        ensureCapacity(size + 1)
        buffer[size++] = b.toByte()
    }

    fun write(bytes: ByteArray) {
        ensureCapacity(size + bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (minCapacity > buffer.size) {
            var newCapacity = buffer.size * 2
            if (newCapacity < minCapacity) newCapacity = minCapacity
            buffer = buffer.copyOf(newCapacity)
        }
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

internal object ConfixCborEmitter {
    fun emit(element: ConfixElement): ByteArray {
        val out = ByteArrayBuilder()
        write(out, element)
        return out.toByteArray()
    }

    private fun write(out: ByteArrayBuilder, e: ConfixElement) {
        when (e) {
            ConfixNull -> out.write(0xF6)
            is ConfixPrimitive -> {
                val v = e.content
                val bool = e.booleanOrNull
                val long = v.toLongOrNull()
                val dbl = v.toDoubleOrNull()
                when {
                    bool != null -> out.write(if (bool) 0xF5 else 0xF4)
                    long != null -> {
                        if (long >= 0) writeHead(out, 0, long)
                        else writeHead(out, 1, -1L - long)
                    }
                    dbl != null -> {
                        out.write(0xFB)
                        val bits = dbl.toBits()
                        out.write((bits ushr 56).toInt() and 0xFF)
                        out.write((bits ushr 48).toInt() and 0xFF)
                        out.write((bits ushr 40).toInt() and 0xFF)
                        out.write((bits ushr 32).toInt() and 0xFF)
                        out.write((bits ushr 24).toInt() and 0xFF)
                        out.write((bits ushr 16).toInt() and 0xFF)
                        out.write((bits ushr 8).toInt() and 0xFF)
                        out.write((bits ushr 0).toInt() and 0xFF)
                    }
                    else -> {
                        val b = v.encodeToByteArray()
                        writeHead(out, 3, b.size.toLong())
                        out.write(b)
                    }
                }
            }
            is ConfixArray -> {
                writeHead(out, 4, e.size.toLong())
                e.forEach { write(out, it) }
            }
            is ConfixObject -> {
                writeHead(out, 5, e.size.toLong())
                val sortedEntries = e.entries.map {
                    val keyBytes = it.key.encodeToByteArray()
                    val outKey = ByteArrayBuilder()
                    writeHead(outKey, 3, keyBytes.size.toLong())
                    outKey.write(keyBytes)
                    val keyEncoded = outKey.toByteArray()
                    Triple(it.key, keyEncoded, it.value)
                }.sortedWith { a, b ->
                    val lenCmp = a.second.size.compareTo(b.second.size)
                    if (lenCmp != 0) lenCmp else {
                        var res = 0
                        for (i in a.second.indices) {
                            val ba = a.second[i].toInt() and 0xFF
                            val bb = b.second[i].toInt() and 0xFF
                            res = ba.compareTo(bb)
                            if (res != 0) break
                        }
                        res
                    }
                }
                
                for ((_, keyEncoded, value) in sortedEntries) {
                    out.write(keyEncoded)
                    write(out, value)
                }
            }
        }
    }

    private fun writeHead(out: ByteArrayBuilder, mt: Int, len: Long) {
        val base = mt shl 5
        when {
            len in 0..23 -> out.write(base or len.toInt())
            len in 24..255 -> { out.write(base or 24); out.write(len.toInt()) }
            len in 256..65535 -> { 
                out.write(base or 25)
                out.write((len.toInt() ushr 8) and 0xFF)
                out.write(len.toInt() and 0xFF) 
            }
            len in 65536..4294967295L -> {
                out.write(base or 26)
                out.write((len.toInt() ushr 24) and 0xFF)
                out.write((len.toInt() ushr 16) and 0xFF)
                out.write((len.toInt() ushr 8) and 0xFF)
                out.write(len.toInt() and 0xFF)
            }
            else -> { 
                out.write(base or 27)
                for (s in 56 downTo 0 step 8) {
                    out.write((len ushr s).toInt() and 0xFF)
                }
            }
        }
    }
}
