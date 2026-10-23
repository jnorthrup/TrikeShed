package borg.trikeshed.btrfs

import borg.trikeshed.userspace.nio.ByteBuffer
import borg.trikeshed.userspace.nio.Volume
import kotlin.random.Random

/** Null data denotes a directory. The root directory is implicit. Paths use '/' and UTF-8. */
data class BtrfsImageEntry(
    val path: String,
    val data: ByteArray? = null,
    val mode: UInt = if (data == null) 0x41edu else 0x81a4u,
    val modifiedSeconds: Long = 0,
    val modifiedNanoseconds: Int = 0,
    val uid: UInt = 0u,
    val gid: UInt = 0u,
    val accessedSeconds: Long = 0,
    val accessedNanoseconds: Int = 0,
    val changedSeconds: Long = 0,
    val changedNanoseconds: Int = 0,
    val createdSeconds: Long = 0,
    val createdNanoseconds: Int = 0,
)
data class BtrfsImageSnapshot(
    val generation: ULong,
    val label: String,
    val entries: List<BtrfsImageEntry>,
    val fsid: ByteArray = ByteArray(0),
    val rootMetadata: BtrfsImageEntry = BtrfsImageEntry(""),
)
/** Capacity of the managed snapshot layout, including its fixed metadata/mirror reservation. */
data class BtrfsImageSpace(
    val blockSize: Int = 4096,
    val totalBlocks: Long,
    val allocatedBlocks: Long,
    val freeDataBlocks: Long,
    val availableDataBlocks: Long,
)

/**
 * Canonical single-device Btrfs snapshots over the common userspace Volume boundary.
 * write requires a fresh, zero-filled staging volume: it is NOT an in-place transaction.
 * Its caller publishes the completed and synced image atomically. Metadata is uncompressed,
 * as required by the native format. The initial data writer uses checksummed regular extents.
 */
object BtrfsFilesystemImage {
    const val MINIMUM_BYTES: Long = 128L * 1024 * 1024
    /** The next native superblock mirror starts at 256 GiB; this writer supports the first two. */
    const val MAXIMUM_BYTES: Long = 256L * 1024 * 1024 * 1024
    const val MAX_SNAPSHOT_DATA_BYTES: Long = 256L * 1024 * 1024
    const val MAX_SNAPSHOT_METADATA_BYTES: Long = 64L * 1024 * 1024
    /** Padded extents can contain more sectors than the logical payload quota alone permits. */
    const val MAX_CHECKSUM_ENTRIES: Long = MAX_SNAPSHOT_METADATA_BYTES / 64
    const val MAX_DIRECTORY_DEPTH: Int = 256
    private const val SECTOR = 4096
    private const val NODE = 16384
    private const val SYSTEM = 1024L * 1024
    private const val SYSTEM_BYTES = 4L * 1024 * 1024
    private const val METADATA = 5L * 1024 * 1024
    private const val METADATA_BYTES = 16L * 1024 * 1024
    private const val DATA = 80L * 1024 * 1024
    private val RELOC = (-9L).toULong()
    private val CSUM = (-10L).toULong()
    private data class Item(val key: BtrfsKey, val bytes: ByteArray)
    private data class Node(val address: Long, val owner: ULong, val level: Int, val items: List<Item>, val children: List<Node>) {
        val first: BtrfsKey get() = if (level == 0) items.firstOrNull()?.key ?: BtrfsKey(0u, 0u, 0u) else children.first().first
    }
    private data class Tree(val root: Node, val nodes: List<Node>)
    private data class FileExtent(val inode: ULong, val address: Long, val bytes: ByteArray, val diskSize: Int)
    private data class Chunk(val logical: Long, val size: Long, val flags: ULong)
    private class Allocation(var next: Long) { fun node(): Long = next.also { next += NODE } }
    private fun item(id: ULong, type: Int, offset: ULong = 0u, bytes: ByteArray) = Item(BtrfsKey(id, type.toUByte(), offset), bytes)
    private fun put(b: ByteArray, p: Int, n: Long, size: Int = 8) = BtrfsDisk.put(b, p, n.toULong(), size)
    private fun put(b: ByteArray, p: Int, n: ULong, size: Int = 8) = BtrfsDisk.put(b, p, n, size)
    private fun aligned(size: Int): Int {
        require(size <= Int.MAX_VALUE - SECTOR) { "Btrfs snapshot file exceeds byte-array extent capacity" }
        return ((size + SECTOR - 1) / SECTOR) * SECTOR
    }
    private fun capacity(volume: Volume): Long {
        require(volume.blockSize > 0 && SECTOR % volume.blockSize == 0) { "Volume block size must divide 4096" }
        require(volume.capacity in 0..Long.MAX_VALUE / volume.blockSize) { "Volume capacity overflow" }
        return volume.capacity * volume.blockSize
    }
    /**
     * Capacity for the next canonical snapshot on the configured staging volume.
     * This accounts for the 80 MiB reserved prefix, padded file extents, and payload budget.
     * It does not claim to inspect an imported image's native free-space tree or provide inode capacity.
     * Many small files can exhaust the fixed metadata group before the data region is full.
     */
    fun space(volumeCapacityBytes: Long, snapshot: BtrfsImageSnapshot): BtrfsImageSpace {
        require(volumeCapacityBytes in MINIMUM_BYTES..MAXIMUM_BYTES && volumeCapacityBytes % SECTOR == 0L) { "Unsupported Btrfs snapshot capacity" }
        val bytes = snapshot.entries.sumOf { it.data?.size?.toLong() ?: 0 }
        require(bytes <= MAX_SNAPSHOT_DATA_BYTES) { "Btrfs snapshot exceeds 256 MiB file-data budget" }
        val allocated = DATA / SECTOR + snapshot.entries.sumOf { (it.data?.size?.toLong()?.plus(SECTOR - 1) ?: 0) / SECTOR }
        val total = volumeCapacityBytes / SECTOR
        require(allocated <= total) { "Btrfs data block group is full" }
        val free = total - allocated
        return BtrfsImageSpace(totalBlocks = total, allocatedBlocks = allocated, freeDataBlocks = free,
            availableDataBlocks = minOf(free, (MAX_SNAPSHOT_DATA_BYTES - bytes) / SECTOR))
    }
    private suspend fun readAt(volume: Volume, address: Long, size: Int): ByteArray {
        require(address >= 0 && size >= 0 && address <= capacity(volume) - size) { "Btrfs read exceeds image" }
        val start = address / volume.blockSize
        val skip = (address % volume.blockSize).toInt()
        val count = (skip.toLong() + size + volume.blockSize - 1) / volume.blockSize
        require(count <= Int.MAX_VALUE) { "Btrfs read exceeds buffer capacity" }
        val buffer = volume.read(start, count.toInt())
        val all = ByteArray(count.toInt() * volume.blockSize)
        require(buffer.remaining() == all.size) { "Short Btrfs volume read" }
        buffer.get(all)
        return all.copyOfRange(skip, skip + size)
    }
    private suspend fun writeAt(volume: Volume, address: Long, bytes: ByteArray) {
        require(address % volume.blockSize == 0L && bytes.size % volume.blockSize == 0) { "Unaligned Btrfs image write" }
        require(address >= 0 && address <= capacity(volume) - bytes.size) { "Btrfs write exceeds image" }
        volume.write(address / volume.blockSize, ByteBuffer.wrap(bytes))
    }
    private fun validateMetadata(entry: BtrfsImageEntry) {
        require(listOf(entry.modifiedNanoseconds, entry.accessedNanoseconds, entry.changedNanoseconds, entry.createdNanoseconds).all { it in 0..999_999_999 }
            && entry.mode.toInt() and 61440 == if (entry.data == null) 16384 else 32768) { "Invalid Btrfs inode mode or timestamp" }
    }
    private fun captureEntries(input: List<BtrfsImageEntry>): List<BtrfsImageEntry> {
        require(input.sumOf { it.data?.size?.toLong() ?: 0 } <= MAX_SNAPSHOT_DATA_BYTES) { "Btrfs snapshot exceeds 256 MiB file-data budget" }
        var metadataBytes = input.sumOf { it.path.length.toLong() * 4 + 512 }
        require(metadataBytes <= MAX_SNAPSHOT_METADATA_BYTES) { "Btrfs snapshot exceeds metadata budget" }
        val records = linkedMapOf<String, BtrfsImageEntry>()
        for (entry in input) {
            val segments = entry.path.split('/')
            validateMetadata(entry)
            require(segments.size <= MAX_DIRECTORY_DEPTH && entry.path.encodeToByteArray().decodeToString() == entry.path) { "Btrfs path exceeds depth limit or contains invalid Unicode" }
            require(entry.path.isNotEmpty() && segments.all { it.isNotEmpty() && it != "." && it != ".." && '\u0000' !in it && it.encodeToByteArray().size <= 255 }) { "Invalid Btrfs image path: ${entry.path}" }
            require(entry.path.encodeToByteArray().size <= 4096 && entry.path !in records) { "Duplicate or oversized Btrfs image path" }
            records[entry.path] = entry.copy(data = entry.data?.copyOf())
        }
        for (path in records.keys.toList()) {
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(records[parent]?.data == null) { "File is an ancestor directory: $parent" }
                if (parent !in records) {
                    metadataBytes += parent.length.toLong() * 4 + 512
                    require(metadataBytes <= MAX_SNAPSHOT_METADATA_BYTES) { "Btrfs implicit directories exceed metadata budget" }
                    records[parent] = BtrfsImageEntry(parent)
                }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return records.values.sortedBy { it.path }
    }
    private fun tree(owner: ULong, input: List<Item>, allocation: Allocation): Tree {
        val sorted = input.sortedBy { it.key }
        require(sorted.zipWithNext().all { it.first.key < it.second.key }) { "Duplicate Btrfs tree key" }
        val leaves = mutableListOf<Node>()
        var batch = mutableListOf<Item>()
        var used = BtrfsDisk.HEADER
        for (entry in sorted) {
            require(entry.bytes.size + 25 <= NODE - BtrfsDisk.HEADER) { "Btrfs item exceeds leaf capacity" }
            if (used + 25 + entry.bytes.size > NODE) {
                leaves.add(Node(allocation.node(), owner, 0, batch, emptyList()))
                batch = mutableListOf(); used = BtrfsDisk.HEADER
            }
            batch.add(entry); used += 25 + entry.bytes.size
        }
        if (batch.isNotEmpty() || leaves.isEmpty()) leaves.add(Node(allocation.node(), owner, 0, batch, emptyList()))
        val all = leaves.toMutableList()
        var current: List<Node> = leaves
        var level = 0
        while (current.size > 1) {
            level++
            current = current.chunked((NODE - BtrfsDisk.HEADER) / 33).map { Node(allocation.node(), owner, level, emptyList(), it) }
            all.addAll(current)
        }
        return Tree(current.single(), all)
    }
    private fun nodeBytes(node: Node, generation: ULong, fsid: ByteArray, chunkId: ByteArray): ByteArray {
        val bytes = ByteArray(NODE)
        fsid.copyInto(bytes, 32); put(bytes, 48, node.address); put(bytes, 56, (1uL shl 56) or 1uL)
        chunkId.copyInto(bytes, 64); put(bytes, 80, generation); put(bytes, 88, node.owner)
        put(bytes, 96, (if (node.level == 0) node.items.size else node.children.size).toLong(), 4); bytes[100] = node.level.toByte()
        if (node.level == 0) {
            var end = NODE - BtrfsDisk.HEADER
            for ((i, entry) in node.items.withIndex()) {
                end -= entry.bytes.size
                val p = BtrfsDisk.HEADER + 25 * i
                BtrfsDisk.key(bytes, p, entry.key); put(bytes, p + 17, end.toLong(), 4); put(bytes, p + 21, entry.bytes.size.toLong(), 4)
                entry.bytes.copyInto(bytes, BtrfsDisk.HEADER + end)
            }
        } else for ((i, child) in node.children.withIndex()) {
            val p = BtrfsDisk.HEADER + 33 * i
            BtrfsDisk.key(bytes, p, child.first); put(bytes, p + 17, child.address); put(bytes, p + 25, generation)
        }
        BtrfsDisk.checksum(bytes)
        return bytes
    }
    private fun inode(directory: Boolean, size: Long, allocated: Long, generation: ULong) = ByteArray(160).also {
        put(it, 0, generation); put(it, 8, generation); put(it, 16, size); put(it, 24, allocated)
        put(it, 40, 1L, 4); put(it, 52, if (directory) 16877L else 33188L, 4)
    }
    private fun inodeRef(name: String, index: Long) = name.encodeToByteArray().let { nameBytes ->
        ByteArray(10 + nameBytes.size).also { put(it, 0, index); put(it, 8, nameBytes.size.toLong(), 2); nameBytes.copyInto(it, 10) }
    }
    private fun dirItem(name: String, target: BtrfsKey, directory: Boolean, generation: ULong) = name.encodeToByteArray().let { nameBytes ->
        ByteArray(30 + nameBytes.size).also {
            BtrfsDisk.key(it, 0, target); put(it, 17, generation); put(it, 27, nameBytes.size.toLong(), 2)
            it[29] = if (directory) 2 else 1; nameBytes.copyInto(it, 30)
        }
    }
    private fun nameHash(name: String): ULong = BtrfsDisk.crc(name.encodeToByteArray(), seed = -2).toUInt().toULong()
    private fun rootItem(tree: Tree, generation: ULong, directory: Boolean = false, uuid: ByteArray = ByteArray(16)) = ByteArray(439).also {
        inode(true, 3, NODE.toLong(), generation).copyInto(it)
        put(it, 160, generation); put(it, 168, if (directory) 256L else 0L); put(it, 176, tree.root.address)
        put(it, 192, tree.nodes.size.toLong() * NODE); put(it, 216, 1L, 4); it[238] = tree.root.level.toByte()
        put(it, 239, generation); uuid.copyInto(it, 247)
    }
    private fun chunkBytes(chunk: Chunk, deviceUuid: ByteArray) = ByteArray(80).also {
        put(it, 0, chunk.size); put(it, 8, 2L); put(it, 16, 65536L); put(it, 24, chunk.flags)
        put(it, 32, 4096L, 4); put(it, 36, 4096L, 4); put(it, 40, 4096L, 4); put(it, 44, 1L, 2)
        put(it, 48, 1L); put(it, 56, chunk.logical); deviceUuid.copyInto(it, 64)
    }
    private fun device(bytes: Long, allocated: Long, fsid: ByteArray, uuid: ByteArray) = ByteArray(98).also {
        put(it, 0, 1L); put(it, 8, bytes); put(it, 16, allocated)
        put(it, 24, 4096L, 4); put(it, 28, 4096L, 4); put(it, 32, 4096L, 4)
        uuid.copyInto(it, 66); fsid.copyInto(it, 82)
    }

    suspend fun write(volume: Volume, entries: List<BtrfsImageEntry>, label: String = "", generation: ULong = 1uL, fsid: ByteArray? = null, rootMetadata: BtrfsImageEntry = BtrfsImageEntry("")): BtrfsImageSnapshot {
        val total = capacity(volume)
        require(total in MINIMUM_BYTES..MAXIMUM_BYTES && total % SECTOR == 0L) { "Canonical Btrfs image needs 128 MiB through 256 GiB, sector aligned" }
        require(generation in 1uL..Long.MAX_VALUE.toULong()) { "Invalid Btrfs generation" }
        require(label.encodeToByteArray().size <= 255 && '\u0000' !in label) { "Btrfs label exceeds 255 UTF-8 bytes" }
        require(rootMetadata.path.isEmpty() && rootMetadata.data == null) { "Btrfs root metadata must describe the implicit root directory" }
        validateMetadata(rootMetadata)
        val captured = captureEntries(entries)
        // Publication must never overwrite a live or foreign image. Check superblocks before any write.
        for (offset in listOf(65536L, 67108864L)) require(readAt(volume, offset, SECTOR).all { it == 0.toByte() }) { "Btrfs writer requires a fresh staging image" }
        val rng = Random.Default
        fun uuid() = rng.nextBytes(16).also { it[6] = ((it[6].toInt() and 15) or 64).toByte(); it[8] = ((it[8].toInt() and 63) or 128).toByte() }
        require(fsid == null || (fsid.size == 16 && fsid.any { it != 0.toByte() })) { "Btrfs FSID must be a nonzero 16-byte UUID" }
        val imageFsid = fsid?.copyOf() ?: uuid(); val deviceId = uuid(); val chunkId = uuid(); val subvolId = uuid()
        val chunks = listOf(Chunk(SYSTEM, SYSTEM_BYTES, 2u), Chunk(METADATA, METADATA_BYTES, 4u), Chunk(DATA, total - DATA, 1u))
        val dev = device(total, chunks.sumOf { it.size }, imageFsid, deviceId)
        val chunkTree = tree(3u, listOf(item(1u, 216, 1u, dev)) + chunks.map { item(256u, 228, it.logical.toULong(), chunkBytes(it, deviceId)) }, Allocation(SYSTEM))
        val ids = mapOf("" to 256uL) + captured.mapIndexed { i, e -> e.path to (257 + i).toULong() }
        val paths = listOf(rootMetadata) + captured
        val fsItems = mutableListOf<Item>()
        val extents = mutableListOf<FileExtent>()
        var dataEnd = DATA
        val indices = mutableMapOf<String, Long>()
        for (entry in paths) {
            val id = ids.getValue(entry.path)
            val children = paths.filter { it.path.isNotEmpty() && it.path.substringBeforeLast('/', "") == entry.path }
            val data = entry.data
            val size = data?.size?.toLong() ?: children.sumOf { 2L * it.path.substringAfterLast('/').encodeToByteArray().size }
            val disk = if (data == null) 0 else aligned(data.size)
            val inodeBytes = inode(data == null, size, disk.toLong(), generation)
            put(inodeBytes, 44, entry.uid.toULong(), 4)
            put(inodeBytes, 48, entry.gid.toULong(), 4)
            put(inodeBytes, 52, entry.mode.toULong(), 4)
            put(inodeBytes, 112, entry.accessedSeconds)
            put(inodeBytes, 120, entry.accessedNanoseconds.toLong(), 4)
            put(inodeBytes, 124, entry.changedSeconds)
            put(inodeBytes, 132, entry.changedNanoseconds.toLong(), 4)
            put(inodeBytes, 136, entry.modifiedSeconds)
            put(inodeBytes, 144, entry.modifiedNanoseconds.toLong(), 4)
            put(inodeBytes, 148, entry.createdSeconds)
            put(inodeBytes, 156, entry.createdNanoseconds.toLong(), 4)
            fsItems.add(item(id, 1, bytes = inodeBytes))
            if (entry.path.isEmpty()) fsItems.add(item(id, 12, id, inodeRef("..", 0)))
            else {
                val parent = entry.path.substringBeforeLast('/', "")
                val parentId = ids.getValue(parent)
                val name = entry.path.substringAfterLast('/')
                val index = indices.getOrPut(parent) { 2L }
                indices[parent] = index + 1
                fsItems.add(item(id, 12, parentId, inodeRef(name, index)))
                val directory = dirItem(name, BtrfsKey(id, 1u, 0u), data == null, generation)
                val key = BtrfsKey(parentId, 84u, nameHash(name))
                val collision = fsItems.indexOfFirst { it.key == key }
                if (collision < 0) fsItems.add(Item(key, directory)) else fsItems[collision] = Item(key, fsItems[collision].bytes + directory)
                fsItems.add(item(parentId, 96, index.toULong(), directory))
            }
            if (data != null && data.isNotEmpty()) {
                require(dataEnd <= total - disk) { "Btrfs data block group is full" }
                extents.add(FileExtent(id, dataEnd, data, disk))
                val extent = ByteArray(53)
                put(extent, 0, generation); put(extent, 8, disk.toLong()); extent[20] = 1
                put(extent, 21, dataEnd); put(extent, 29, disk.toLong()); put(extent, 45, disk.toLong())
                fsItems.add(item(id, 108, bytes = extent))
                dataEnd += disk
            }
        }
        val allocation = Allocation(METADATA)
        val fsTree = tree(5u, fsItems, allocation)
        val devTree = tree(4u, listOf(item(0u, 249, 1u, ByteArray(40))) + chunks.map { chunk ->
            item(1u, 204, chunk.logical.toULong(), ByteArray(48).also {
                put(it, 0, 3L); put(it, 8, 256L); put(it, 16, chunk.logical); put(it, 24, chunk.size); chunkId.copyInto(it, 32)
            })
        }, allocation)
        val checksums = mutableListOf<Item>()
        for (extent in extents) {
            val sectors = extent.diskSize / SECTOR
            var sector = 0
            while (sector < sectors) {
                val count = minOf(4000, sectors - sector)
                val csums = ByteArray(count * 4)
                repeat(count) { j ->
                    val start = (sector + j) * SECTOR
                    val bytes = extent.bytes.copyOfRange(start, minOf(start + SECTOR, extent.bytes.size)).copyOf(SECTOR)
                    put(csums, j * 4, BtrfsDisk.crc(bytes).inv().toUInt().toULong(), 4)
                }
                checksums.add(item(CSUM, 128, (extent.address + sector.toLong() * SECTOR).toULong(), csums)); sector += count
            }
        }
        val csumTree = tree(7u, checksums, allocation)
        val uuidTree = tree(9u, listOf(item(BtrfsDisk.u64(subvolId, 0), 251, BtrfsDisk.u64(subvolId, 8), ByteArray(8).also { put(it, 0, 5L) })), allocation)
        val relocTree = tree(RELOC, listOf(item(256u, 1, bytes = inode(true, 0, 0, generation)), item(256u, 12, 256u, inodeRef("..", 0))), allocation)
        val rootAddress = allocation.node()
        val rootPlaceholder = Node(rootAddress, 1u, 0, emptyList(), emptyList())
        val baseNodes = chunkTree.nodes + fsTree.nodes + devTree.nodes + csumTree.nodes + uuidTree.nodes + relocTree.nodes + rootPlaceholder
        val extentStart = allocation.next
        var guessed = listOf(Node(extentStart, 2u, 0, emptyList(), emptyList()))
        var extentTree: Tree? = null
        repeat(16) {
            if (extentTree != null) return@repeat
            val all = baseNodes + guessed
            val items = all.map { node -> item(node.address.toULong(), 169, node.level.toULong(), ByteArray(33).also {
                put(it, 0, 1L); put(it, 8, generation); put(it, 16, 2L); it[24] = 176.toByte(); put(it, 25, node.owner)
            }) }.toMutableList()
            for (extent in extents) items.add(item(extent.address.toULong(), 168, extent.diskSize.toULong(), ByteArray(53).also {
                put(it, 0, 1L); put(it, 8, generation); put(it, 16, 1L); it[24] = 178.toByte()
                put(it, 25, 5L); put(it, 33, extent.inode); put(it, 41, 0L); put(it, 49, 1L, 4)
            }))
            for (chunk in chunks) items.add(item(chunk.logical.toULong(), 192, chunk.size.toULong(), ByteArray(24).also {
                val used = if (chunk.flags == 1uL) dataEnd - DATA else all.count { n -> n.address >= chunk.logical && n.address < chunk.logical + chunk.size }.toLong() * NODE
                put(it, 0, used); put(it, 8, 256L); put(it, 16, chunk.flags)
            }))
            val built = tree(2u, items, Allocation(extentStart))
            if (built.nodes.map { n -> n.address to n.level } == guessed.map { n -> n.address to n.level }) extentTree = built
            else guessed = built.nodes
        }
        val extent = checkNotNull(extentTree) { "Btrfs extent allocation did not converge" }
        val roots = listOf(2uL to extent, 4uL to devTree, 5uL to fsTree, 7uL to csumTree, 9uL to uuidTree, RELOC to relocTree)
        val rootItems = roots.map { (id, tree) -> item(id, 132, bytes = rootItem(tree, generation, id == 5uL || id == RELOC, if (id == 5uL) subvolId else ByteArray(16))) }.toMutableList()
        rootItems.add(item(5u, 12, 6u, inodeRef("default", 0)))
        rootItems.add(item(6u, 1, bytes = inode(true, 0, NODE.toLong(), generation)))
        rootItems.add(item(6u, 12, 6u, inodeRef("..", 0)))
        rootItems.add(item(6u, 84, nameHash("default"), dirItem("default", BtrfsKey(5u, 132u, ULong.MAX_VALUE), true, generation)))
        val rootTree = tree(1u, rootItems, Allocation(rootAddress))
        check(rootTree.nodes.size == 1) { "Btrfs root allocation exceeds reserved node" }
        val allNodes = baseNodes.filter { it !== rootPlaceholder } + rootTree.nodes + extent.nodes
        require(allNodes.filter { it.owner != 3uL }.all { it.address < METADATA + METADATA_BYTES }) { "Btrfs metadata block group is full" }
        require(chunkTree.nodes.all { it.address < SYSTEM + SYSTEM_BYTES }) { "Btrfs system block group is full" }
        // All validation and allocation above precede writes. Superblocks publish only after data and trees sync.
        for (entry in extents) {
            var offset = 0
            while (offset < entry.diskSize) {
                val count = minOf(1024 * 1024, entry.diskSize - offset)
                val bytes = entry.bytes.copyOfRange(offset, minOf(offset + count, entry.bytes.size)).copyOf(count)
                writeAt(volume, entry.address + offset, bytes); offset += count
            }
        }
        for (node in allNodes) {
            val bytes = nodeBytes(node, generation, imageFsid, chunkId)
            if (volume is BtrfsMetadataSink) volume.writeMetadata(node.address / volume.blockSize, ByteBuffer.wrap(bytes))
            else writeAt(volume, node.address, bytes)
        }
        volume.sync()
        val systemChunk = chunkBytes(chunks[0], deviceId)
        for (offset in listOf(67108864L, 65536L)) {
            val sb = ByteArray(SECTOR)
            imageFsid.copyInto(sb, 32); put(sb, 48, offset); put(sb, 56, 1L); put(sb, 64, BTRFS_MAGIC)
            put(sb, 72, generation); put(sb, 80, rootTree.root.address); put(sb, 88, chunkTree.root.address)
            put(sb, 112, total); put(sb, 120, allNodes.size.toLong() * NODE + dataEnd - DATA)
            put(sb, 128, 6L); put(sb, 136, 1L); put(sb, 144, SECTOR.toLong(), 4); put(sb, 148, NODE.toLong(), 4)
            put(sb, 152, NODE.toLong(), 4); put(sb, 156, SECTOR.toLong(), 4); put(sb, 160, 97L, 4)
            put(sb, 164, generation); put(sb, 188, 0x121L) // mixed backrefs, big metadata, skinny metadata
            sb[198] = rootTree.root.level.toByte(); sb[199] = chunkTree.root.level.toByte()
            dev.copyInto(sb, 201); label.encodeToByteArray().copyInto(sb, 299)
            BtrfsDisk.key(sb, 811, BtrfsKey(256u, 228u, SYSTEM.toULong())); systemChunk.copyInto(sb, 828)
            BtrfsDisk.checksum(sb); writeAt(volume, offset, sb)
        }
        volume.sync()
        return BtrfsImageSnapshot(generation, label, captured, imageFsid.copyOf(), rootMetadata)
    }

    private class Reader(val volume: Volume, val sb: BtrfsSuperblock) {
        val chunks = mutableMapOf<ULong, BtrfsChunkItem>()
        private var metadataBytes = 0L
        init {
            var p = 0
            while (p < sb.systemChunks.size) {
                val key = BtrfsDisk.key(sb.systemChunks, p)
                require(key.type == BTRFS_KEY_TYPE_CHUNK_ITEM) { "Invalid system chunk key" }
                val count = BtrfsDisk.u16(sb.systemChunks, p + 17 + 44)
                val size = 48 + count * 32
                BtrfsDisk.range(sb.systemChunks, p + 17, size)
                chunks[key.offset] = BtrfsChunkTree.decode(sb.systemChunks.copyOfRange(p + 17, p + 17 + size))
                p += 17 + size
            }
        }
        suspend fun logical(address: ULong, size: Int): ByteArray {
            val (base, chunk) = chunks.entries.filter { address >= it.key && address - it.key < it.value.length }.maxByOrNull { it.key }
                ?.let { it.key to it.value } ?: error("Unmapped Btrfs logical address $address")
            val profile = chunk.type and 7uL.inv()
            require(profile == CHUNK_SINGLE || profile == CHUNK_DUP) { "Unsupported Btrfs chunk profile: $profile" }
            require(chunk.stripes.size == if (profile == CHUNK_DUP) 2 else 1) { "Invalid Btrfs chunk stripe count" }
            val delta = address - base
            require(size >= 0 && chunk.stripes.all { it.devid == sb.deviceId } && size.toULong() <= chunk.length - delta) { "Btrfs extent crosses an unavailable chunk" }
            require(delta <= ULong.MAX_VALUE - chunk.stripes.first().offset) { "Btrfs physical address overflow" }
            val physical = chunk.stripes.first().offset + delta
            require(physical <= Long.MAX_VALUE.toULong()) { "Btrfs address exceeds host range" }
            return readAt(volume, physical.toLong(), size)
        }
        suspend fun walk(address: ULong, expectedLevel: Int, owner: ULong, generation: ULong? = null): List<Item> {
            val visited = mutableSetOf<ULong>()
            suspend fun visit(at: ULong, level: Int, expectedGeneration: ULong?): List<Item> {
                require(level in 0..8 && visited.add(at)) { "Cyclic or excessive Btrfs tree depth" }
                require(metadataBytes <= MAX_SNAPSHOT_METADATA_BYTES - sb.nodeSize.toLong()) { "Btrfs snapshot exceeds 64 MiB tree-read budget" }
                metadataBytes += sb.nodeSize.toLong()
                val bytes = logical(at, sb.nodeSize.toInt())
                BtrfsDisk.check(bytes)
                require(bytes.copyOfRange(32, 48).map(Byte::toUByte) == sb.metadataUuid && BtrfsDisk.u64(bytes, 48) == at && BtrfsDisk.u64(bytes, 88) == owner) { "Btrfs tree identity mismatch" }
                require(expectedGeneration == null || BtrfsDisk.u64(bytes, 80) == expectedGeneration) { "Btrfs tree generation mismatch" }
                require((bytes[100].toInt() and 255) == level) { "Btrfs tree level mismatch" }
                if (level == 0) return BtrfsDisk.leaf(bytes).map { Item(it.first, it.second) }
                val count = BtrfsDisk.u32(bytes, 96).toLong()
                require(count in 1..((bytes.size - BtrfsDisk.HEADER) / 33).toLong()) { "Invalid Btrfs node item count" }
                val result = mutableListOf<Item>()
                var previous: BtrfsKey? = null
                for (i in 0 until count.toInt()) {
                    val pos = BtrfsDisk.HEADER + i * 33
                    val key = BtrfsDisk.key(bytes, pos)
                    require(previous == null || previous < key) { "Unordered Btrfs node keys" }
                    val child = visit(BtrfsDisk.u64(bytes, pos + 17), level - 1, BtrfsDisk.u64(bytes, pos + 25))
                    require(child.isNotEmpty() && child.first().key == key && (result.isEmpty() || result.last().key < child.first().key)) { "Btrfs child key mismatch" }
                    result.addAll(child); previous = key
                }
                return result
            }
            return visit(address, expectedLevel, generation)
        }
        suspend fun load() {
            for (entry in walk(sb.chunkRoot, sb.chunkRootLevel, 3u)) if (entry.key.type == BTRFS_KEY_TYPE_CHUNK_ITEM) chunks[entry.key.offset] = BtrfsChunkTree.decode(entry.bytes)
        }
    }

    private suspend fun reader(volume: Volume): Reader {
        val sb = BtrfsSuperblock.parse(readAt(volume, 65536, SECTOR))
        require(sb.bytenr == 65536uL && sb.numDevices == 1uL && sb.totalBytes <= capacity(volume).toULong()) { "Unsupported multi-device or truncated Btrfs image" }
        require(sb.sectorSize == SECTOR.toUInt()) { "Only 4096-byte Btrfs sectors are supported" }
        val supported = 1uL or 2uL or 4uL or 8uL or 16uL or 32uL or 64uL or 256uL or 512uL or 1024uL
        require(sb.incompatFlags and supported.inv() == 0uL) { "Unsupported Btrfs incompatibility flags: ${sb.incompatFlags}" }
        require(sb.compatRoFlags and 3uL.inv() == 0uL) { "Unsupported Btrfs read-only compatibility flags: ${sb.compatRoFlags}" }
        require(sb.flags and 1uL.inv() == 0uL) { "Btrfs image has unsupported/error superblock flags" }
        require(sb.logRoot == 0uL && sb.logRootTransid == 0uL) { "Btrfs pending log replay is unsupported" }
        return Reader(volume, sb).also { it.load() }
    }

    /** Canonical logical-address reads; gaps in chunk allocation are rejected. */
    suspend fun readLogical(volume: Volume, lba: Long, count: Int): ByteArray {
        require(lba >= 0 && count >= 0 && lba <= Long.MAX_VALUE / SECTOR && count <= Int.MAX_VALUE / SECTOR) { "Invalid Btrfs logical read" }
        val source = reader(volume)
        val output = ByteArray(count * SECTOR)
        for (i in 0 until count) {
            require(lba <= Long.MAX_VALUE / SECTOR - i) { "Btrfs logical read overflow" }
            source.logical(((lba + i) * SECTOR).toULong(), SECTOR).copyInto(output, i * SECTOR)
        }
        return output
    }

    suspend fun read(volume: Volume): BtrfsImageSnapshot {
        val source = reader(volume)
        val sb = source.sb
        val roots = source.walk(sb.root, sb.rootLevel, 1u)
        require(roots.none { it.key.type == 132.toUByte() && it.key.objectid in 256uL..(ULong.MAX_VALUE - 255uL) }) { "Additional Btrfs subvolumes and snapshots are unsupported" }
        require(roots.none { it.key.type == 132.toUByte() && it.key.objectid == 8uL }) { "Btrfs quota groups are unsupported" }
        require(roots.filter { it.key.type == 132.toUByte() }.all { it.key.objectid in setOf(2uL, 4uL, 5uL, 7uL, 9uL, 10uL, RELOC) }) { "Unknown Btrfs root tree is unsupported" }
        val fsRoot = roots.lastOrNull { it.key.objectid == 5uL && it.key.type == 132.toUByte() } ?: error("Missing Btrfs filesystem root")
        require(fsRoot.bytes.size >= 239) { "Truncated Btrfs root item" }
        require(BtrfsDisk.u64(fsRoot.bytes, 208) == 0uL) { "Btrfs subvolume flags are unsupported" }
        val items = source.walk(BtrfsDisk.u64(fsRoot.bytes, 176), fsRoot.bytes[238].toInt() and 255, 5u, BtrfsDisk.u64(fsRoot.bytes, 160))
        require(items.none { it.key.type == 24.toUByte() }) { "Btrfs extended attributes and ACLs are unsupported" }
        val csums = mutableMapOf<ULong, UInt>()
        roots.lastOrNull { it.key.objectid == 7uL && it.key.type == 132.toUByte() }?.let { root ->
            for (entry in source.walk(BtrfsDisk.u64(root.bytes, 176), root.bytes[238].toInt() and 255, 7u)) {
                require(entry.key.objectid == CSUM && entry.key.type == 128.toUByte() && entry.bytes.size % 4 == 0) { "Invalid Btrfs checksum item" }
                require(entry.key.offset <= ULong.MAX_VALUE - (entry.bytes.size / 4).toULong() * SECTOR.toULong()) { "Btrfs checksum address overflow" }
                for (i in 0 until entry.bytes.size / 4) {
                    require(csums.size < MAX_CHECKSUM_ENTRIES) { "Btrfs checksum index exceeds metadata budget" }
                    val address = entry.key.offset + i.toULong() * SECTOR.toULong()
                    require(address !in csums) { "Overlapping Btrfs checksum items" }
                    csums[address] = BtrfsDisk.u32(entry.bytes, i * 4)
                }
            }
        }
        val inodes = items.filter { it.key.type == 1.toUByte() }.associateBy { it.key.objectid }
        for (inode in inodes.values) {
            BtrfsDisk.range(inode.bytes, 0, 160)
            require(BtrfsDisk.u32(inode.bytes, 40) == 1u) { "Btrfs hard links and unlinked inodes are unsupported" }
            require(BtrfsDisk.u64(inode.bytes, 64) == 0uL) { "Btrfs inode flags are unsupported" }
        }
        val directoryEntries = items.filter { it.key.type == 96.toUByte() }.groupBy { it.key.objectid }
        val fileExtents = items.filter { it.key.type == 108.toUByte() }.groupBy { it.key.objectid }
        val out = mutableListOf<BtrfsImageEntry>()
        val seenDirectories = mutableSetOf<ULong>()
        val seenInodes = mutableSetOf<ULong>()
        var fileBytes = 0L
        fun metadata(inode: Item, path: String, data: ByteArray? = null): BtrfsImageEntry = BtrfsImageEntry(
            path = path, data = data, mode = BtrfsDisk.u32(inode.bytes, 52),
            modifiedSeconds = BtrfsDisk.u64(inode.bytes, 136).toLong(), modifiedNanoseconds = BtrfsDisk.u32(inode.bytes, 144).toInt(),
            uid = BtrfsDisk.u32(inode.bytes, 44), gid = BtrfsDisk.u32(inode.bytes, 48),
            accessedSeconds = BtrfsDisk.u64(inode.bytes, 112).toLong(), accessedNanoseconds = BtrfsDisk.u32(inode.bytes, 120).toInt(),
            changedSeconds = BtrfsDisk.u64(inode.bytes, 124).toLong(), changedNanoseconds = BtrfsDisk.u32(inode.bytes, 132).toInt(),
            createdSeconds = BtrfsDisk.u64(inode.bytes, 148).toLong(), createdNanoseconds = BtrfsDisk.u32(inode.bytes, 156).toInt(),
        ).also(::validateMetadata)
        suspend fun file(inode: Item): ByteArray {
            BtrfsDisk.range(inode.bytes, 0, 160)
            val size = BtrfsDisk.u64(inode.bytes, 16)
            require(size <= (MAX_SNAPSHOT_DATA_BYTES - fileBytes).toULong()) { "Btrfs snapshot exceeds 256 MiB file-data budget" }
            fileBytes += size.toLong()
            val bytes = ByteArray(size.toInt())
            var previousEnd = 0uL
            for (extent in fileExtents[inode.key.objectid].orEmpty().sortedBy { it.key.offset }) {
                val b = extent.bytes
                BtrfsDisk.range(b, 0, 21)
                require(b[16] == 0.toByte() && b[17] == 0.toByte() && BtrfsDisk.u16(b, 18) == 0) { "Unsupported Btrfs extent compression/encryption" }
                require(extent.key.offset >= previousEnd && extent.key.offset <= size) { "Overlapping Btrfs file extents" }
                val type = b[20].toInt() and 255
                if (type == 0) {
                    require(extent.key.offset == 0uL && BtrfsDisk.u64(b, 8) == (b.size - 21).toULong() && b.size - 21 == size.toInt()) { "Invalid Btrfs inline extent" }
                    b.copyInto(bytes, 0, 21); previousEnd = size
                } else {
                    require(type == 1 || type == 2) { "Unsupported Btrfs extent type" }
                    BtrfsDisk.range(b, 0, 53)
                    val disk = BtrfsDisk.u64(b, 21); val diskSize = BtrfsDisk.u64(b, 29)
                    val extentOffset = BtrfsDisk.u64(b, 37); val length = BtrfsDisk.u64(b, 45)
                    require(length <= Int.MAX_VALUE.toULong() && extent.key.offset <= ULong.MAX_VALUE - length) { "Btrfs file extent overflow" }
                    val end = minOf(size, extent.key.offset + length)
                    require(end >= extent.key.offset) { "Invalid Btrfs extent length" }
                    if (disk != 0uL && type == 1) {
                        require(disk % SECTOR.toULong() == 0uL && diskSize % SECTOR.toULong() == 0uL && extentOffset <= diskSize && length <= diskSize - extentOffset && disk <= ULong.MAX_VALUE - diskSize) { "Invalid Btrfs regular extent" }
                        val count = (end - extent.key.offset).toInt()
                        var copied = 0
                        while (copied < count) {
                            val at = disk + extentOffset + copied.toULong()
                            val sectorAddress = at / SECTOR.toULong() * SECTOR.toULong()
                            val data = source.logical(sectorAddress, SECTOR)
                            require(csums[sectorAddress] == BtrfsDisk.crc(data).inv().toUInt()) { "Missing or incorrect Btrfs data checksum" }
                            val skip = (at - sectorAddress).toInt(); val take = minOf(count - copied, SECTOR - skip)
                            data.copyInto(bytes, extent.key.offset.toInt() + copied, skip, skip + take); copied += take
                        }
                    }
                    previousEnd = extent.key.offset + length
                }
            }
            return bytes
        }
        suspend fun directory(id: ULong, path: String, depth: Int = 0) {
            require(depth <= MAX_DIRECTORY_DEPTH) { "Btrfs directory depth exceeds snapshot limit" }
            require(seenDirectories.add(id) && seenDirectories.size <= inodes.size) { "Cyclic Btrfs directory graph" }
            for (entry in directoryEntries[id].orEmpty().sortedBy { it.key.offset }) {
                require(depth < MAX_DIRECTORY_DEPTH) { "Btrfs directory depth exceeds snapshot limit" }
                var offset = 0
                while (offset < entry.bytes.size) {
                    val b = entry.bytes
                    BtrfsDisk.range(b, offset, 30)
                    val target = BtrfsDisk.key(b, offset)
                    val dataSize = BtrfsDisk.u16(b, offset + 25); val nameSize = BtrfsDisk.u16(b, offset + 27)
                    require(nameSize in 1..255) { "Invalid Btrfs directory name length" }
                    BtrfsDisk.range(b, offset + 30, nameSize + dataSize)
                    require(dataSize == 0 && target.type == 1.toUByte()) { "Subvolume or extended directory entries are unsupported" }
                    val name = b.decodeToString(offset + 30, offset + 30 + nameSize, throwOnInvalidSequence = true)
                    require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name) { "Invalid Btrfs directory name" }
                    val namePath = if (path.isEmpty()) name else "$path/$name"
                    require(namePath.encodeToByteArray().size <= 4096) { "Btrfs directory path exceeds snapshot limit" }
                    val inode = inodes[target.objectid] ?: error("Missing Btrfs inode")
                    require(seenInodes.add(target.objectid)) { "Btrfs hard-link identity is unsupported" }
                    val rawMode = BtrfsDisk.u32(inode.bytes, 52)
                    val mode = rawMode.toInt() and 61440
                    require((b[offset + 29].toInt() and 255) == if (mode == 16384) 2 else 1) { "Btrfs directory entry type disagrees with inode" }
                    if (mode == 16384) {
                        out.add(metadata(inode, namePath)); directory(target.objectid, namePath, depth + 1)
                    } else {
                        require(mode == 32768) { "Only Btrfs regular files and directories are supported" }
                        out.add(metadata(inode, namePath, file(inode)))
                    }
                    offset += 30 + nameSize + dataSize
                }
            }
        }
        val rootId = BtrfsDisk.u64(fsRoot.bytes, 168)
        val rootMetadata = metadata(inodes[rootId] ?: error("Missing Btrfs root directory inode"), "")
        seenInodes.add(rootId)
        directory(rootId, "")
        require(seenInodes == inodes.keys) { "Unreachable Btrfs inodes are unsupported" }
        require(out.map { it.path }.distinct().size == out.size) { "Duplicate Btrfs directory paths" }
        return BtrfsImageSnapshot(sb.generation, sb.label, out.sortedBy { it.path }, sb.fsid.map(UByte::toByte).toByteArray(), rootMetadata)
    }
}
