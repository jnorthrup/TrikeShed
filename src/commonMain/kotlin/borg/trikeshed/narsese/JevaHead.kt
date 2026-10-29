package borg.trikeshed.narsese

import borg.trikeshed.lib.*

/**
 * The Float32 head of a torch zip checkpoint (`head.pt`): stored zip entries, and the pickle subset torch.save writes for
 * a state dict of tensors. Anything outside that subset is an error, not a skip.
 */
object JevaHead {
    private class Global(val module: String, val name: String)
    private class Storage(val key: String, val elements: Int)
    private class Cells(val storage: Storage, val offset: Int, val shape: IntArray, val stride: IntArray)
    private class Entry(val offset: Int, val size: Int)

    fun parse(archive: ByteArray): Map<String, MetaSeries<Tensor, Float>> {
        val entries = zip(archive)
        val pickle = entries.keys.singleOrNull { it.endsWith("/data.pkl") || it == "data.pkl" }
            ?: error("Torch archive has no data.pkl")
        val base = pickle.removeSuffix("data.pkl")
        entries[base + "byteorder"]?.let {
            require(archive.decodeToString(it.offset, it.offset + it.size) == "little") { "Torch archive is not little-endian" }
        }
        val state = unpickle(archive.copyOfRange(entries.getValue(pickle).offset, entries.getValue(pickle).let { it.offset + it.size }))
        val tensors = LinkedHashMap<String, MetaSeries<Tensor, Float>>()
        fun walk(prefix: String, value: Any?) {
            when (value) {
                is Map<*, *> -> for ((key, item) in value) {
                    walk(prefix + (key as? String ?: error("Torch state key is not text")) + if (item is Map<*, *>) "." else "", item)
                }
                is Cells -> require(tensors.put(prefix, floats(archive, entries, base, value)) == null) { "Duplicate tensor $prefix" }
                is String -> if (prefix == "decision_arch") require(value == "matrix_stack") { "Head architecture is $value" }
            }
        }
        require(state is Map<*, *>) { "Torch state is not a dict" }
        walk("", state)
        return tensors
    }

    private fun floats(archive: ByteArray, entries: Map<String, Entry>, base: String, cells: Cells): MetaSeries<Tensor, Float> {
        val entry = entries[base + "data/" + cells.storage.key] ?: error("Missing storage ${cells.storage.key}")
        require(entry.size == cells.storage.elements * 4) { "Storage ${cells.storage.key} is not Float32" }
        val shape = cells.shape
        var count = 1
        for (extent in shape) { require(extent >= 0); count *= extent }
        var expected = 1
        for (axis in shape.indices.reversed()) {
            require(shape[axis] == 1 || cells.stride[axis] == expected) { "Tensor is not row-major contiguous" }
            expected *= shape[axis]
        }
        require(cells.offset >= 0 && cells.offset.toLong() + count <= cells.storage.elements) { "Tensor exceeds its storage" }
        val values = FloatArray(count) {
            val at = entry.offset + (cells.offset + it) * 4
            val bits = (archive[at].toInt() and 255) or ((archive[at + 1].toInt() and 255) shl 8) or
                ((archive[at + 2].toInt() and 255) shl 16) or ((archive[at + 3].toInt() and 255) shl 24)
            Float.fromBits(bits).also { value -> require(value.isFinite()) { "Non-finite head value" } }
        }
        return tensorOf(IntArray(shape.size) { shape[it] }.toSeries(), values)
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 255) or ((b[at + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, at: Int): Int {
        val value = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)
        require(value <= Int.MAX_VALUE) { "Zip field exceeds 2 GiB" }
        return value.toInt()
    }

    private fun zip(b: ByteArray): Map<String, Entry> {
        var end = b.size - 22
        while (end >= 0 && u32Raw(b, end) != 0x06054b50L) end--
        require(end >= 0) { "Not a zip archive" }
        val count = u16(b, end + 10)
        var at = u32(b, end + 16)
        val entries = LinkedHashMap<String, Entry>()
        repeat(count) {
            require(u32Raw(b, at) == 0x02014b50L) { "Bad zip central directory" }
            require(u16(b, at + 10) == 0) { "Compressed zip entry" }
            val size = u32(b, at + 24)
            require(u32(b, at + 20) == size) { "Compressed zip entry" }
            val nameLength = u16(b, at + 28)
            val name = b.decodeToString(at + 46, at + 46 + nameLength)
            val local = u32(b, at + 42)
            require(u32Raw(b, local) == 0x04034b50L) { "Bad zip local header" }
            val data = local + 30 + u16(b, local + 26) + u16(b, local + 28)
            require(data.toLong() + size <= b.size) { "Truncated zip entry" }
            entries[name] = Entry(data, size)
            at += 46 + nameLength + u16(b, at + 30) + u16(b, at + 32)
        }
        return entries
    }

    private fun u32Raw(b: ByteArray, at: Int): Long = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

    private fun unpickle(b: ByteArray): Any? {
        val stack = ArrayList<Any?>()
        val marks = ArrayList<Int>()
        val memo = HashMap<Int, Any?>()
        var at = 0
        fun byte() = b[at++].toInt() and 255
        fun word(): Int = u16(b, at).also { at += 2 }
        fun int(): Int = (u16(b, at) or (u16(b, at + 2) shl 16)).also { at += 4 }
        fun line(): String { val from = at; while (b[at] != '\n'.code.toByte()) at++; return b.decodeToString(from, at++) }
        fun pop(): Any? = stack.removeAt(stack.lastIndex)
        fun sinceMark(): List<Any?> {
            val from = marks.removeAt(marks.lastIndex)
            return ArrayList(stack.subList(from, stack.size)).also { while (stack.size > from) stack.removeAt(stack.lastIndex) }
        }
        fun ints(value: Any?) = (value as List<*>).map { (it as Int) }.toIntArray()
        while (true) {
            when (val op = byte()) {
                0x80 -> require(byte() == 2) { "Unsupported pickle protocol" }
                '}'.code -> stack.add(LinkedHashMap<Any?, Any?>())
                'q'.code -> memo[byte()] = stack.last()
                'r'.code -> memo[int()] = stack.last()
                'h'.code -> stack.add(memo.getValue(byte()))
                'j'.code -> stack.add(memo.getValue(int()))
                '('.code -> marks += stack.size
                'X'.code -> { val n = int(); stack.add(b.decodeToString(at, at + n)); at += n }
                'c'.code -> stack.add(Global(line(), line()))
                ')'.code -> stack.add(emptyList<Any?>())
                'K'.code -> stack.add(byte())
                'M'.code -> stack.add(word())
                'J'.code -> stack.add(int())
                't'.code -> stack.add(sinceMark())
                0x85 -> stack.add(listOf(pop()))
                0x86 -> { val second = pop(); val first = pop(); stack.add(listOf(first, second)) }
                0x87 -> { val third = pop(); val second = pop(); val first = pop(); stack.add(listOf(first, second, third)) }
                0x88 -> stack.add(true)
                0x89 -> stack.add(false)
                'R'.code -> {
                    val args = pop() as List<*>
                    val function = pop() as Global
                    stack.add(when (function.module + " " + function.name) {
                        "collections OrderedDict" -> { require(args.isEmpty()); LinkedHashMap<Any?, Any?>() }
                        "torch._utils _rebuild_tensor_v2" -> Cells(args[0] as Storage, args[1] as Int, ints(args[2]), ints(args[3]))
                        else -> error("Unsupported pickle callable ${function.module}.${function.name}")
                    })
                }
                'Q'.code -> {
                    val id = pop() as List<*>
                    val kind = id[1] as Global
                    require(id[0] == "storage" && kind.module == "torch" && kind.name == "FloatStorage") { "Unsupported torch storage" }
                    stack.add(Storage(id[2] as String, id[4] as Int))
                }
                's'.code -> { val value = pop(); val key = pop(); @Suppress("UNCHECKED_CAST") (stack.last() as MutableMap<Any?, Any?>)[key] = value }
                'u'.code -> {
                    val items = sinceMark()
                    @Suppress("UNCHECKED_CAST") val target = stack.last() as MutableMap<Any?, Any?>
                    for (i in items.indices step 2) target[items[i]] = items[i + 1]
                }
                'b'.code -> require(pop() is Map<*, *> && stack.last() is Map<*, *>) { "Unsupported pickle state" }
                '.'.code -> return pop()
                else -> error("Unsupported pickle opcode 0x${op.toString(16)}")
            }
        }
    }
}

/** A head read from `head.pt`: small enough to hold, nothing mapped. */
class JevaHeadTensors(private val tensors: Map<String, MetaSeries<Tensor, Float>>) : JevaTensors {
    override fun get(name: String): MetaSeries<Tensor, Float> = requireNotNull(tensors[name]) { "Missing Jeva tensor: $name" }
    override val mappedBytes: Long get() = 0L
    override fun close() {}
}
