package borg.trikeshed.couch.isam

import borg.trikeshed.collections.FunnelHashMap
import borg.trikeshed.collections.associative.FunnelHashIndex
import borg.trikeshed.cursor.RowVec
import borg.trikeshed.isam.*
import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.userspace.nio.channels.FileChannel
import kotlinx.coroutines.CoroutineScope

/** Dense ids 0 until size over string keys: the id space of dictionary-coded ISAM columns and log templates. */
interface Stringpool {
    /** Id of [value]: its id when present, else the next dense id, assigned as [value] is appended. */
    fun put(value: String): Int

    /** Key of [id]; null outside 0 until size. */
    operator fun get(id: Int): String?
}

/**
 * Keys persisted at [location] as a one-column meta-v2 ISAM file of zstd leaves. The default is portable;
 * an optional compression dictionary is named by CID in the file. A key's id is its row.
 * Lookup is a [FunnelHashIndex] over the rows present at open, then a
 * [FunnelHashMap] of the keys put since; [put] appends one row. [width] fixes the key bytes when the pool is created.
 */
class FileBackedStringpool internal constructor(
    val location: String, width: Int, channels: IsamChannelFactory, leafCodec: IsamLeafCodec,
) : Stringpool {
    constructor(location: String, width: Int = 64, leafCodec: IsamLeafCodec = ZstdLeafCodec) :
        this(location, width, ::openIsamChannel, leafCodec)

    constructor(scope: CoroutineScope, location: String, width: Int = 64, leafCodec: IsamLeafCodec = ZstdLeafCodec) :
        this(location, width, { path, options -> FileChannel.open(scope, path, options) }, leafCodec)

    val operations = UringIsamOperations(channels, leafBytes = ISAM_LEAF_BYTES, leafCodec = leafCodec)
    val column: RecordMeta
    var keys: Array<String?>
    var size: Int
    val index: FunnelHashIndex<String>
    val added = FunnelHashMap<String, Int>()

    init {
        if (IsamFileOperations(channels).exists("$location.meta")) {
            val isam = IsamDataFile(location, operations = operations)
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
