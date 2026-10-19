package borg.trikeshed.tilting.zran

/** RFC 8878 §5: raw history or a trained dictionary's entropy tables, repeat offsets and history.
 * The input is owned here; each decoder copies the initial entropy state before consuming a frame.
 */
class ZstdDictionary(bytes: ByteArray) {
    internal val bytes = bytes.copyOf()
    val id: Long
    internal val content: ByteArray
    internal val repeats = intArrayOf(1, 4, 8)
    private val huffman = HufTable()
    private val offsets = FseTable(8)
    private val matches = FseTable(9)
    private val literals = FseTable(9)

    init {
        require(bytes.isNotEmpty()) { "zstd: empty dictionary" }
        if (bytes.size >= 8 && bytes.le32(0) == 0xEC30A437.toInt()) {
            id = bytes.le32(4).toLong() and 0xFFFFFFFFL
            require(id != 0L) { "zstd: trained dictionary id is zero" }
            var p = 8
            p += huffman.readTree(bytes, p, bytes.size)
            p += offsets.read(bytes, p, bytes.size, 31, 8)
            p += matches.read(bytes, p, bytes.size, 52, 9)
            p += literals.read(bytes, p, bytes.size, 35, 9)
            require(bytes.size - p >= 12) { "zstd: truncated dictionary repeat offsets" }
            for (i in 0..2) {
                val offset = bytes.le32(p + 4 * i).toLong() and 0xFFFFFFFFL
                require(offset in 1..(bytes.size - p - 12).toLong()) { "zstd: invalid dictionary repeat offset" }
                repeats[i] = offset.toInt()
            }
            content = bytes.copyOfRange(p + 12, bytes.size)
        } else {
            id = 0
            content = this.bytes
        }
    }

    internal fun initialize(decoder: ZstdDecoder) {
        repeats.copyInto(decoder.rep)
        if (id == 0L) return
        huffman.cell.copyInto(decoder.huf.cell)
        decoder.huf.log = huffman.log
        decoder.hufReady = true
        fun copy(source: FseTable, destination: FseTable): FseTable {
            source.cell.copyInto(destination.cell)
            destination.log = source.log
            return destination
        }
        decoder.of = copy(offsets, decoder.ofOwn)
        decoder.ml = copy(matches, decoder.mlOwn)
        decoder.ll = copy(literals, decoder.llOwn)
    }
}
