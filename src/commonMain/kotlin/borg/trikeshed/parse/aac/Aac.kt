package borg.trikeshed.parse.aac

import borg.trikeshed.parse.reify
import borg.trikeshed.tilting.zran.*

/**
 * Anna's Archive Containers (AAC), as the standard states them (Anna's Blog, 2023-08-15, "Anna's Archive
 * Containers (AAC): standardizing releases") and as the sample files of the annas-archive repository carry them.
 *
 * An AACID is `aacid__{collection}__{timestamp}__{collection-specific ID}__{shortuuid}`: the collection holds no
 * double underscore, the timestamp is `yyyyMMddTHHmmssZ` (UTC), and the collection-specific ID may be left out,
 * so an id has four parts or more. [key] is that ID, null when the id has none.
 */
data class AacId(val collection: String, val timestamp: String, val key: String?, val uuid: String) {
    override fun toString(): String = listOfNotNull("aacid", collection, timestamp, key, uuid).joinToString("__")

    companion object {
        fun of(text: String): AacId {
            val parts = text.split("__")
            require(parts.size >= 4 && parts[0] == "aacid") { "not an AACID: $text" }
            require(timestamp(parts[2])) { "AACID timestamp is not yyyyMMddTHHmmssZ: $text" }
            return AacId(parts[1], parts[2], if (parts.size == 4) null else parts.subList(3, parts.size - 1).joinToString("__"), parts.last())
        }

        /** Whether [text] is the short ISO 8601 form the standard names, `20230808T014342Z`. */
        fun timestamp(text: String): Boolean = text.length == 16 && text[8] == 'T' && text[15] == 'Z' &&
            text.indices.all { it == 8 || it == 15 || text[it] in '0'..'9' }
    }
}

/**
 * The name of a metadata file or of a binary data folder: `{institution}_meta__aacid__{collection}__{from}--{to}`
 * and what follows it (`.jsonl.zst`, `.jsonl.seekable.zst`, `.jsonl`, `.torrent`), or the same with `_data__` and
 * no suffix. [from] is a timestamp. [to] is kept as written: the standard makes it a timestamp, and one released
 * file (`ebscohost_records__20240823T161729Z--Wk44RExtNXgJ3346eBgRk9`) carries a shortuuid there.
 */
data class AacRange(val institution: String, val binary: Boolean, val collection: String, val from: String, val to: String, val suffix: String) {
    override fun toString(): String = "${institution}_${if (binary) "data" else "meta"}__aacid__${collection}__$from--$to$suffix"

    /** Whether [timestamp] lies in the range, both ends included; false when [to] is no timestamp. */
    fun holds(timestamp: String): Boolean = AacId.timestamp(to) && timestamp >= from && timestamp <= to

    companion object {
        fun of(name: String): AacRange {
            val mark = name.indexOf("__aacid__")
            require(mark > 0) { "not an AAC name: $name" }
            val head = name.substring(0, mark)
            val binary = head.endsWith("_data")
            require(binary || head.endsWith("_meta")) { "an AAC name is prefixed …_meta__ or …_data__: $name" }
            val rest = name.substring(mark + 9)
            val dashes = rest.indexOf("--")
            val split = if (dashes < 0) -1 else rest.lastIndexOf("__", dashes)
            require(split > 0) { "an AAC name ends {collection}__{from}--{to}: $name" }
            val from = rest.substring(split + 2, dashes)
            require(AacId.timestamp(from)) { "AAC range does not start at a timestamp: $name" }
            val dot = rest.indexOf('.', dashes).let { if (it < 0) rest.length else it }
            require(dot > dashes + 2) { "AAC range has no end: $name" }
            return AacRange(head.dropLast(5), binary, rest.substring(0, split), from, rest.substring(dashes + 2, dot), rest.substring(dot))
        }
    }
}

/**
 * One container's line of a metadata file. [metadata] is the collection's own value as [reify] reads JSON (a map,
 * a list, a string, a `Long` where the number is whole, else a `Double`), and [folder] the binary data folder
 * when the container has binary data: that data is the file [path], named by the AACID.
 */
class AacRecord(val id: AacId, val metadata: Any?, val folder: String?) {
    val path: String? get() = folder?.let { "$it/$id" }
}

object Aac {
    const val ID = "aacid"
    const val METADATA = "metadata"
    const val FOLDER = "data_folder"

    /** The record of one JSON line; the standard allows `aacid`, `metadata` and `data_folder` at the top and nothing else. */
    fun record(line: ByteArray): AacRecord {
        val fields = reify(line) { it.toLongOrNull() ?: it.toDoubleOrNull() } as? Map<*, *> ?: error("AAC line is not a JSON object")
        for (key in fields.keys) require(key == ID || key == METADATA || key == FOLDER) { "AAC line carries a field the standard does not allow: $key" }
        require(METADATA in fields) { "AAC line has no metadata" }
        val folder = fields[FOLDER]?.let { it as? String ?: error("AAC data_folder is not a string") }
        return AacRecord(AacId.of(fields[ID] as? String ?: error("AAC line has no aacid")), fields[METADATA], folder)
    }

    /** The JSON Lines text of a metadata file: [bytes] as they are, or decompressed when they start with a Zstandard frame. */
    fun lines(bytes: ByteArray): ByteArray {
        if (bytes.size < 4) return bytes
        val magic = bytes.le32(0)
        return if (magic == ZSTD_MAGIC || magic and -16 == ZSTD_SKIPPABLE) ZstdDecoder().decompress(bytes) else bytes
    }

    /** Every record of a whole metadata file, in file order. */
    fun records(bytes: ByteArray): List<AacRecord> {
        val out = ArrayList<AacRecord>()
        feed(Carry(), lines(bytes), true) { out.add(it); true }
        return out
    }

    /** What a line cut by a frame boundary leaves for the next frame, and the count of lines read. */
    class Carry { var rest = ByteArray(0); var lines = 0 }

    /** Hands [each] the records of the whole lines in [carry] + [text]; false when [each] stopped it. [last] ends the file. */
    fun feed(carry: Carry, text: ByteArray, last: Boolean, each: (AacRecord) -> Boolean): Boolean {
        val all = if (carry.rest.isEmpty()) text else carry.rest + text
        var start = 0
        while (true) {
            var end = start
            while (end < all.size && all[end] != NEWLINE) end++
            if (end == all.size && !(last && end > start)) break
            carry.lines++
            check(end > start) { "AAC line ${carry.lines} is empty" }
            val record = try { record(all.copyOfRange(start, end)) } catch (failed: Exception) { throw IllegalStateException("AAC line ${carry.lines}: ${failed.message}", failed) }
            start = minOf(end + 1, all.size)
            if (!each(record)) { carry.rest = all.copyOfRange(start, all.size); return false }
        }
        carry.rest = all.copyOfRange(start, all.size)
        return true
    }

    /**
     * The seek table of a `.seekable.zst` file of [length] bytes, read through [read] (offset, count → those bytes):
     * two reads, the nine-byte footer and the table it names. Null when the file ends in no seekable footer.
     */
    suspend fun table(length: Long, read: suspend (Long, Int) -> ByteArray): SeekTable? {
        if (length < SEEK_TABLE_FOOTER) return null
        val size = seekTableLength(read(length - SEEK_TABLE_FOOTER, SEEK_TABLE_FOOTER))
        if (size < 0 || size > length || size > Int.MAX_VALUE) return null
        return parseSeekTable(read(length - size, size.toInt()))
    }

    /**
     * The records of a seekable metadata file, frame by frame from its start, each frame one [read]: [each] takes
     * the records in file order and answers false to stop, so a reader that wants the first of something fetches
     * the frames up to it and no more. A line may run over a frame boundary. Returns the frames read.
     */
    suspend fun scan(length: Long, read: suspend (Long, Int) -> ByteArray, each: (AacRecord) -> Boolean): Int {
        val table = table(length, read) ?: error("no seekable footer: read the file whole")
        val carry = Carry()
        val decoder = ZstdDecoder()
        for (k in 0 until table.frames) {
            val packed = table.packed[k + 1] - table.packed[k]
            check(packed <= Int.MAX_VALUE && table.packed[k + 1] <= length) { "frame $k: $packed bytes ending at ${table.packed[k + 1]} of $length" }
            val text = decoder.decompress(read(table.packed[k], packed.toInt()))
            check(text.size.toLong() == table.content[k + 1] - table.content[k]) { "frame $k decodes to ${text.size} bytes, its table entry says ${table.content[k + 1] - table.content[k]}" }
            if (!feed(carry, text, k == table.frames - 1, each)) return k + 1
        }
        return table.frames
    }

    const val NEWLINE: Byte = 10
}
