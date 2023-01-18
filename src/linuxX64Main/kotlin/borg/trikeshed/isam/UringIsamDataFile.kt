package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.lib.CZero.nz
import borg.trikeshed.native.HasPosixErr
import kotlinx.cinterop.*
import platform.posix.*
import platform.posix.FILE
import platform.posix.MAP_PRIVATE
import platform.posix.O_RDONLY
import platform.posix.PROT_READ
import platform.posix.close
import platform.posix.fopen
import platform.posix.malloc
import platform.posix.mmap
import platform.posix.munmap
import platform.posix.open
import platform.posix.stat
import zlinux_uring.*

fun <T> UringIsamDataFile.use(block: (UringIsamDataFile) -> T): T {
val r = block(this)
close()
return r
}

class UringIsamDataFile constructor(
datafileFilename: String,
metafileFilename: String,
metafile: IsamMetaFileReader,
) : Cursor {
val datafileFilename: String = datafileFilename
val metafile: IsamMetaFileReader = metafile


val recordlen: Int by lazy {
this.metafile.recordlen.also {
require(it > 0) { "recordlen must be > 0" }
}

} // unfortunately due to separation of ctor and open, this is not immutable
val constraints: Series<RecordMeta> by lazy { metafile.constraints }
private lateinit var data: COpaquePointer
var fileSize: Long = -1

private var first = true
fun open() {
if (!first) return
memScoped {
val fd = open(datafileFilename, O_RDONLY)
val stat = alloc<stat>()
fstat(fd, stat.ptr)
fileSize = stat.st_size.toLong()

require(fileSize % recordlen == 0L) { "fileSize must be a multiple of recordlen" }


data = mmap(null, fileSize.toULong(), PROT_READ, MAP_PRIVATE, fd, 0)!!
close(fd)

// report on record alignment of the file
val alignment = fileSize % recordlen
if (alignment != 0L) {
println("WARN: file $datafileFilename is not aligned to recordlen $recordlen")
} else
println("DEBUG: file $datafileFilename is aligned to recordlen $recordlen")


println("DEBUG: each record is ${recordlen.toLong().humanReadableByteCountIEC} bytes long")

// mention record counts and percentages of each field type by record byte occupancy and by file byte occupancy, and percentage of file
val fieldCounts = mutableMapOf<IOMemento, Int>()
val fieldOccupancy = mutableMapOf<IOMemento, Int>()
constraints.forEach { constraint ->
val count = fieldCounts.getOrPut(constraint.type) { 0 }
fieldCounts[constraint.type] = count + 1
val occupancy = fieldOccupancy.getOrPut(constraint.type) { 0 }
fieldOccupancy[constraint.type] = occupancy + constraint.end - constraint.begin
}
val recordCount = fileSize / recordlen
println("DEBUG: file  $datafileFilename has $recordCount records in ${fileSize.humanReadableByteCountIEC}")
fieldCounts.forEach { (type, count) ->
val occupancy = fieldOccupancy[type]!!
println("DEBUG: file $datafileFilename has $count fields of type $type occupying $occupancy bytes (${occupancy * 100 / recordlen}%) of each record (${(occupancy * recordCount).humanReadableByteCountSI} in the file)")
}
}
}

override val a: Int get() = open().let { return (fileSize / recordlen).toInt() }
override val b: (Int) -> Join<Int, (Int) -> Join<*, () -> RecordMeta>> = { row ->
memScoped {
val d2 = data.toLong() + (row * recordlen)

constraints.size j { col ->
constraints[col].let { recordMeta ->
val d4 = d2 + recordMeta.begin
val d5: COpaquePointer = d4.toCPointer()!!
val d6: ByteArray = d5.readBytes(recordMeta.end - recordMeta.begin)
recordMeta.decoder(d6)!! j { recordMeta }
}
}
}
}

override fun toString(): String =
"IsamDataFile(metafile=$metafile, recordlen=$recordlen, constraints=$constraints, datafileFilename='$datafileFilename', fileSize=$fileSize)"

fun close() {
memScoped {
munmap(data, fileSize.toULong())
}
}


companion object {
fun write(cursor: Cursor, datafilename: String): Unit = memScoped {
val metafilename = "$datafilename.meta"

IsamMetaFileReader.write(metafilename, cursor.meta.map { colMeta: ColMeta -> colMeta as RecordMeta })

//open RandomAccessDataFile
val data: CPointer<FILE>? = fopen(datafilename, "w")

//create row buffer
val meta: Series<RecordMeta> = cursor.meta α { it as RecordMeta }
val rowLen: Int = meta.last().end
val rowBuffer = ByteArray(rowLen)
val clears: IntArray =
meta.`▶`.withIndex().filter { it.value.type.networkSize == null }.map { it.index }.toIntArray()

//write rows
for (y in 0 until cursor.a) {
val rowData: Series<*> = cursor.row(y).left

for (x in 0 until cursor.meta.size) {
val colMeta: RecordMeta = meta[x]
val colData: Any? = rowData[x]
val colBytes: ByteArray = colMeta.encoder(colData)
colBytes.copyInto(rowBuffer, colMeta.begin, 0, colBytes.size)
if (x in clears && colBytes.size < colMeta.end - colMeta.begin)   //write 1 zero
rowBuffer[colMeta.begin + colBytes.size] = 0
}
val fwrite: ULong = fwrite(rowBuffer.refTo(0), 1, rowLen.toULong(), data)
}
val fclose: Int = fclose(data)
}

/**exact same function but writes the file with IoUring set-up and writes */
fun writeUring(cursor: Cursor, datafilename: String): Unit = memScoped {
val metafilename = "$datafilename.meta"

IsamMetaFileReader.write(metafilename, cursor.meta.map { colMeta: ColMeta -> colMeta as RecordMeta })


//create row buffer
val meta: Series<RecordMeta> = cursor.meta α { it as RecordMeta }
val rowLen: Int = meta.last().end
val rowBuffer = ByteArray(rowLen)
val clears: IntArray =// if the ioMemento is null, then it is a variable length field, and we end as null
meta.`▶`.withIndex().filter { it.value.type.networkSize == null }.map { it.index }.toIntArray()

//set up liburing
val ring = malloc(io_uring.size.convert())!!.reinterpret<io_uring>()
val ringParams = malloc(io_uring_params.size.convert())!!.reinterpret<io_uring_params>()
ringParams.pointed.sq_entries = 1024.toUInt()
ringParams.pointed.cq_entries = 1024.toUInt()
ringParams.pointed.flags = IORING_SETUP_IOPOLL

val ret: Int = io_uring_queue_init(32, ring, 0)  //initialize the ring
HasPosixErr.posixRequires(ret.nz) { "io_uring_queue_init" }

//open RandomAccessDataFile
val data: CPointer<FILE>? = fopen(datafilename, "w")
val fd: Int = fileno(data)

//write rows
for (y in 0 until cursor.a) {
val rowData: Series<*> = cursor.row(y).left

for (x in 0 until cursor.meta.size) {
val colMeta: RecordMeta = meta[x]
val colData: Any? = rowData[x]
val colBytes: ByteArray = colMeta.encoder(colData)
colBytes.copyInto(rowBuffer, colMeta.begin, 0, colBytes.size)
if (x in clears && colBytes.size < colMeta.end - colMeta.begin)   //write 1 zero
rowBuffer[colMeta.begin + colBytes.size] = 0
}
// val fwrite: ULong = fwrite(rowBuffer.refTo(0), 1, rowLen.toULong(), data)
//use io_uring instead
val sqe: CPointer<io_uring_sqe>? = io_uring_get_sqe(ring)  //get a submission queue entry
io_uring_prep_write(
sqe,
fd,
rowBuffer.refTo(0),
rowLen.toUInt(),
0
)  //prepare the submission queue entry
io_uring_submit(ring)  //submit the submission queue entry
io_uring_wait_cqe(ring, null)  //wait for the completion queue entry
}
val fclose: Int = fclose(data)
} } }
