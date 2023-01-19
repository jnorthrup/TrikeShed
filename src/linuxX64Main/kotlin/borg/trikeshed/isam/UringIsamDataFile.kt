package borg.trikeshed.isam

import borg.trikeshed.isam.meta.IOMemento
import borg.trikeshed.lib.*
import borg.trikeshed.native.HasPosixErr
import kotlinx.cinterop.*
import platform.posix.*
import platform.posix.FILE
import platform.posix.MAP_PRIVATE
import platform.posix.O_RDONLY
import platform.posix.PROT_READ
import platform.posix.close
import platform.posix.fopen
import platform.posix.mmap
import platform.posix.munmap
import platform.posix.open
import platform.posix.stat
import simple.PosixFile
import simple.PosixOpenOpts
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
        fun write1(cursor: Cursor, datafilename: String): Unit = memScoped {
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

        /**exact same function but writes the file with IoUring set-up and writes and native heap */
        fun write (cursor: Cursor, datafilename: String): Unit {
            val metafilename = "$datafilename.meta"
            IsamMetaFileReader.write(metafilename, cursor.meta.map { colMeta: ColMeta -> colMeta as RecordMeta })


            // Create a file for writing
            val data: Int = open(datafilename, platform.posix.O_CREAT or platform.posix.O_WRONLY, 644.fromOctal())

            //create row buffer
            val meta: Series<RecordMeta> = cursor.meta α { it as RecordMeta }
            val rowLen: Int = meta.last().end
            val rowBuffer = ByteArray(rowLen)
            val clears: IntArray =
                meta.`▶`.withIndex().filter { it.value.type.networkSize == null }.map { it.index }.toIntArray()

            // Create an io_uring instance and set up the queue
            val ring: io_uring = nativeHeap.alloc<io_uring>()
            HasPosixErr.posixRequires(io_uring_queue_init(32, ring.ptr, 0) == 0) { "io_uring_queue_init" }

            // Create a submission queue entry
            val sqe: io_uring_sqe = nativeHeap.alloc<io_uring_sqe>()
            HasPosixErr.posixRequires(io_uring_get_sqe(ring.ptr) != null) { "io_uring_get_sqe" }
            //create  CValuesRef<CPointerVar<io_uring_cqe>> to pass to io_uring_wait_cqe()


            //do we need to pre-create cqe here also? or is it created by the io_uring_wait_cqe() call?
            val cqe: io_uring_cqe = nativeHeap.alloc<io_uring_cqe>()
            memScoped {
                val cPointerVarOfCqe = alloc<CPointerVar<io_uring_cqe>> { cqe.ptr }


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

                    //set up the io_uring_sqe
                    val offset: ULong = (y * rowLen).toULong()
                    io_uring_prep_write(sqe.ptr, data, rowBuffer.refTo(0), rowLen.toUInt(), offset)

                    //submit the io_uring_sqe
                    HasPosixErr.posixRequires(io_uring_submit(ring.ptr) == 1) { "io_uring_submit" }


                    //wait for the io_uring_cqe
                    HasPosixErr.posixRequires(
                        io_uring_wait_cqe(
                            ring.ptr,
                            cPointerVarOfCqe.ptr
                        ) == 0
                    ) { "io_uring_wait_cqe" }

                    //check the result
                    HasPosixErr.posixRequires(cqe.res == rowLen) { "io_uring_wait_cqe" }

                    //free the io_uring_cqe
                    io_uring_cqe_seen(ring.ptr, cqe.ptr)
                }
            }

            //close the io_uring
            io_uring_queue_exit(ring.ptr)

            //free the native heap
            nativeHeap.free(ring)
            nativeHeap.free(sqe)
            nativeHeap.free(cqe)


            //close the file
            close(data)

        }
    }
}



