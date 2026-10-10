package borg.trikeshed.couch.isam

import borg.trikeshed.collections.FunnelHashMap
import borg.trikeshed.collections.associative.FunnelHashIndex
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*

/** Dense ids 0 until size over string keys: the id space of dictionary-coded ISAM columns and log templates. */
interface Stringpool {
    /** Id of [value]: its id when present, else the next dense id, assigned as [value] is appended. */
    fun put(value: String): Int

    /** Key of [id]; null outside 0 until size. */
    operator fun get(id: Int): String?
}

/**
 * Keys persisted at [location] as a one-column meta-v2 ISAM file of zstd leaves (`zstd -d` yields the plain
 * fixed-width column); a key's id is its row. Lookup is a [FunnelHashIndex] over the rows present at open, then a
 * [FunnelHashMap] of the keys put since; [put] appends one row. [width] fixes the key bytes when the pool is created.
 */
class FileBackedStringpool(val location: String, width: Int = 64) : Stringpool {
    val operations = UringIsamOperations(leafBytes = ISAM_LEAF_BYTES)
    val column: RecordMeta
    var keys: Array<String?>
    var size: Int
    val index: FunnelHashIndex<String>
    val added = FunnelHashMap<String, Int>()

    init {
        if (IsamFileOperations().exists("$location.meta")) {
            val isam = IsamDataFile(location)
            isam.open()
            try {
                column = isam.metafile.constraints[0]
                size = isam.size
                keys = arrayOfNulls(maxOf(16, size))
                for (id in 0 until size) keys[id] = isam[id][0].a as String
            } finally { isam.close() }
        } else {
            column = RecordMeta("key", IOMemento.IoString, 0, width)
            size = 0
            keys = arrayOfNulls(16)
        }
        val present = keys
        index = FunnelHashIndex.build(size j { id: Int -> present[id]!! }, 0L)
    }

    override fun put(value: String): Int {
        index.get(value)?.let { return it }
        added.get(value)?.let { return it }
        val id = size
        val row: RowVec = 1 j { _: Int -> value j column.`↺` }
        operations.append(listOf(row), location, emptyMap(), null, false)
        if (id == keys.size) keys = keys.copyOf(id * 2)
        keys[id] = value
        added.put(value, id)
        size = id + 1
        return id
    }

    override fun get(id: Int): String? = if (id in 0 until size) keys[id] else null
}
