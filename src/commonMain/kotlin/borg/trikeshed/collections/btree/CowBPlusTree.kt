package borg.trikeshed.collections.btree

import borg.trikeshed.job.ContentId
import borg.trikeshed.job.JobId
import borg.trikeshed.job.CasStore

/**
 * Key for CowBPlusTree
 */
data class BTreeKey(
    val facetId: String,
    val facetValue: ByteArray,
    val jobId: JobId,
    val revision: Long,
) : Comparable<BTreeKey> {
    override fun compareTo(other: BTreeKey): Int {
        val fIdCmp = facetId.compareTo(other.facetId)
        if (fIdCmp != 0) return fIdCmp

        val minLen = minOf(facetValue.size, other.facetValue.size)
        for (i in 0 until minLen) {
            val a = facetValue[i].toInt() and 0xFF
            val b = other.facetValue[i].toInt() and 0xFF
            if (a != b) return a.compareTo(b)
        }
        val fValCmp = facetValue.size.compareTo(other.facetValue.size)
        if (fValCmp != 0) return fValCmp

        val jobCmp = jobId.value.compareTo(other.jobId.value)
        if (jobCmp != 0) return jobCmp

        return revision.compareTo(other.revision)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BTreeKey) return false
        return compareTo(other) == 0
    }

    override fun hashCode(): Int {
        var result = facetId.hashCode()
        result = 31 * result + facetValue.contentHashCode()
        result = 31 * result + jobId.hashCode()
        result = 31 * result + revision.hashCode()
        return result
    }
}

/**
 * Value for CowBPlusTree
 */
data class BTreeValue(
    val cid: ContentId,
    val sequence: Long,
)

/**
 * Nodes of CowBPlusTree
 */
sealed class BTreeNode {
    abstract val isLeaf: Boolean
    abstract val keys: List<BTreeKey>

    data class Leaf(
        override val keys: List<BTreeKey>,
        val values: List<BTreeValue>
    ) : BTreeNode() {
        override val isLeaf: Boolean = true
        init {
            require(keys.size == values.size) { "Leaf node keys and values must have the same size" }
        }
    }

    data class Internal(
        override val keys: List<BTreeKey>,
        val children: List<ContentId>
    ) : BTreeNode() {
        override val isLeaf: Boolean = false
        init {
            require(children.size == keys.size + 1) { "Internal node children must be keys.size + 1" }
        }
    }
}

/**
 * Codec for CowBPlusTree Nodes
 */
object CowBPlusTreeCodec {
    private val MAGIC = byteArrayOf(0x42, 0x54, 0x31, 0x00) // BT1\0
    private const val VERSION = 1
    private const val MAX_FIELD_BYTES = 16 * 1024 * 1024

    fun encode(node: BTreeNode): ByteArray {
        val out = mutableListOf<Byte>()
        out.addAll(MAGIC.toList())
        out.addAll(intToBytes(VERSION).toList())

        when (node) {
            is BTreeNode.Leaf -> {
                out.add(1) // Leaf marker
                out.addAll(intToBytes(node.keys.size).toList())
                for (i in node.keys.indices) {
                    encodeKey(node.keys[i], out)
                    encodeValue(node.values[i], out)
                }
            }
            is BTreeNode.Internal -> {
                out.add(0) // Internal marker
                out.addAll(intToBytes(node.keys.size).toList())
                for (key in node.keys) {
                    encodeKey(key, out)
                }
                out.addAll(intToBytes(node.children.size).toList())
                for (child in node.children) {
                    encodeString(child.value, out)
                }
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): BTreeNode {
        var offset = 0
        fun readBytes(size: Int): ByteArray {
            if (offset + size > bytes.size) throw IllegalArgumentException("Unexpected end of bytes")
            val res = bytes.copyOfRange(offset, offset + size)
            offset += size
            return res
        }
        fun readInt(): Int {
            val b = readBytes(4)
            return ((b[0].toInt() and 0xFF) shl 24) or
                   ((b[1].toInt() and 0xFF) shl 16) or
                   ((b[2].toInt() and 0xFF) shl 8) or
                   (b[3].toInt() and 0xFF)
        }
        fun readLong(): Long {
            val b = readBytes(8)
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (b[i].toLong() and 0xFFL)
            }
            return value
        }
        fun readString(): String {
            val size = readInt()
            if (size > MAX_FIELD_BYTES) throw IllegalArgumentException("Field size too large")
            return readBytes(size).decodeToString()
        }
        fun readByteArray(): ByteArray {
            val size = readInt()
            if (size > MAX_FIELD_BYTES) throw IllegalArgumentException("Field size too large")
            return readBytes(size)
        }

        val magic = readBytes(4)
        if (!magic.contentEquals(MAGIC)) throw IllegalArgumentException("Invalid magic bytes")
        val version = readInt()
        if (version != VERSION) throw IllegalArgumentException("Unsupported version")

        val marker = readBytes(1)[0].toInt()
        val keySize = readInt()

        return if (marker == 1) {
            val keys = mutableListOf<BTreeKey>()
            val values = mutableListOf<BTreeValue>()
            for (i in 0 until keySize) {
                keys.add(BTreeKey(
                    facetId = readString(),
                    facetValue = readByteArray(),
                    jobId = JobId.of(readString()),
                    revision = readLong()
                ))
                values.add(BTreeValue(
                    cid = ContentId(readString()),
                    sequence = readLong()
                ))
            }
            BTreeNode.Leaf(keys, values)
        } else {
            val keys = mutableListOf<BTreeKey>()
            for (i in 0 until keySize) {
                keys.add(BTreeKey(
                    facetId = readString(),
                    facetValue = readByteArray(),
                    jobId = JobId.of(readString()),
                    revision = readLong()
                ))
            }
            val childrenSize = readInt()
            val children = mutableListOf<ContentId>()
            for (i in 0 until childrenSize) {
                children.add(ContentId(readString()))
            }
            BTreeNode.Internal(keys, children)
        }
    }

    private fun encodeKey(key: BTreeKey, out: MutableList<Byte>) {
        encodeString(key.facetId, out)
        encodeByteArray(key.facetValue, out)
        encodeString(key.jobId.value, out)
        out.addAll(longToBytes(key.revision).toList())
    }

    private fun encodeValue(value: BTreeValue, out: MutableList<Byte>) {
        encodeString(value.cid.value, out)
        out.addAll(longToBytes(value.sequence).toList())
    }

    private fun encodeString(str: String, out: MutableList<Byte>) {
        val bytes = str.encodeToByteArray()
        out.addAll(intToBytes(bytes.size).toList())
        out.addAll(bytes.toList())
    }

    private fun encodeByteArray(bytes: ByteArray, out: MutableList<Byte>) {
        out.addAll(intToBytes(bytes.size).toList())
        out.addAll(bytes.toList())
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }

    private fun longToBytes(value: Long): ByteArray {
        return byteArrayOf(
            (value ushr 56).toByte(),
            (value ushr 48).toByte(),
            (value ushr 40).toByte(),
            (value ushr 32).toByte(),
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )
    }
}

/**
 * Deterministic CAS-backed copy-on-write B+tree
 */
class CowBPlusTree(
    private val casStore: CasStore,
    private val maxDegree: Int = 32
) {
    init {
        require(maxDegree >= 3) { "maxDegree must be at least 3" }
    }

    private fun loadNode(cid: ContentId): BTreeNode {
        val bytes = casStore.get(cid) ?: throw IllegalStateException("Node not found: $cid")
        return CowBPlusTreeCodec.decode(bytes)
    }

    private fun saveNode(node: BTreeNode): ContentId {
        val bytes = CowBPlusTreeCodec.encode(node)
        return casStore.put(bytes)
    }

    fun get(rootCid: ContentId, key: BTreeKey): BTreeValue? {
        var currentCid = rootCid
        while (true) {
            val node = loadNode(currentCid)
            when (node) {
                is BTreeNode.Leaf -> {
                    val idx = node.keys.binarySearch(key)
                    return if (idx >= 0) node.values[idx] else null
                }
                is BTreeNode.Internal -> {
                    var idx = node.keys.binarySearch(key)
                    if (idx < 0) {
                        idx = -(idx + 1)
                    } else {
                        idx += 1
                    }
                    currentCid = node.children[idx]
                }
            }
        }
    }

    fun range(rootCid: ContentId, startKey: BTreeKey, endKey: BTreeKey): List<Pair<BTreeKey, BTreeValue>> {
        val result = mutableListOf<Pair<BTreeKey, BTreeValue>>()

        fun traverse(cid: ContentId) {
            val node = loadNode(cid)
            when (node) {
                is BTreeNode.Leaf -> {
                    for (i in node.keys.indices) {
                        val key = node.keys[i]
                        if (key >= startKey && key <= endKey) {
                            result.add(key to node.values[i])
                        }
                    }
                }
                is BTreeNode.Internal -> {
                    for (i in node.children.indices) {
                        val minKeyInChild = if (i == 0) null else node.keys[i - 1]
                        val maxKeyInChild = if (i == node.keys.size) null else node.keys[i]

                        val mightOverlap = (minKeyInChild == null || minKeyInChild <= endKey) &&
                                           (maxKeyInChild == null || maxKeyInChild >= startKey)

                        if (mightOverlap) {
                            traverse(node.children[i])
                        }
                    }
                }
            }
        }

        traverse(rootCid)
        return result
    }

    /**
     * Inserts a key-value pair and returns the new root ContentId.
     * If rootCid is null, creates a new root.
     */
    fun insert(rootCid: ContentId?, key: BTreeKey, value: BTreeValue): ContentId {
        if (rootCid == null) {
            val newRoot = BTreeNode.Leaf(listOf(key), listOf(value))
            return saveNode(newRoot)
        }

        val res = insertRecursive(rootCid, key, value)
        if (res.split != null) {
            val newRoot = BTreeNode.Internal(
                listOf(res.split.key),
                listOf(res.newCid, res.split.rightCid)
            )
            return saveNode(newRoot)
        }
        return res.newCid
    }

    private data class InsertResult(
        val newCid: ContentId,
        val split: Split? = null
    )

    private data class Split(
        val key: BTreeKey,
        val rightCid: ContentId
    )

    private fun insertRecursive(cid: ContentId, key: BTreeKey, value: BTreeValue): InsertResult {
        val node = loadNode(cid)
        when (node) {
            is BTreeNode.Leaf -> {
                var idx = node.keys.binarySearch(key)
                val newKeys = node.keys.toMutableList()
                val newValues = node.values.toMutableList()
                if (idx >= 0) {
                    newValues[idx] = value
                } else {
                    idx = -(idx + 1)
                    newKeys.add(idx, key)
                    newValues.add(idx, value)
                }

                if (newKeys.size <= maxDegree) {
                    return InsertResult(saveNode(BTreeNode.Leaf(newKeys, newValues)))
                } else {
                    val mid = newKeys.size / 2
                    val left = BTreeNode.Leaf(newKeys.subList(0, mid), newValues.subList(0, mid))
                    val right = BTreeNode.Leaf(newKeys.subList(mid, newKeys.size), newValues.subList(mid, newKeys.size))

                    val rightCid = saveNode(right)
                    val leftCid = saveNode(left)
                    return InsertResult(
                        leftCid,
                        Split(right.keys[0], rightCid)
                    )
                }
            }
            is BTreeNode.Internal -> {
                var idx = node.keys.binarySearch(key)
                if (idx < 0) {
                    idx = -(idx + 1)
                } else {
                    idx += 1
                }

                val childRes = insertRecursive(node.children[idx], key, value)

                val newChildren = node.children.toMutableList()
                newChildren[idx] = childRes.newCid
                val newKeys = node.keys.toMutableList()

                if (childRes.split != null) {
                    newKeys.add(idx, childRes.split.key)
                    newChildren.add(idx + 1, childRes.split.rightCid)
                }

                if (newKeys.size < maxDegree) {
                    return InsertResult(saveNode(BTreeNode.Internal(newKeys, newChildren)))
                } else {
                    val mid = newKeys.size / 2
                    val leftKeys = newKeys.subList(0, mid)
                    val leftChildren = newChildren.subList(0, mid + 1)

                    val splitKey = newKeys[mid]

                    val rightKeys = newKeys.subList(mid + 1, newKeys.size)
                    val rightChildren = newChildren.subList(mid + 1, newChildren.size)

                    val right = BTreeNode.Internal(rightKeys, rightChildren)
                    val left = BTreeNode.Internal(leftKeys, leftChildren)

                    val rightCid = saveNode(right)
                    val leftCid = saveNode(left)

                    return InsertResult(
                        leftCid,
                        Split(splitKey, rightCid)
                    )
                }
            }
        }
    }
}
